package com.sendspindroid.network

import android.util.Log
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Periodically pings the default server's local address when mDNS doesn't
 * find it on the local network (e.g., the device is on cellular, or has
 * temporarily lost the mDNS announcement).
 *
 * ## Purpose
 * mDNS only discovers servers on the local network, and its announcements
 * can be missed. This pinger enables auto-connect by checking if the
 * default server's local address is directly reachable.
 *
 * A server whose only configured method is remote or proxy (legacy data)
 * cannot be reached at all -- those transports were removed.
 *
 * ## Adaptive Intervals
 * | Condition                | Interval |
 * |--------------------------|----------|
 * | App in foreground        | 60s      |
 * | Device charging          | 60s      |
 * | Background + not charging| 120s     |
 * | After failed ping        | Backoff (doubles, max 5 min) |
 * | Network change           | Immediate |
 *
 * ## Usage
 * ```kotlin
 * val pinger = DefaultServerPinger(
 *     onServerReachable = { server -> onUnifiedServerSelected(server) }
 * )
 *
 * // Start when showing server list (not user-disconnected)
 * pinger.start()
 *
 * // Stop when connected or user manually disconnected
 * pinger.stop()
 *
 * // Notify of state changes
 * pinger.onForegroundChanged(true)
 * pinger.onChargingChanged(true)
 * pinger.onNetworkChanged()
 * ```
 */
