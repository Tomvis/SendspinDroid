package com.sendspindroid.coordinator

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sendspindroid.logging.throwableSummary
import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.NetworkState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Events emitted by [ConnectionCoordinator] for Android-specific network signals
 * that cannot be represented in the platform-neutral [NetworkState] data class.
 *
 * PlaybackService subscribes to [ConnectionCoordinator.networkEvents] to dispatch
 * side effects (onNetworkChanged, disconnectForReselection, multicast-lock refresh)
 * that depend on these signals.
 */
sealed class NetworkEvent {
    /**
     * The followed network (the best one with internet that is not a VPN) is
     * gone and another has taken its place (different [networkHandle]), other
     * than one left earlier while it was still up.
     * Indicates a transport handover (e.g., WiFi -> Cellular). The reconnect
     * loop should re-attempt the local connection on the new network.
     */
    data class IdentityChanged(val networkHandle: Long) : NetworkEvent()

    /**
     * An address was added to or removed from the followed network without an
     * identity change: a DHCP renewal, or an IPv6 temporary address rotating.
     * Also sent with the first addresses of a network followed without an
     * identity change.
     * A connection open on another address carries on; what depends on the
     * address set (multicast lock, NSD advertisement) is refreshed.
     */
    data class LinkAddressesChanged(val addresses: Set<LinkAddress>) : NetworkEvent()
}