class DefaultServerPinger(
    private val onServerReachable: (UnifiedServer) -> Unit
) {

    companion object {
        private const val TAG = "DefaultServerPinger"

        // Adaptive intervals
        private const val INTERVAL_FOREGROUND_MS = 60_000L   // 60s when foreground or charging
        private const val INTERVAL_BACKGROUND_MS = 120_000L  // 120s when background + not charging
        private const val MAX_BACKOFF_MS = 300_000L          // 5 min max after failures
        private const val INITIAL_BACKOFF_MS = 60_000L       // Start backoff at 60s

        // Ping timeouts
        private const val LOCAL_PING_TIMEOUT_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State
    private val isRunning = AtomicBoolean(false)
    private val isPinging = AtomicBoolean(false)
    private var pingJob: Job? = null
    private var scheduledPingJob: Job? = null

    // Adaptive interval state
    private var isInForeground = true
    private var isCharging = false
    private val consecutiveFailures = AtomicInteger(0)

    // OkHttp client for WebSocket ping attempts
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket
        .build()

    /**
     * Starts periodic pinging for the default server.
     * Only pings if:
     * - Default server exists with remote or proxy config
     * - mDNS hasn't discovered it locally
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Already running")
            return
        }

        Log.i(TAG, "Starting default server pinger")
        consecutiveFailures.set(0)

        // Trigger an immediate ping, then schedule periodic
        pingNow()
    }

    /**
     * Stops periodic pinging.
     * Called when connected or user manually disconnected.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "Already stopped")
            return
        }

        Log.i(TAG, "Stopping default server pinger")
        scheduledPingJob?.cancel()
        scheduledPingJob = null
        pingJob?.cancel()
        pingJob = null
        isPinging.set(false)
    }

    /**
     * Triggers an immediate ping (e.g., on network change or app resume).
     * Cancels any scheduled ping and starts fresh.
     */
    fun pingNow() {
        if (!isRunning.get()) {
            Log.d(TAG, "Not running, ignoring pingNow()")
            return
        }

        // Cancel scheduled ping (we're pinging now)
        scheduledPingJob?.cancel()
        scheduledPingJob = null

        // If already pinging, don't stack up
        if (isPinging.get()) {
            Log.d(TAG, "Already pinging, skipping")
            return
        }

        performPing()
    }

    /**
     * Called when network type changes (WiFi <-> cellular).
     * Triggers an immediate ping.
     */
    fun onNetworkChanged() {
        if (!isRunning.get()) return

        Log.i(TAG, "Network changed - triggering immediate ping")
        pingNow()
    }

    /**
     * Called when app foreground state changes.
     * Adjusts the ping interval accordingly.
     */
    fun onForegroundChanged(inForeground: Boolean) {
        val wasInForeground = isInForeground
        isInForeground = inForeground

        if (!isRunning.get()) return

        if (inForeground && !wasInForeground) {
            // Coming to foreground - trigger immediate ping
            Log.i(TAG, "App foregrounded - triggering immediate ping")
            pingNow()
        } else if (!inForeground && wasInForeground) {
            // Going to background - let current schedule continue
            Log.d(TAG, "App backgrounded - interval will adjust on next schedule")
        }
    }

    /**
     * Called when device charging state changes.
     * Adjusts the ping interval accordingly.
     */
    fun onChargingChanged(charging: Boolean) {
        val wasCharging = isCharging
        isCharging = charging

        if (!isRunning.get()) return

        if (charging && !wasCharging && !isInForeground) {
            // Just plugged in while in background - adjust interval immediately
            Log.i(TAG, "Device plugged in while in background - adjusting interval")
            // Re-schedule with new interval if we have a pending scheduled ping
            if (scheduledPingJob?.isActive == true) {
                scheduledPingJob?.cancel()
                scheduleNextPing()
            }
        }
    }

    /**
     * Performs the actual ping attempt.
     */
    private fun performPing() {
        if (isPinging.getAndSet(true)) {
            Log.d(TAG, "Ping already in progress")
            return
        }

        pingJob = scope.launch {
            try {
                val defaultServer = UnifiedServerRepository.getDefaultServer()
                if (defaultServer == null) {
                    Log.d(TAG, "No default server configured")
                    return@launch
                }

                // Only a local address can be pinged; remote/proxy config (legacy
                // data) has no transport left to reach it with.
                val local = defaultServer.local
                if (local == null) {
                    Log.d(TAG, "Default server has no local address configured")
                    return@launch
                }

                // Check if mDNS has already discovered this server locally
                val onlineIds = UnifiedServerRepository.onlineSavedServerIds.first()
                if (onlineIds.contains(defaultServer.id)) {
                    Log.d(TAG, "Default server already discovered via mDNS - skipping ping")
                    // Reset failures since server is available
                    consecutiveFailures.set(0)
                    return@launch
                }

                Log.d(TAG, "Pinging default server: ${defaultServer.name}")

                if (!isRunning.get()) {
                    Log.d(TAG, "Pinger stopped during ping attempt")
                    return@launch
                }

                if (pingLocal(local.address, local.path)) {
                    Log.i(TAG, "Default server reachable via LOCAL - triggering connect")
                    consecutiveFailures.set(0)

                    // Trigger connection on main thread
                    withContext(Dispatchers.Main) {
                        if (isRunning.get()) {
                            onServerReachable(defaultServer)
                        }
                    }
                    return@launch
                }

                val failures = consecutiveFailures.incrementAndGet()
                Log.d(TAG, "Ping failed (consecutive failures: $failures)")

            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Ping error", e)
                consecutiveFailures.incrementAndGet()
            } finally {
                isPinging.set(false)

                // Schedule next ping if still running
                if (isRunning.get()) {
                    scheduleNextPing()
                }
            }
        }
    }

    /**
     * Schedules the next ping based on current state.
     */
    private fun scheduleNextPing() {
        val interval = calculateNextInterval()
        Log.d(TAG, "Scheduling next ping in ${interval / 1000}s")

        scheduledPingJob = scope.launch {
            delay(interval)
            if (isRunning.get()) {
                performPing()
            }
        }
    }

    /**
     * Calculates the next ping interval based on foreground/charging state and failures.
     */
    private fun calculateNextInterval(): Long {
        val baseInterval = if (isInForeground || isCharging) {
            INTERVAL_FOREGROUND_MS
        } else {
            INTERVAL_BACKGROUND_MS
        }

        // Apply exponential backoff on failures, but never go below base interval
        val failures = consecutiveFailures.get()
        return if (failures > 0) {
            val backoff = INITIAL_BACKOFF_MS * (1L shl (failures - 1).coerceAtMost(4))
            maxOf(backoff, baseInterval).coerceAtMost(MAX_BACKOFF_MS)
        } else {
            baseInterval
        }
    }

    /**
     * Pings a local server via WebSocket handshake.
     * Returns true if the WebSocket connection handshake succeeds.
     */
    private suspend fun pingLocal(address: String, path: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = WebSocketUrlBuilder.build(address, path)
                Log.d(TAG, "Ping local: $url")

                withTimeout(LOCAL_PING_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val request = Request.Builder()
                            .url(url)
                            .build()

                        val webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d(TAG, "Local ping success: $address")
                                webSocket.close(1000, "Ping complete")
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                Log.d(TAG, "Local ping failed: ${t.message}")
                                if (cont.isActive) cont.resume(false)
                            }
                        })

                        cont.invokeOnCancellation {
                            webSocket.cancel()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.d(TAG, "Local ping timeout: $address")
                false
            } catch (e: Exception) {
                Log.d(TAG, "Local ping error: ${e.message}")
                false
            }
        }
    }

    /**
     * Cleans up resources.
     */
    fun destroy() {
        stop()
        scope.cancel()
    }
}