/**
 * Single authority for "what server is active, and which of its transports are up,"
 * plus the one reconnect loop in the app. PlaybackService starts the loop when a
 * connection is lost and cancels it when the user disconnects or picks a server;
 * neither the client nor the UI retries on its own.
 *
 * The loop:
 * - backs off 500ms, 1s, 2s, 4s, 8s, 15s, 30s, then every 60s, with no attempt cap
 * - cuts the wait short on [retryNow] (network back, server seen again on mDNS),
 *   debounced to once per 2s and followed by a 500ms settling delay
 *
 * Also owns the ConnectivityManager.NetworkCallback. PlaybackService observes
 * [networkState] and [networkEvents] to dispatch side effects.
 *
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
class ConnectionCoordinator(
    currentServerFlow: Flow<UnifiedServer?>,
    sendSpinStateFlow: Flow<TransportState>,
    private val scope: CoroutineScope,
    private val connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
    private val context: android.content.Context,
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        // The wait before each attempt. The last value repeats for as long as
        // the server stays away: there is no attempt cap.
        private val BACKOFF_DELAYS = listOf(
            500L, 1000L, 2000L, 4000L, 8000L, 15000L, 30000L, 60000L,
        )

        /**
         * Fork: the wait (ms) before 1-based [attempt], as the loop applies it,
         * so the UI countdown keys off the real schedule. Clamped to the table.
         */
        fun backoffDelayMsForAttempt(attempt: Int): Long =
            BACKOFF_DELAYS[(attempt - 1).coerceIn(BACKOFF_DELAYS.indices)]

        private const val RETRY_NOW_DEBOUNCE_MS = 2_000L
        private const val MIN_DELAY_AFTER_RETRY_NOW_MS = 500L
        private const val VALIDATION_LOSS_DEBOUNCE_MS = 3_000L
    }

    val sessionState: StateFlow<SessionState> = combine(
        currentServerFlow,
        sendSpinStateFlow,
    ) { server, sendSpin ->
        SessionState(server = server, sendSpin = sendSpin)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionState(),
    )

    private val _reconnectStatusFlow = MutableStateFlow<ReconnectStatus>(ReconnectStatus.Idle)
    val reconnectStatus: StateFlow<ReconnectStatus> = _reconnectStatusFlow

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState

    // Android-specific network events that cannot be encoded in the platform-neutral NetworkState.
    // Replay=0: events are ephemeral -- a late subscriber should not re-process stale events.
    private val _networkEvents = MutableSharedFlow<NetworkEvent>(replay = 0, extraBufferCapacity = 8)
    val networkEvents: SharedFlow<NetworkEvent> = _networkEvents.asSharedFlow()

    private var reconnectJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)
    @Volatile private var skipDelay: CompletableDeferred<Unit>? = null
    @Volatile private var lastRetryNowNanos: Long = 0L

    // Network callback state -- written only on the main thread, where the
    // callback runs (see init) and close() is called.
    // The network whose identity and addresses are followed: never a VPN.
    @Volatile private var lastNetwork: Network? = null
    // The followed network when the connection last became Ready: where a
    // connection open now is. Null while there is none, and once a handover
    // has given it up.
    @Volatile private var sessionNetwork: Network? = null
    @Volatile private var lastLinkAddresses: Set<Pair<java.net.InetAddress, Int>>? = null
    // Set when a network is followed without an identity change: its first
    // addresses are reported rather than taken as the baseline, since the
    // multicast lock and the advertisement were last refreshed on another.
    @Volatile private var reportFirstAddresses = false
    @Volatile private var lastValidatedState: Boolean? = null
    private var validationLossJob: Job? = null

    private val networkEvaluator: NetworkEvaluator? = try {
        NetworkEvaluator(context)
    } catch (e: Exception) {
        Log.w(TAG, "NetworkEvaluator unavailable (test environment?)", e)
        null
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: handle=${network.networkHandle} (last=${lastNetwork?.networkHandle})")

            networkEvaluator?.evaluateCurrentNetwork(network)
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            // A network is back: no point waiting out the rest of a backoff.
            retryNow()
            // Whether it is a new network identity is decided in
            // onCapabilitiesChanged, which always follows straight after.
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: handle=${network.networkHandle}")
            val stillHaveNetwork = connectivityManager?.activeNetwork != null
            networkEvaluator?.evaluateCurrentNetwork(
                if (stillHaveNetwork) connectivityManager?.activeNetwork else null
            )
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            // Cancel any pending validation-loss debounce; onLost is authoritative.
            validationLossJob?.cancel()
            validationLossJob = null

            if (!stillHaveNetwork) {
                Log.i(TAG, "No active network remaining")
                lastLinkAddresses = null
            } else {
                Log.d(TAG, "Another network still active")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            Log.d(TAG, "Network capabilities changed: handle=${network.networkHandle}")
            networkEvaluator?.evaluateCurrentNetwork(network)
            networkEvaluator?.networkState?.value?.let { _networkState.value = it }

            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val wasValidated = lastValidatedState
            lastValidatedState = isValidated

            // Skip transition logic on first callback (wasValidated == null).
            if (wasValidated == true && !isValidated) {
                Log.w(TAG, "Network lost VALIDATED - debouncing ${VALIDATION_LOSS_DEBOUNCE_MS}ms")
                validationLossJob?.cancel()
                validationLossJob = scope.launch {
                    kotlinx.coroutines.delay(VALIDATION_LOSS_DEBOUNCE_MS)
                    if (lastValidatedState == false) {
                        Log.w(TAG, "Validation loss confirmed after debounce - marking network unavailable")
                        _networkState.value = _networkState.value.copy(isConnected = false)
                    }
                }
            } else if (wasValidated == false && isValidated) {
                Log.i(TAG, "Network regained VALIDATED")
                validationLossJob?.cancel()
                validationLossJob = null
                // Re-emit the current (connected) evaluator state.
                networkEvaluator?.networkState?.value?.let { _networkState.value = it }
            }

            // A VPN is never the identity: a LAN connection stays on the
            // network under it, and carries on as the VPN comes and goes.
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) follow(network)
        }

        /**
         * Follow [network] from now on. The first one is the baseline. A
         * different one is a handover only if the network the connection is
         * on is gone: Wi-Fi that lost validation gives way to cellular but
         * stays connected, and a connection open on it carries on, also when
         * Wi-Fi is the best network again. With no connection, the network
         * just left stands in for it.
         */
        private fun follow(network: Network) {
            val prev = lastNetwork
            lastNetwork = network
            if (prev == null || prev.networkHandle == network.networkHandle) return

            // The next onLinkPropertiesChanged takes the new network's baseline.
            lastLinkAddresses = null
            val anchor = sessionNetwork ?: prev
            // May be a step behind the callbacks. A stale answer that the
            // network is still up only leaves the reconnect to the failure of
            // the socket on it, which resets the clock as well.
            if (anchor.networkHandle == network.networkHandle ||
                connectivityManager?.getNetworkCapabilities(anchor) != null
            ) {
                Log.i(TAG, "Following network ${network.networkHandle}; ${anchor.networkHandle} is still up")
                reportFirstAddresses = true
                return
            }
            Log.i(TAG, "Network identity changed: ${anchor.networkHandle} -> ${network.networkHandle}")
            sessionNetwork = null
            scope.launch { _networkEvents.emit(NetworkEvent.IdentityChanged(network.networkHandle)) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Only care about changes on the followed network.
            if (network.networkHandle != lastNetwork?.networkHandle) return

            // Address and prefix only: LinkAddress equality also compares flags
            // and lifetimes, which change on every router advertisement.
            val addresses = linkProperties.linkAddresses.toSet()
            val prev = lastLinkAddresses
            val current = addresses.map { it.address to it.prefixLength }.toSet()
            lastLinkAddresses = current

            if (reportFirstAddresses) {
                reportFirstAddresses = false
                Log.i(TAG, "Link addresses of the newly followed network: $current")
            } else {
                // First callback after onAvailable establishes the baseline -- no action.
                if (prev == null) return
                // Filter noise: DNS reorders, MTU changes, route updates all fire this.
                if (prev == current) return
                Log.i(TAG, "Link addresses changed on active network: $prev -> $current")
            }
            scope.launch { _networkEvents.emit(NetworkEvent.LinkAddressesChanged(addresses)) }
        }
    }

    init {
        scope.launch {
            sessionState.collect { state ->
                if (state.sendSpin is TransportState.Ready) {
                    if (sessionNetwork == null) sessionNetwork = lastNetwork
                } else {
                    sessionNetwork = null
                }
            }
        }

        val cm = connectivityManager
        if (cm != null) {
            // One network at a time: a callback for every network with
            // internet would see a cellular network kept up in the background
            // while on Wi-Fi, and treat its comings and goings as a handover.
            // On the main thread, like close() and the event collectors, so the
            // callback state is only ever touched from one thread.
            val handler = Handler(Looper.getMainLooper())
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The best network with internet that is not a VPN (the
                    // builder's default NOT_VPN): under a VPN, Wi-Fi's drops,
                    // rejoins and address changes are still seen.
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerBestMatchingNetworkCallback(request, networkCallback, handler)
                } else {
                    // The app's default network. Under a VPN that is the VPN,
                    // which these versions then see alone: nothing of the
                    // network under it reaches the callback.
                    cm.registerDefaultNetworkCallback(networkCallback, handler)
                }
                Log.d(TAG, "Network callback registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register network callback", e)
            }
        } else {
            Log.w(TAG, "ConnectivityManager unavailable - network callback not registered")
        }
    }

    /**
     * Releases resources owned by this Coordinator. Call from PlaybackService.onDestroy
     * before cancelling the service scope.
     */
    fun close() {
        validationLossJob?.cancel()
        validationLossJob = null
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /**
     * Keep trying to connect to [server] until an attempt succeeds or
     * [cancelReconnect] is called. Replaces any loop already running.
     */
    fun connect(server: UnifiedServer) {
        cancelReconnect()
        isReconnecting.set(true)
        lastRetryNowNanos = 0L
        // Published here and not from inside the loop, so the caller can rely
        // on the status as soon as this returns.
        _reconnectStatusFlow.value = ReconnectStatus.Attempting(server.id, attempt = 1, method = null)
        reconnectJob = scope.launch {
            runReconnectLoop(server)
        }
    }

    fun cancelReconnect() {
        // Fork: always cancel a held job. The loop clears isReconnecting on
        // Succeeded/Failed before returning, and a flag-only early return in
        // that window would leave the job behind.
        reconnectJob?.cancel()
        reconnectJob = null
        skipDelay = null
        isReconnecting.set(false)
        lastRetryNowNanos = 0L
        _reconnectStatusFlow.value = ReconnectStatus.Idle
    }

    /**
     * Skip what is left of the current backoff wait, because something says
     * the next attempt is worth making now: a network came up, or the server
     * was seen again on mDNS. Does nothing when no loop is waiting.
     */
    fun retryNow() {
        if (!isReconnecting.get()) return
        val now = System.nanoTime()
        val elapsedMs = (now - lastRetryNowNanos) / 1_000_000
        if (elapsedMs < RETRY_NOW_DEBOUNCE_MS) return
        lastRetryNowNanos = now
        skipDelay?.complete(Unit)
    }

    private suspend fun runReconnectLoop(server: UnifiedServer) {
        var attemptNumber = 0
        while (true) {
            // An attempt may have cancelled the loop it was running in.
            coroutineContext.ensureActive()
            attemptNumber++
            val delayMs = BACKOFF_DELAYS.getOrElse(attemptNumber - 1) { BACKOFF_DELAYS.last() }

            _reconnectStatusFlow.value = ReconnectStatus.Attempting(
                serverId = server.id,
                attempt = attemptNumber,
                method = null,
            )

            val signal = CompletableDeferred<Unit>()
            skipDelay = signal
            var skipped = false
            try {
                withTimeout(delayMs) {
                    signal.await()
                    skipped = true
                }
            } catch (_: TimeoutCancellationException) {
                // Normal: full delay elapsed.
            }
            skipDelay = null

            if (skipped) delay(MIN_DELAY_AFTER_RETRY_NOW_MS)
            coroutineContext.ensureActive()

            val methods = priorityMethodsForCurrentNetwork()
            var succeeded = false
            for (method in methods) {
                coroutineContext.ensureActive()
                if (!serverHasMethod(server, method)) continue

                _reconnectStatusFlow.value = ReconnectStatus.Attempting(
                    serverId = server.id,
                    attempt = attemptNumber,
                    method = method,
                )

                try {
                    if (connectAttempt(server, method)) {
                        succeeded = true
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Continue to next method. Fork: log a bounded summary so a
                    // throwing attempt is visible in triage.
                    Log.w(TAG, "connectAttempt(${server.id}, $method) threw; trying next method: " + throwableSummary(e))
                }
            }

            if (succeeded) {
                _reconnectStatusFlow.value = ReconnectStatus.Succeeded(server.id)
                isReconnecting.set(false)
                return
            }
        }
    }

    private fun priorityMethodsForCurrentNetwork(): List<ConnectionType> {
        return ConnectionSelector.getPriorityOrder()
    }

    private fun serverHasMethod(server: UnifiedServer, method: ConnectionType): Boolean = when (method) {
        ConnectionType.LOCAL -> server.local != null
        ConnectionType.REMOTE -> server.remote != null
        ConnectionType.PROXY -> server.proxy != null
    }
}
