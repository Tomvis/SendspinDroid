package com.sendspindroid.sendspin

import android.os.Build
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.logging.AppLog
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.sendspin.protocol.ControllerState
import com.sendspindroid.sendspin.protocol.GroupInfo
import com.sendspindroid.sendspin.protocol.SendSpinProtocol

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.asNoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseHandshakeException
import com.sendspindroid.sendspin.crypto.AndroidPairingConfigStore
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.crypto.PskCandidates
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import kotlinx.serialization.json.JsonObject
import com.sendspindroid.sendspin.protocol.RehandshakeDriver
import com.sendspindroid.sendspin.protocol.GoodbyeReason
import com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler
import com.sendspindroid.sendspin.protocol.StreamConfig
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.pairing.PairMethod
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

/**
 * Native Kotlin SendSpin client.
 *
 * Implements the Sendspin Protocol for synchronized multi-room audio streaming.
 * Protocol spec: https://www.sendspin-audio.com/spec/
 *
 * ## Protocol Overview
 * 1. Connect via WebSocket
 * 2. Send client/hello with capabilities
 * 3. Receive server/hello with active roles
 * 4. Send client/time messages continuously for clock sync
 * 5. Receive binary audio chunks (type 4) with microsecond timestamps
 * 6. Play audio at computed client time using Kalman-filtered offset
 *
 * ## Connection Mode
 * - **Local**: Direct WebSocket to server on local network (ws://host:port/sendspin)
 *
 * This class extends SendSpinProtocolHandler for shared protocol logic
 * and implements client-specific concerns:
 * - WebSocket transport
 * - Connection state machine (Disconnected/Connecting/Connected/Error)
 * - Reconnection with exponential backoff
 * - Time filter freeze/thaw during reconnection
 */
class SendSpin(
    private val deviceName: String,
    private val callback: Callback
) : SendSpinProtocolHandler(TAG) {

    companion object {
        private const val TAG = "SendSpin"

        // Reconnection configuration
        // Short initial delay (500ms) to maximize reconnect attempts during buffer drain
        // Sequence: 500ms, 1s, 2s, 4s, 8s - gives ~5 attempts in first 15 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 500L // 500ms (was 1s)
        private const val MAX_RECONNECT_DELAY_MS = 10000L // 10 seconds (was 30s)
        private const val HIGH_POWER_RECONNECT_DELAY_MS = 30_000L // 30s steady-state for high power mode

        // Hard ceiling on reconnect attempts per cycle. At the current schedule
        // (exp backoff for 5, then 30s each) this caps the total try-window at
        // about 7m45s. Beyond that we surface the failure to the UI via
        // onDisconnected(wasReconnectExhausted=true) and stop scheduling attempts.
        // Reset to 0 on every successful handshake.
        private const val MAX_TOTAL_RECONNECT_ATTEMPTS = 20

        // Stall watchdog: while connected+handshake-complete, if no bytes arrive for
        // this long, force-close the transport so the existing reconnect path kicks in.
        // Shorter than Ktor's 30s ping-timeout to beat buffer drain.
        private const val STALL_TIMEOUT_MS = 7_000L
        private const val STALL_CHECK_INTERVAL_MS = 3_000L

        // Idle-mode stall threshold. Larger than the streaming threshold because during
        // idle the only regular server->client traffic is server/time responses to our
        // TimeSyncManager bursts. Burst cadence is 500ms-3s once converged, so ~9s is
        // the worst-case natural silence; 20s gives 2x headroom while still catching
        // server death with headroom for reconnect + resync inside the ~30s audio buffer.
        // Issue #127.
        private const val IDLE_STALL_TIMEOUT_MS = 20_000L
    }

    /**
     * Callback interface for SendSpin events.
     */
    interface Callback {
        fun onServerDiscovered(name: String, address: String)
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        fun onMetadataUpdate(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String,
            durationMs: Long,
            positionMs: Long,
            playbackSpeed: Int = 1000
        )
        fun onArtwork(imageData: ByteArray)
        fun onArtworkCleared()
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?)
        fun onStreamClear()
        fun onStreamEnd()
        fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray)
        fun onVolumeChanged(volume: Int)
        fun onMutedChanged(muted: Boolean)
        fun onSyncOffsetApplied(offsetMs: Double, source: String)
        fun onNetworkChanged()

        /**
         * Called when audio output should be silenced or unsilenced because
         * the client cannot maintain sync. Per Sendspin spec, "error" state
         * mutes audio while continuing to drain the buffer. Implementations
         * should forward this to the audio sink. Default no-op for callers
         * that don't render audio.
         */
        fun onSyncMuteChanged(muted: Boolean) {}

        /**
         * The server dropped its pairing with this device.
         *
         * Without surfacing this, an unpair is indistinguishable from a network
         * drop: the player simply stops appearing, and the app looks broken
         * rather than deliberately disconnected.
         *
         * @param serverId the server the removed record paired with; null only
         *   for an unbound record left over from before 1.0.0-rc1.
         */
        fun onUnpaired(serverId: String?) {}

        /**
         * The connection was accepted but cannot carry playback, and why.
         *
         * Without this the two blocked states are invisible: the app connects,
         * reports itself connected, and then sits with no roles and no
         * explanation, which reads as a hang rather than as waiting on an
         * action the operator has to take on the server.
         */
        fun onAdmissionStateChanged(state: AdmissionState) {}

        /** Show this dynamic pairing code to the operator. */
        fun onDynamicPairingCodeEmitted(code: String) {}

        /** Stop showing a dynamic pairing code (the attempt ended, one way or another). */
        fun onDynamicPairingCodeCleared() {}

        /** The dynamic pairing attempt is gesture-gated; show "Allow pairing". */
        fun onDynamicPairingGestureRequested() {}
    }

    // Dedicated single-thread dispatcher for timer-dominated work: stall
    // watchdog polling, reconnect backoff delays, TimeSyncManager's
    // periodic scheduler. Isolating this from Dispatchers.IO means timer
    // latency is bounded by a single thread's scheduling, not by shared
    // pool contention with blocking IO work.
    //
    // ExecutorCoroutineDispatcher is held as its concrete type so it can
    // be closed() during destroy() -- otherwise the executor thread leaks.
    private val timerDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val timerScope = CoroutineScope(SupervisorJob() + timerDispatcher)

    // Dispatchers.IO scope for blocking IO work: immediate reconnect
    // transport creation, any other work that may block the thread.
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    /**
     * When false, transport drops do not trigger the internal attemptReconnect
     * loop -- onDisconnected is fired and the external owner (e.g.
     * ConnectionCoordinator) decides whether to retry.
     *
     * Defaults to true so pre-Coordinator callers and wizard test instances
     * keep their original behavior. ConnectionCoordinator sets this to false
     * after construction in Phase 2B+, ending the dueling-timer problem.
     */
    @Volatile
    var selfReconnectEnabled: Boolean = true

    // Controller (group-level) state: supported_commands, group
    // volume/mute, repeat, shuffle. Null until the server first sends a
    // server/state controller object.
    private val _controllerState = MutableStateFlow<ControllerState?>(null)
    val controllerState: StateFlow<ControllerState?> = _controllerState.asStateFlow()

    // Transport abstraction - WebSocket
    private var transport: SendSpinTransport? = null

    // Connection info (stored for reconnection)
    private var serverAddress: String? = null
    private var serverPath: String? = null
    private var serverName: String? = null
    private var serverId: String? = null

    // Client identity - persisted across app launches
    private val clientId = UserSettings.getPlayerId()

    // Time synchronization (Kalman filter)
    private val timeFilter = SendspinTimeFilter().apply {
        // roles/player/v1.md requires output_delay_ms be persisted "across
        // reboots and server reconnections", so restore it here rather than
        // waiting for a server to set it again. Without this the reported value
        // and the applied compensation would both silently reset to 0 on every
        // launch, which is the failure the persistence requirement exists to
        // prevent.
        setOutputDelayMs(UserSettings.getOutputDelayMs().toDouble())
    }

    // Reconnection state
    private val userInitiatedDisconnect = AtomicBoolean(false)

    /**
     * Set when the server unpairs us; blocks automatic reconnection only.
     *
     * Separate from [userInitiatedDisconnect] because the two answer different
     * questions and are reported differently to the UI. Cleared when the user
     * deliberately connects again, which is the only thing that can make
     * reconnecting sensible: the credential the server dropped is gone, so
     * every automatic attempt would just be refused.
     */
    private val suppressAutoReconnect = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null  // Pending reconnect coroutine - cancelled on disconnect

    // Network awareness for smart reconnection
    // When network is unavailable, reconnect attempts are paused (not wasted)
    private val networkAvailable = AtomicBoolean(true)
    private val waitingForNetwork = AtomicBoolean(false)

    // Stall watchdog state. lastByteReceivedAtMs is updated on EVERY text/binary
    // message from the transport. stallWatchdogJob is the polling coroutine.
    // watchdogLock serializes the cancel+reassign dance in startStallWatchdog /
    // stopStallWatchdog so a concurrent start-from-WS-thread + stop-from-main
    // cannot orphan a job or leave the field pointing at a running watchdog
    // that the caller believed was stopped. @Volatile is still needed for any
    // reader that observes the field without taking the lock.
    private val lastByteReceivedAtMs = AtomicLong(System.currentTimeMillis())
    private val watchdogLock = Any()
    @Volatile
    private var stallWatchdogJob: Job? = null

    // True while a server-announced audio stream is active. The stall watchdog
    // only trips while streaming - during idle (no stream) the server may send
    // nothing for long periods, which would cause false-positive stalls.
    private val streamActive = AtomicBoolean(false)

    // -- Connection health telemetry (issue #128). All observational: updated on
    // event paths that already touch state (handshake-complete, onClosed,
    // onFailure, attemptReconnect); read by the stats poll and the structured
    // [disconnect]/[reconnect-ok] log lines. No hot-path cost.
    private val reconnectAttemptsTotal = AtomicInteger(0)
    @Volatile private var connectedAtMs: Long? = null
    @Volatile private var lastDisconnectAtMs: Long? = null
    @Volatile private var lastDisconnectCode: Int? = null
    @Volatile private var lastDisconnectReason: String? = null

    val isConnected: Boolean
        get() = _connectionState.value is TransportState.Ready

    /**
     * Get the number of reconnection attempts since last successful connect.
     */
    fun getReconnectAttempts(): Int = reconnectAttempts.get()

    // -- Connection health accessors (issue #128) --

    /** Milliseconds since the transport last delivered a text or binary frame. */
    fun getLastByteReceivedAgoMs(): Long =
        System.currentTimeMillis() - lastByteReceivedAtMs.get()

    /**
     * True when the stall watchdog would actually evaluate: handshake is
     * complete, the client isn't mid-reconnect, and the user hasn't asked to
     * disconnect. Note: this does NOT check `streamActive` -- the watchdog
     * fires in both streaming (7 s threshold) and idle (20 s threshold) states
     * per #127, so "armed" means "the watchdog is running and will trip if
     * the appropriate silence threshold is exceeded."
     */
    fun isStallWatchdogArmed(): Boolean =
        handshakeComplete && !userInitiatedDisconnect.get() && !reconnecting.get()

    /** Lifetime reconnect attempts (survives across sessions within the process). */
    fun getReconnectAttemptsTotal(): Int = reconnectAttemptsTotal.get()

    /** Most recent close code seen on an abnormal disconnect; null if none. */
    fun getLastDisconnectCode(): Int? = lastDisconnectCode

    /** Most recent close reason / error message. null if no disconnect yet. */
    fun getLastDisconnectReason(): String? = lastDisconnectReason

    /** When the current session handshake completed (null if not connected). */
    fun getConnectedAtMs(): Long? = connectedAtMs

    init {
        // Initialize time sync manager with our time filter
        initTimeSyncManager(timeFilter)
    }

    // ========== SendSpinProtocolHandler Implementation ==========

    // ========== Encrypted handshake (spec) ==========

    private var handshakeDriver: SendSpinHandshakeDriver? = null

    private fun startEncryptedHandshake() {
        // Cleared here rather than on disconnect: every fresh handshake passes
        // through this point, so a stale category from the previous session can
        // never survive into the next one and overstate what it is paired with.
        matchedPsk = null

        // The wire client_id for an encrypted session is the base64url public
        // key, NOT the legacy UUID player id - the two are different
        // identifiers and the server rejects a non-43-character value.
        val identity = UserSettings.getOrCreateClientIdentity()
        val driver = SendSpinHandshakeDriver(
            identity = identity,
            candidates = pskCandidates(),
            onEvent = ::onHandshakeEvent,
        )
        handshakeDriver = driver
        driver.start()
    }

    private fun onHandshakeEvent(event: SendSpinHandshakeDriver.Event) {
        when (event) {
            is SendSpinHandshakeDriver.Event.SendCleartext ->
                // Cleartext handshake frames are the only text frames the spec
                // permits; everything after transport mode is binary.
                sendTextMessage(event.text)

            is SendSpinHandshakeDriver.Event.TransportReady -> {
                Log.i(TAG, "Noise handshake complete with ${event.serverInit.serverId} " +
                    "(psk=${event.matchedPsk.category})")
                // The Sentinel Fallback. The session is unpaired from here on;
                // no record is touched - only a new pairing replaces one.
                event.lookupMiss?.let {
                    Log.w(TAG, "Credential mismatch, continuing unpaired with the Sentinel PSK: $it")
                }
                // Retained rather than logged and dropped: the category decides
                // which activities the server may declare and whether pairing
                // may run. Recomputing it anywhere else would let those two
                // disagree.
                matchedPsk = event.matchedPsk

                // Retained for the in-band re-handshake, which re-sends none of
                // this and has no way to ask for it again.
                sessionFacts = SessionFacts(
                    serverId = event.serverInit.serverId,
                    serverStaticKey = event.serverInit.serverStaticKey,
                    // Carries over unchanged: the re-handshake re-sends no
                    // client/init, so there is no opportunity to renegotiate it.
                    suite = SendSpinHandshakeDriver.DEFAULT_SUITE,
                    priorHandshakeHash = event.transport.handshakeHash,
                )

                // "used" means a server has authenticated a session with this
                // record. Marked on entry to transport mode rather than on the
                // psk_id match, because a match that then failed AEAD
                // authenticated nothing.
                if (event.matchedPsk.category == PskCategory.LONG_TERM) {
                    UserSettings.getOrCreateTrustStore().markUsed(event.matchedPsk.pskId)
                }
                // Nothing is sent yet: client/hello is the reply to the
                // server/hello that arrives over this channel next.
                installEncryptedTransport(event.transport)
            }

            is SendSpinHandshakeDriver.Event.Fail -> {
                // The spec allows no application-level error message here, so
                // this log line is the only diagnostic that will ever exist.
                Log.e(TAG, "Noise handshake failed: ${event.reason} - ${event.detail}")
                handshakeDriver = null
                // A server too old to speak the encrypted handshake is the one
                // failure the user can fix, so it is reported as itself rather
                // than as a generic handshake failure. A server/error is the
                // opposite case - the server does speak it and refused our
                // client/init - so it stays a handshake failure, with its
                // reason in the line above.
                val reason = if (event.reason ==
                    NoiseHandshakeException.Cause.ServerLacksEncryption
                ) {
                    FailureReason.ServerLacksEncryption
                } else {
                    FailureReason.HandshakeFailed
                }
                _connectionState.value = TransportState.Failed(reason)
                transport?.close(1002, "handshake failed")
            }
        }
    }

    /** Read once per connection; the PSK is process-wide and never rotates itself. */
    private val pairingConfigStore = AndroidPairingConfigStore()

    /**
     * The PSK that admitted the current session, or null before the handshake.
     *
     * Single source of truth for everything that follows from how we were
     * authenticated: the `server/activate` admissibility table and whether a
     * pairing activation may proceed.
     */
    @Volatile
    private var matchedPsk: Psk? = null

    /**
     * Everything a re-handshake needs that is NOT re-sent.
     *
     * "`client/init` and `server/init` are not re-sent - `client_id`,
     * `server_id`, and `suite` carry over. The new handshake's prologue is the
     * prior handshake's hash `h`." So the connection has to retain them; there
     * is no second chance to read them off the wire.
     */
    private class SessionFacts(
        val serverId: String,
        val serverStaticKey: ByteArray,
        val suite: NoiseCipherSuite,
        /** Prologue for the NEXT handshake. Advances on every promotion. */
        var priorHandshakeHash: ByteArray,
    )

    @Volatile
    private var sessionFacts: SessionFacts? = null

    /**
     * Every PSK this handshake may match: the stored records, the Sentinel, and
     * the Pairing PSK.
     *
     * Built from stored state alone, with no reference to whether a pairing
     * screen is open, because the server re-handshakes to the Pairing PSK
     * unprompted and that handshake "succeeds only if the client already
     * recognizes its `psk_id`".
     */
    private fun pskCandidates(): PskCandidateSet = PskCandidateSet(
        PskCandidates.build(
            records = UserSettings.getOrCreateTrustStore().listRecords(),
            config = pairingConfigStore.load(),
        )
    )

    override fun isUnpairedAccessEnabled(): Boolean =
        pairingConfigStore.load().unpairedAccessEnabled

    /**
     * The category that admitted this connection, defaulting to the Sentinel
     * before a handshake has matched anything - the least-privileged answer,
     * so a bug here narrows what the server may declare rather than widening it.
     */
    override fun matchedPskCategory(): PskCategory =
        matchedPsk?.category ?: PskCategory.SENTINEL

    /**
     * The live configuration, not a constant. `pairing_psk` is always offered.
     *
     * `dynamic_pairing_code` is opt-in and defaults off: there is no capability
     * signal in `server/hello` to test for it, and advertising it unconditionally
     * broke the handshake against aiosendspin 9.1.1 (see
     * [PairingConfig.dynamicPairingCodeEnabled]).
     */
    override fun offeredPairMethods(): Set<String> = buildSet {
        add(PairMethod.PAIRING_PSK)
        if (pairingConfigStore.load().dynamicPairingCodeEnabled) {
            add(PairMethod.DYNAMIC_PAIRING_CODE)
        }
    }

    /**
     * The `server_id` a pairing record binds to.
     *
     * The exact 43-character string from `server/init`, retained on the
     * session - a record bound to a re-derived or reformatted value would fail
     * the stored-pubkey check on the next connect and look like corruption.
     */
    override fun currentServerId(): String? = sessionFacts?.serverId

    override fun trustStore(): TrustStore = UserSettings.getOrCreateTrustStore()

    override fun onPaired(serverId: String) {
        // The server drives the in-band re-handshake from here (#223); the
        // client sends nothing further. The new record is already visible to
        // pskCandidates(), which reads the store on every call.
        Log.i(TAG, "Pairing complete with $serverId - awaiting the server's re-handshake")
    }

    override fun onAdmissionStateChanged(state: AdmissionState) {
        Log.i(TAG, "Admission state: $state")
        callback.onAdmissionStateChanged(state)
    }

    override fun onDynamicPairingCodeEmitted(code: String) {
        callback.onDynamicPairingCodeEmitted(code)
    }

    override fun onDynamicPairingCodeCleared() {
        callback.onDynamicPairingCodeCleared()
    }

    override fun onDynamicPairingGestureRequested() {
        callback.onDynamicPairingGestureRequested()
    }

    /** The PSK that admitted this session; the re-handshake swaps it. */
    override fun matchedPsk(): Psk? = matchedPsk

    override fun onUnpaired(pskId: String, serverId: String?) {
        Log.i(TAG, "Unpaired by $serverId (psk_id=$pskId)")

        // "Server should not auto-reconnect." On a client-initiated topology
        // that guidance lands on us: reconnecting would just hand the server a
        // credential it has dropped, once per backoff step, forever.
        suppressAutoReconnect.set(true)

        callback?.onUnpaired(serverId)
    }

    /**
     * A protocol-level failure that requires closing the socket.
     *
     * The base class only logs; nothing ever actually closed the transport,
     * even though the spec allows no application-level message for these
     * failures and "close the WebSocket, persist nothing" is a security
     * property of pairing. Closing at the transport level - not
     * `closeConnectionAfterFlush` or a goodbye - is deliberate: those send an
     * application message first, and this path must send nothing at all.
     */
    override fun onProtocolFailure(reason: String) {
        super.onProtocolFailure(reason)
        transport?.close(1002, "protocol failure")
    }

    override fun closeConnectionAfterFlush() {
        // closeAfterFlush, not close: close() cancels the connection job, and
        // the sender coroutine is its child, so a goodbye still sitting in the
        // outgoing channel dies with it.
        transport?.closeAfterFlush(1000, "goodbye")
    }

    /**
     * Run an in-band re-handshake.
     *
     * The server initiates this to promote the channel after a pairing, or to
     * switch a Sentinel-keyed connection to the Pairing PSK before offering
     * `pairing_psk`. The socket stays open throughout: "The server may rerun
     * the Noise handshake in transport mode to swap session keys without
     * closing the WebSocket."
     *
     * Every failure below closes without an application-level message, because
     * the spec allows none - so each one logs its own reason first. That log
     * line is the only artifact anyone will have.
     */
    override fun onRehandshakeMessage(payload: JsonObject?) {
        val facts = sessionFacts ?: return failRehandshake(
            "noise/handshake arrived before any handshake completed"
        )
        Log.i(TAG, "Re-handshake starting (prior h=${hashPrefix(facts.priorHandshakeHash)})")

        // Candidates are rebuilt now rather than reused from connect time: a
        // record persisted moments ago by a pairing must be visible to this
        // very selection, which is why the server started the exchange.
        val driver = RehandshakeDriver(
            identity = UserSettings.getOrCreateClientIdentity(),
            candidates = pskCandidates(),
            serverId = facts.serverId,
            serverStaticKey = facts.serverStaticKey,
            suite = facts.suite,
            priorHandshakeHash = facts.priorHandshakeHash,
        )

        val outcome = driver.handle(payload?.get("data")?.jsonPrimitive?.contentOrNull)
        if (outcome is RehandshakeDriver.Outcome.Fail) return failRehandshake(outcome.reason)
        outcome as RehandshakeDriver.Outcome.Reply

        // Encrypted under the OLD keys, then the swap. The callback runs before
        // that frame goes out, so the session facts are in place by the time
        // the server can answer it.
        sendAndSwapKeys(outcome.replyJson, outcome.transport.asNoiseCrypto()) {
            facts.priorHandshakeHash = outcome.transport.handshakeHash
            matchedPsk = outcome.matched
            if (outcome.matched.category == PskCategory.LONG_TERM) {
                UserSettings.getOrCreateTrustStore().markUsed(outcome.matched.pskId)
            }
            // The channel is promoted, not replaced: transport, streams, roles
            // and time filter all survive. "Neither server/hello nor
            // client/hello is re-sent" - the server's next message is a
            // server/activate, evaluated under the newly matched PSK, and we
            // send nothing until it arrives.
            resetForRehandshake()
            Log.i(
                TAG,
                "Re-handshake complete: psk=${outcome.matched.category} " +
                    "new h=${hashPrefix(outcome.transport.handshakeHash)}"
            )
        }
    }

    /** First 8 hex chars of a handshake hash. Channel-binding data, not a secret. */
    private fun hashPrefix(hash: ByteArray): String =
        hash.take(4).joinToString("") { b ->
            ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }

    private fun failRehandshake(reason: String) {
        Log.e(TAG, "Re-handshake failed: $reason")
        onProtocolFailure(reason)
    }

    override fun getSupportedPairMethods(): List<MessageBuilder.PairMethodDescriptor> = buildList {
        add(MessageBuilder.PairMethodDescriptor.PAIRING_PSK)
        // "An implemented method that is disabled is omitted." Opt-in and off
        // by default (see offeredPairMethods) -- never in place of the
        // (unimplemented) static_pairing_code, which pairing.md forbids
        // combining with it.
        if (pairingConfigStore.load().dynamicPairingCodeEnabled) {
            add(MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE)
        }
    }

    /** Backs the Dynamic Pairing Code flow's brute-force counter (item 3.2's escalation gate). */
    private val dynamicPairingCounterStore = object : PairingCounterStore {
        override fun load(): Int = UserSettings.getPairingCodeFailures()
        override fun save(value: Int) {
            UserSettings.setPairingCodeFailures(value)
        }
    }

    override fun pairingCounterStore(): PairingCounterStore = dynamicPairingCounterStore

    /** The Noise handshake hash for the current session; null before one completes. */
    override fun currentHandshakeHash(): ByteArray? = sessionFacts?.priorHandshakeHash

    /** The negotiated AEAD suite, needed to wrap the dynamic flow's confirm/finalize secrets. */
    override fun negotiatedCipherSuite(): NoiseCipherSuite? = sessionFacts?.suite

    /** Cleartext handshake frames only; everything after them is binary. */
    private fun sendTextMessage(text: String) {
        val t = transport ?: return  // Silently drop if transport is gone (e.g. post-disconnect race)
        val success = t.send(text)
        if (!success) {
            Log.w(TAG, "Failed to send message")
        }
    }

    override fun sendBinaryFrame(bytes: ByteArray) {
        val t = transport ?: return
        if (!t.send(bytes)) {
            Log.w(TAG, "Failed to send binary frame (${bytes.size} bytes)")
        }
    }

    // TimeSyncManager uses this scope for its periodic scheduler loop
    // (delay then send a small time-sync request). That is timer-dominated
    // work, so it belongs on timerScope.
    override fun getCoroutineScope(): CoroutineScope = timerScope

    override fun getTimeFilter(): SendspinTimeFilter = timeFilter

    override fun isLowMemoryMode(): Boolean = UserSettings.lowMemoryMode

    override fun getClientId(): String = clientId

    override fun getDeviceName(): String = deviceName

    override fun getManufacturer(): String = Build.MANUFACTURER ?: "Unknown"

    override fun getSoftwareVersion(): String = com.sendspindroid.BuildConfig.VERSION_NAME

    override fun getSupportedFormats(): List<MessageBuilder.FormatEntry> {
        val bitDepths = if (isLowMemoryMode()) {
            listOf(16)
        } else {
            AudioDecoderFactory.getSupportedPcmBitDepths()
        }
        return MessageBuilder.buildSupportedFormats(
            preferredCodec = UserSettings.getPreferredCodec(),
            isCodecSupported = { AudioDecoderFactory.isCodecSupported(it) },
            supportedBitDepths = bitDepths
        )
    }

    override fun onHandshakeComplete(serverName: String, serverId: String) {
        this.serverName = serverName
        this.serverId = serverId

        // Controller state belongs to the previous session; the handler's
        // copy was reset, so reset the published flow too.
        _controllerState.value = null

        // Check if this is a reconnection
        val wasReconnecting = timeFilter.isFrozen || reconnecting.get()

        if (timeFilter.isFrozen) {
            val thawed = timeFilter.thaw(serverName, serverId)
            if (thawed) {
                Log.i(TAG, "Time filter thawed after reconnection - re-syncing with increased covariance")
            } else {
                timeFilter.resetAndDiscard()
                resetSyncStateTracking()
                Log.i(TAG, "Server identity changed during reconnect; discarded frozen sync state")
            }
        }

        evaluateAndPublishSyncState()

        // Capture telemetry for the structured [reconnect-ok] line before resetting
        // current-cycle counters. Issue #128.
        val attemptsThisCycle = reconnectAttempts.get()
        val disconnectAtMs = lastDisconnectAtMs

        reconnecting.set(false)
        reconnectAttempts.set(0)
        waitingForNetwork.set(false)
        _connectionState.value = TransportState.Ready

        // Mark session start for uptime calculation and clear the disconnect marker.
        // Issue #128.
        connectedAtMs = System.currentTimeMillis()
        lastDisconnectAtMs = null

        if (wasReconnecting) {
            // Emit a structured recovery log line so shared on-device logs show
            // end-to-end reconnect outcomes (disconnect -> handshake complete).
            // Issue #128.
            if (disconnectAtMs != null) {
                val tookSeconds = (System.currentTimeMillis() - disconnectAtMs) / 1000.0
                AppLog.Network.i(
                    "[reconnect-ok] took_s=%.1f attempts_this_cycle=%d attempts_total=%d".format(
                        tookSeconds,
                        attemptsThisCycle,
                        reconnectAttemptsTotal.get(),
                    )
                )
            }
            Log.i(TAG, "Reconnection successful")
        }

        streamActive.set(false)  // fresh handshake - wait for server to announce stream state
        startStallWatchdog()  // (re)start watchdog now that we have a live handshake-complete session
    }

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        // Per spec, extrapolate the reported position from the metadata's
        // server timestamp to "now" before publishing. Without this, the
        // position is stale by network latency plus however long the
        // snapshot sat on the server (and downstream anchors interpolate
        // from receive time). Requires a converged clock; fall back to the
        // raw value until then.
        val positionMs = if (timeFilter.isReady) {
            metadata.progressAtServerTime(timeFilter.clientToServer(System.nanoTime() / 1000))
        } else {
            metadata.positionMs
        }
        // Null means the server has no value for the field - either it was
        // never sent or it was explicitly cleared. The callback's empty string
        // carries that meaning downstream.
        callback.onMetadataUpdate(
            metadata.title.orEmpty(),
            metadata.artist.orEmpty(),
            metadata.album.orEmpty(),
            metadata.artworkUrl.orEmpty(),
            metadata.durationMs,
            positionMs,
            metadata.progress?.playbackSpeed ?: 1000
        )
    }

    override fun onPlaybackStateChanged(state: String) {
        callback.onStateChanged(state)
    }

    override fun onVolumeCommand(volume: Int) {
        callback.onVolumeChanged(volume)
    }

    override fun onMuteCommand(muted: Boolean) {
        callback.onMutedChanged(muted)
    }

    override fun onGroupUpdate(info: GroupInfo) {
        callback.onGroupUpdate(info.groupId, info.groupName, info.playbackState)
    }

    override fun onStreamStart(config: StreamConfig) {
        streamActive.set(true)
        // Reset so we don't false-trip from any stale timestamp accumulated while
        // the stream was inactive (we were not expecting data then).
        lastByteReceivedAtMs.set(System.currentTimeMillis())

        val preferredCodec = UserSettings.getPreferredCodec()
        Log.i(TAG, "Stream started: server chose codec=${config.codec} (we preferred=$preferredCodec)")
        callback.onStreamStart(
            config.codec,
            config.sampleRate,
            config.channels,
            config.bitDepth,
            config.codecHeader
        )
    }

    override fun onStreamClear() {
        streamActive.set(false)
        callback.onStreamClear()
    }

    override fun onStreamEnd() {
        streamActive.set(false)
        callback.onStreamEnd()
    }

    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) {
        callback.onAudioChunk(timestampMicros, audioData)
    }

    override fun onArtwork(channel: Int, payload: ByteArray) {
        if (payload.isEmpty()) {
            callback.onArtworkCleared()
        } else {
            callback.onArtwork(payload)
        }
    }

    override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
        callback.onSyncOffsetApplied(offsetMs, source)
    }

    override fun onSyncMuteChanged(muted: Boolean) {
        callback.onSyncMuteChanged(muted)
    }

    override fun onControllerStateUpdate(state: ControllerState) {
        _controllerState.value = state
    }

    // ========== Public API ==========

    /**
     * Get the connected server's name.
     */
    fun getServerName(): String? = serverName

    /**
     * Get the connected server's address.
     */
    fun getServerAddress(): String? = serverAddress

    /**
     * Get milliseconds since the last time sync measurement.
     */
    fun getLastTimeSyncAgeMs(): Long {
        val lastUpdate = timeFilter.lastUpdateTimeUs
        if (lastUpdate <= 0) return -1
        val nowUs = System.nanoTime() / 1000
        return (nowUs - lastUpdate) / 1000
    }

    /**
     * Called when the network changes.
     * During reconnection, we preserve the frozen sync state to maintain playback continuity.
     */
    fun onNetworkChanged() {
        if (!isConnected) return

        // If we're actively reconnecting, preserve the frozen sync state
        // This allows playback to continue from buffer without losing clock sync
        if (reconnecting.get() || timeFilter.isFrozen) {
            Log.i(TAG, "Network changed during reconnection - preserving frozen sync state")
            return
        }

        Log.i(TAG, "Network changed - resetting time filter for re-sync")
        timeFilter.reset()
        callback.onNetworkChanged()
    }

    /**
     * Called when network becomes available.
     * If we're actively reconnecting, cancel any pending backoff and immediately retry.
     * This minimizes buffer exhaustion by reconnecting as fast as possible.
     */
    fun onNetworkAvailable() {
        if (!reconnecting.get()) return

        Log.i(TAG, "Network available during reconnection - attempting immediate reconnect")

        // Cancel any pending backoff delay
        reconnectJob?.cancel()
        reconnectJob = null

        // Reset backoff counter for faster retry if this fails too
        // (Keep it at least 1 so we don't re-freeze the time filter)
        reconnectAttempts.set(1)

        // Immediately try to reconnect
        workScope.launch {
            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled before immediate retry")
                return@launch
            }

            handshakeComplete = false
            stopTimeSync()

            val savedAddress = serverAddress ?: return@launch
            val savedPath = serverPath ?: return@launch
            Log.d(TAG, "Immediate reconnecting to: $savedAddress path=$savedPath")
            createLocalTransport(savedAddress, savedPath)
        }
    }

    /**
     * Called by PlaybackService when network availability changes.
     * When network is lost during reconnection, pauses attempts without wasting them.
     * When network returns, resumes immediately via onNetworkAvailable().
     */
    fun setNetworkAvailable(available: Boolean) {
        networkAvailable.set(available)
        if (available && waitingForNetwork.getAndSet(false)) {
            Log.i(TAG, "Network restored - resuming paused reconnection")
            onNetworkAvailable()
        }
    }

    /**
     * Connect to a SendSpin server on the local network.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connect(address: String, path: String = SendSpinProtocol.ENDPOINT_PATH) {
        connectLocal(address, path)
    }

    /**
     * Connect to the given endpoint. Single entry point that replaces the
     * explicit connectLocal method. Phase 4 introduces this facade; the
     * underlying method stays for now.
     */
    fun connect(endpoint: SendSpinEndpoint) {
        when (endpoint) {
            is SendSpinEndpoint.Local -> connectLocal(endpoint.address, endpoint.path)
        }
    }

    /**
     * Connect to a SendSpin server on the local network.
     *
     * @param address Server address in "host:port" format
     * @param path WebSocket path (from mDNS TXT or default /sendspin)
     */
    fun connectLocal(address: String, path: String = SendSpinProtocol.ENDPOINT_PATH) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        val normalizedPath = normalizePath(path)

        Log.d(TAG, "Connecting locally to: $address path=$normalizedPath")
        prepareForConnection()

        serverAddress = address
        serverPath = normalizedPath

        createLocalTransport(address, normalizedPath)
    }

    /**
     * Common preparation before establishing a connection.
     */
    private fun prepareForConnection() {
        _connectionState.value = TransportState.Connecting
        handshakeComplete = false
        timeFilter.reset()
        resetSyncStateTracking()

        // Cancel any pending reconnect from previous connection attempt
        reconnectJob?.cancel()
        reconnectJob = null

        userInitiatedDisconnect.set(false)
        suppressAutoReconnect.set(false)
        reconnectAttempts.set(0)
        reconnecting.set(false)
        waitingForNetwork.set(false)

        // Clean up any existing transport.
        // Clear the listener first to prevent stale callbacks (e.g., onOpen from
        // a previous OkHttp WebSocket) from firing on the new transport's listener.
        transport?.setListener(null)
        transport?.destroy()
        transport = null
    }

    /**
     * Get the WebSocket ping interval based on High Power Mode setting.
     * High Power Mode uses 15s for faster drop detection, normal uses 30s.
     */
    private fun getPingIntervalSeconds(): Long =
        if (UserSettings.highPowerMode) 15L else 30L

    /**
     * Create and connect a local WebSocket transport.
     */
    private fun createLocalTransport(address: String, path: String) {
        val wsTransport = WebSocketTransport(address, path, pingIntervalSeconds = getPingIntervalSeconds())
        transport = wsTransport
        wsTransport.setListener(TransportEventListener())
        wsTransport.connect()
    }

    /**
     * Disconnect from the current server for reasons that should trigger an
     * upward auto-reconnect, such as the underlying network transport type
     * changing (WiFi -> Cellular). Unlike [disconnect], this does NOT set
     * [userInitiatedDisconnect]. Fires `onDisconnected(wasUserInitiated=false,
     * wasReconnectExhausted=false)`, which MainActivity's STATE_DISCONNECTED
     * handler interprets as "start AutoReconnectManager" -- the outer reconnect
     * loop re-runs `ConnectionSelector` fresh and reconnects for whatever
     * network we are on now.
     *
     * The reason for existing: [disconnect] is a user action (tap 'Switch Server'
     * etc.) and explicitly suppresses auto-reconnect. We want the opposite here:
     * the user did nothing wrong, the network changed out from under us, and the
     * inner reconnect loop would otherwise keep retrying on the network that just
     * went away. Yield cleanly and let the outer loop reconnect on the new network.
     */
    fun disconnectForReselection() {
        stopStallWatchdog()
        Log.i(TAG, "Disconnecting for reselection (transport-type change)")

        // Cancel any pending reconnect coroutine to prevent races
        reconnectJob?.cancel()
        reconnectJob = null

        stopTimeSync()
        resetArtworkStream()
        reconnecting.set(false)
        waitingForNetwork.set(false)
        // Spec reason enum is another_server | shutdown | restart |
        // user_request. "restart" fits: we will reconnect (after the outer
        // loop re-selects the transport) and the server should auto-reconnect.
        sendGoodbye(GoodbyeReason.RESTART)
        // Clear the transport listener BEFORE closing to prevent the async onClosed
        // callback from firing a second onDisconnected after we fire one synchronously below.
        transport?.setListener(null)
        transport?.close(1000, "Reselection")
        transport = null
        handshakeComplete = false
        _connectionState.value = TransportState.Idle
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        stopStallWatchdog()
        Log.d(TAG, "Disconnecting (user-initiated)")
        userInitiatedDisconnect.set(true)

        // Cancel any pending reconnect coroutine to prevent race condition
        reconnectJob?.cancel()
        reconnectJob = null

        stopTimeSync()
        resetArtworkStream()
        reconnecting.set(false)
        waitingForNetwork.set(false)
        sendGoodbye(GoodbyeReason.USER_REQUEST)
        // Clear the transport listener BEFORE closing to prevent the async onClosed
        // callback from firing a second onDisconnected after we fire one synchronously below.
        transport?.setListener(null)
        transport?.close(1000, "User disconnect")
        transport = null
        handshakeComplete = false
        _connectionState.value = TransportState.Idle
    }

    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun stop() = sendCommand("stop")
    fun next() = sendCommand("next")
    fun previous() = sendCommand("previous")
    fun switchGroup() = sendCommand("switch")

    /** Set the volume of the whole group (0-100). */
    fun setGroupVolume(volume: Int) = sendCommand("volume", volume = volume)

    /** Set the mute state of the whole group. */
    fun setGroupMute(muted: Boolean) = sendCommand("mute", mute = muted)

    /** Set repeat mode: "off", "one", or "all". */
    fun setRepeatMode(mode: String) {
        when (mode) {
            "off" -> sendCommand("repeat_off")
            "one" -> sendCommand("repeat_one")
            "all" -> sendCommand("repeat_all")
            else -> Log.w(TAG, "Unknown repeat mode: $mode")
        }
    }

    /** Enable or disable shuffle. */
    fun setShuffle(enabled: Boolean) = sendCommand(if (enabled) "shuffle" else "unshuffle")

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopStallWatchdog()
        stopTimeSync()

        // Cancel any pending reconnect coroutine
        reconnectJob?.cancel()
        reconnectJob = null

        reconnecting.set(false)
        // disconnect() sets userInitiatedDisconnect unconditionally; no need
        // to pre-set it here.
        disconnect()

        // Cancel both scopes before closing the timer dispatcher.
        // Cancelling the scope cancels all its launched coroutines; closing
        // the dispatcher shuts down the underlying executor thread.
        timerScope.cancel()
        workScope.cancel()
        timerDispatcher.close()
    }

    // ========== Private Methods ==========

    /**
     * Normalize and validate the WebSocket path parameter.
     */
    private fun normalizePath(path: String): String {
        if (path.isEmpty()) {
            Log.d(TAG, "Empty path provided, using default: ${SendSpinProtocol.ENDPOINT_PATH}")
            return SendSpinProtocol.ENDPOINT_PATH
        }

        val pathWithoutQuery = path.substringBefore("?")
        if (pathWithoutQuery != path) {
            Log.d(TAG, "Removed query string from path: '$path' -> '$pathWithoutQuery'")
        }

        if (pathWithoutQuery.isEmpty()) {
            Log.d(TAG, "Path empty after removing query string, using default: ${SendSpinProtocol.ENDPOINT_PATH}")
            return SendSpinProtocol.ENDPOINT_PATH
        }

        val normalizedPath = if (!pathWithoutQuery.startsWith("/")) {
            Log.d(TAG, "Path missing leading slash, prepending: '/$pathWithoutQuery'")
            "/$pathWithoutQuery"
        } else {
            pathWithoutQuery
        }

        return normalizedPath
    }

    /**
     * Start the stall watchdog. Called when the connection reaches a state where
     * we expect data to be flowing. Cancels any previous instance.
     *
     * Serialized against [stopStallWatchdog] via [watchdogLock]: the cancel +
     * reassign pair must be atomic so a concurrent stop() cannot null the
     * field after this method launches a new job (which would leave the new
     * job orphaned and running).
     */
    private fun startStallWatchdog() {
        synchronized(watchdogLock) {
            stallWatchdogJob?.cancel()
            // Reset so we don't false-trip using a stale pre-handshake timestamp
            lastByteReceivedAtMs.set(System.currentTimeMillis())
            stallWatchdogJob = timerScope.launch {
                while (true) {
                    delay(STALL_CHECK_INTERVAL_MS)
                    checkStall()
                }
            }
        }
    }

    /**
     * Stop the stall watchdog. Called on disconnect or during reconnect attempts.
     * Serialized against [startStallWatchdog] via [watchdogLock].
     */
    private fun stopStallWatchdog() {
        synchronized(watchdogLock) {
            stallWatchdogJob?.cancel()
            stallWatchdogJob = null
        }
    }

    /**
     * Check whether the transport has gone silent for too long and force-close it
     * if so. Only acts when the client is connected, handshake is complete, and we
     * are not already in a reconnect cycle.
     *
     * Uses a two-tier threshold: [STALL_TIMEOUT_MS] while a stream is active (audio
     * frames should arrive continuously), and [IDLE_STALL_TIMEOUT_MS] when idle
     * (only regular traffic is server/time responses to our TimeSyncManager bursts).
     * The idle threshold catches server death during kiosk/dashboard-style
     * deployments where music is rarely flowing -- without it, detection relied on
     * OkHttp's 30s ping timeout alone which consumes the entire audio buffer.
     * Issue #127.
     *
     * Private for production; reached via reflection from SendSpinClientStallWatchdogTest.
     */
    private fun checkStall() {
        if (userInitiatedDisconnect.get()) return
        if (reconnecting.get()) return
        if (!handshakeComplete) return
        val t = transport ?: return
        if (!t.isConnected) return

        val streaming = streamActive.get()
        val threshold = if (streaming) STALL_TIMEOUT_MS else IDLE_STALL_TIMEOUT_MS
        val sinceLastByte = System.currentTimeMillis() - lastByteReceivedAtMs.get()
        if (sinceLastByte > threshold) {
            val mode = if (streaming) "streaming" else "idle"
            Log.w(TAG, "Stall watchdog: no data received in ${sinceLastByte}ms ($mode threshold ${threshold}ms) - forcing transport close")
            // 1001 "Going Away" is non-1000 so onClosed path triggers reconnection
            t.close(1001, "stall watchdog ($mode)")
        }
    }

    /**
     * Record disconnect state and emit a structured `[disconnect]` log line
     * consumable by anyone reading the on-device log file shared via Settings.
     *
     * Invariants (issue #128):
     *   * Fires on every disconnect path -- normal, abnormal, pre-handshake, or
     *     user-initiated. The stats screen's "last disconnect" is informational
     *     regardless of whether reconnect is scheduled.
     *   * Uptime is derived from [connectedAtMs] (null -> "preconnect" when
     *     handshake had not yet completed).
     *   * Logs at INFO so `AppLog.level = WARN or above` suppresses emission
     *     without any formatting cost.
     */
    private fun recordDisconnectTelemetry(
        code: Int?,
        reasonText: String,
        isNormalClosure: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val connectedAt = connectedAtMs

        // Persist for the stats screen. Keep the "abnormal" flag for Part B's
        // reconnect-result correlation: lastDisconnectAtMs only gates the
        // [reconnect-ok] emission on a subsequent handshake complete, not the
        // user-facing stats, so only set it on abnormal closures that will
        // actually trigger a retry cycle.
        lastDisconnectCode = code
        lastDisconnectReason = reasonText
        if (!isNormalClosure && !userInitiatedDisconnect.get()) {
            lastDisconnectAtMs = now
        }

        // Session ended for uptime purposes regardless of whether we reconnect.
        connectedAtMs = null

        val codeField = code?.toString() ?: "none"
        val uptimeField = if (connectedAt != null) {
            "%.1f".format((now - connectedAt) / 1000.0)
        } else {
            "preconnect"
        }
        AppLog.Network.i(
            "[disconnect] code=$codeField reason=${reasonText.ifBlank { "unknown" }} " +
                "uptime_s=$uptimeField " +
                "attempts_total=${reconnectAttemptsTotal.get()}"
        )
    }

    /**
     * Attempt reconnection with exponential backoff.
     *
     * Exponential backoff for the first 5 attempts (500ms -> 8s), then 30s
     * steady-state retries forever. Applies in both normal and high-power mode.
     * If network is unavailable, pauses without consuming an attempt.
     */
    private fun attemptReconnect() {
        if (serverAddress == null) {
            Log.w(TAG, "Cannot reconnect: no connection info saved")
            return
        }

        if (userInitiatedDisconnect.get()) {
            Log.d(TAG, "Not reconnecting: user-initiated disconnect")
            return
        }

        if (suppressAutoReconnect.get()) {
            Log.i(TAG, "Not reconnecting: this server unpaired us")
            return
        }

        // Hard cap (L-5 fix): after MAX_TOTAL_RECONNECT_ATTEMPTS, stop scheduling
        // attempts and surface failure to the UI. The user can manually reconnect
        // (which clears reconnectAttempts and restarts the cycle).
        val prior = reconnectAttempts.get()
        if (prior >= MAX_TOTAL_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Reconnect cap reached ($prior >= $MAX_TOTAL_RECONNECT_ATTEMPTS) - giving up")
            AppLog.Network.w(
                "[reconnect-exhausted] cap=$MAX_TOTAL_RECONNECT_ATTEMPTS " +
                    "attempts_total=${reconnectAttemptsTotal.get()}"
            )
            reconnecting.set(false)
            reconnectJob?.cancel()
            reconnectJob = null
            _connectionState.value = TransportState.Failed(FailureReason.Exhausted)
            return
        }

        val attempts = reconnectAttempts.incrementAndGet()
        // Lifetime counter survives across reconnect cycles. Issue #128.
        reconnectAttemptsTotal.incrementAndGet()

        // On first reconnection attempt, freeze the time filter so a
        // successful reconnect to the same server can restore sync.
        if (attempts == 1) {
            timeFilter.freeze(serverName, serverId)
            Log.i(TAG, "Time filter frozen for reconnection (had ${timeFilter.measurementCountValue} measurements)")
        }
        stopStallWatchdog()  // watchdog restarts on next successful handshake via onHandshakeComplete

        // If network is unavailable, pause without wasting an attempt
        // setNetworkAvailable(true) will resume via onNetworkAvailable()
        if (!networkAvailable.get()) {
            Log.i(TAG, "Network unavailable - pausing reconnection (attempt $attempts saved)")
            reconnectAttempts.decrementAndGet()
            waitingForNetwork.set(true)
            reconnecting.set(true)
            _connectionState.value = TransportState.Connecting
            return
        }

        // Exponential backoff for first 5 attempts, then 30s steady-state forever.
        // Applies in both normal and high power mode - the user can always disconnect
        // manually if they're done listening.
        val delayMs = if (attempts > MAX_RECONNECT_ATTEMPTS) {
            HIGH_POWER_RECONNECT_DELAY_MS
        } else {
            (INITIAL_RECONNECT_DELAY_MS * (1 shl (attempts - 1)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }

        Log.i(TAG, "Attempting reconnection $attempts in ${delayMs}ms")
        reconnecting.set(true)
        _connectionState.value = TransportState.Connecting

        // Store the job so it can be cancelled if user disconnects during the delay
        reconnectJob = timerScope.launch {
            delay(delayMs)

            if (userInitiatedDisconnect.get() || !reconnecting.get()) {
                Log.d(TAG, "Reconnection cancelled")
                return@launch
            }

            handshakeComplete = false
            stopTimeSync()

            // Clean up old transport
            transport?.destroy()
            transport = null

            // Transport creation does blocking IO -- switch dispatcher
            // from the single-thread timer to the IO pool.
            withContext(Dispatchers.IO) {
                val address = serverAddress ?: return@withContext
                val path = serverPath ?: SendSpinProtocol.ENDPOINT_PATH
                Log.d(TAG, "Reconnecting to: $address path=$path (attempt $attempts)")
                createLocalTransport(address, path)
            }
        }
    }

    /**
     * Check if an error is recoverable (should trigger reconnection).
     */
    private fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""

        return when {
            cause is SocketException -> true
            cause is java.io.EOFException -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true
            cause is SocketTimeoutException -> true
            cause is UnknownHostException -> false
            cause is SSLHandshakeException -> false
            message.contains("refused") -> false
            else -> {
                // Default to NOT recoverable. A leaked programming bug (NPE, parser
                // RuntimeException, etc.) must not trigger endless reconnect loops.
                Log.d(TAG, "isRecoverableError: unrecognized throwable ${cause::class.simpleName} msg='$message' -> unrecoverable")
                false
            }
        }
    }

    /**
     * Maps a throwable / close-code / response-code triple to a FailureReason.
     *
     * Conservative classifier:
     * - AuthRejected: only on 401/403 from a fully-handshaked transport.
     * - HandshakeFailed: SSL/DNS errors, "connection refused".
     * - TransientNetwork: everything else (network flakes, timeouts, generic IO).
     *
     * Phase 5 (the WiFi->Cell login fix) depends on AuthRejected being
     * correctly identified -- a stored MA token is cleared only when this
     * classifier returns AuthRejected.
     */
    private fun classifyFailureReason(
        throwable: Throwable? = null,
        closeCode: Int? = null,
        responseCode: Int? = null,
    ): FailureReason {
        if (responseCode == 401 || responseCode == 403) {
            return FailureReason.AuthRejected
        }
        if (throwable is javax.net.ssl.SSLException ||
            throwable is java.net.UnknownHostException ||
            throwable?.message?.contains("refused", ignoreCase = true) == true) {
            return FailureReason.HandshakeFailed
        }
        return FailureReason.TransientNetwork
    }

    // ========== Transport Event Listener ==========

    /**
     * Unified event listener for the WebSocket transport.
     *
     * ## Reconnect-gate policy (issue #129)
     *
     * Both [onClosed] and [onFailure] trigger [attemptReconnect] under the same
     * core conditions:
     *   * Not a user-initiated disconnect (`!userInitiatedDisconnect`).
     *   * Have connection info saved (address).
     *   * The failure is transient / unexpected (non-1000 close code for
     *     [onClosed]; `isRecoverable` exception class for [onFailure]).
     *
     * Notably, `handshakeComplete` is NOT part of the gate. A server that
     * accepts the WebSocket upgrade and then closes abnormally before
     * `server/hello` arrives is retried -- backoff with 30 s steady-state
     * after 5 attempts handles the "server is broken" case without spinning,
     * and the existing `!isNormalClosure` / `isRecoverable` filters prevent
     * reconnect storms on deterministic rejections (code 1000 from an
     * accept-then-reject server, DNS, SSL, auth failures).
     */
    private inner class TransportEventListener : SendSpinTransport.Listener {

        override fun onConnected() {
            Log.d(TAG, "Transport connected")

            // Spec order: client/init is the FIRST frame on the socket.
            // client/hello moves after the Noise handshake and travels
            // encrypted like every other application message.
            //
            // There is no unencrypted branch here. Encryption has been
            // mandatory since spec #84 (2026-06-29), and a fallback would be
            // a second wire format that only ever runs when the first one
            // breaks - the least tested path reached exactly when things are
            // already going wrong.
            Log.d(TAG, "Starting encrypted handshake")
            startEncryptedHandshake()
        }

        override fun onMessage(text: String, rawUtf8: ByteArray) {
            // The Noise prologue is built from the EXACT bytes of server/init as
            // received, so the driver gets rawUtf8 and never a re-encoding.
            val driver = handshakeDriver
            if (driver != null && driver.phase != SendSpinHandshakeDriver.Phase.Transport) {
                lastByteReceivedAtMs.set(System.currentTimeMillis())
                driver.onCleartextFrame(rawUtf8)
                return
            }
            // "a cleartext message received after switching to transport mode
            // is a silent failure: the detecting side closes the connection".
            // A text frame out here was never decrypted, so dispatching it
            // would let anyone on the path inject server/command, server/unpair
            // or stream/* as if the server had sent them.
            onProtocolFailure("cleartext text frame outside the handshake")
        }

        override fun onMessage(text: String) {
            lastByteReceivedAtMs.set(System.currentTimeMillis())
            handleTextMessage(text)
        }

        override fun onMessage(bytes: ByteArray) {
            lastByteReceivedAtMs.set(System.currentTimeMillis())
            handleBinaryMessage(bytes)
        }

        override fun onClosing(code: Int, reason: String) {
            Log.d(TAG, "Transport closing: $code $reason")
        }

        override fun onClosed(code: Int, reason: String) {
            Log.d(TAG, "Transport closed: $code $reason")

            // Code 1000 = Normal Closure - server intentionally ended the session
            // This is NOT an error that should trigger reconnection
            val isNormalClosure = code == 1000

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. Issue #128. Safe for all paths (normal, abnormal,
            // user-initiated): the stats screen shows "last disconnect" which is
            // informational regardless of whether we reconnect.
            recordDisconnectTelemetry(
                code = code,
                reasonText = reason.ifEmpty { "code=$code" },
                isNormalClosure = isNormalClosure,
            )

            val hasConnectionInfo = serverAddress != null

            if (!userInitiatedDisconnect.get() && !isNormalClosure && hasConnectionInfo) {
                // Abnormal closure (not code 1000) - attempt reconnection. We no
                // longer gate on handshakeComplete here; see class-level doc for
                // the unified reconnect-gate policy (#129). Logging keeps the
                // handshake state so field triage can still distinguish
                // "pre-handshake drop" from "post-handshake drop".
                Log.i(TAG, "Abnormal closure (code=$code, handshakeComplete=$handshakeComplete), attempting reconnection")
                if (selfReconnectEnabled) {
                    attemptReconnect()
                } else {
                    Log.d(TAG, "selfReconnectEnabled=false; not auto-reconnecting after onClosed(code=$code)")
                    reconnecting.set(false)
                    _connectionState.value = TransportState.Idle
                }
            } else {
                // Either user-initiated, pre-handshake, or server's normal closure
                if (isNormalClosure && !userInitiatedDisconnect.get()) {
                    Log.i(TAG, "Server closed connection normally (code 1000) - session ended")
                }
                reconnecting.set(false)
                _connectionState.value = TransportState.Idle
            }
        }

        override fun onFailure(error: Throwable, isRecoverable: Boolean) {
            Log.e(TAG, "Transport failure", error)

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. onFailure has no WebSocket close code -- use `null` code and
            // the error class/message as the reason. Issue #128.
            recordDisconnectTelemetry(
                code = null,
                reasonText = error.message ?: error::class.java.simpleName,
                isNormalClosure = false,
            )

            val hasConnectionInfo = serverAddress != null

            val shouldReconnect = !userInitiatedDisconnect.get() &&
                    hasConnectionInfo &&
                    isRecoverable

            if (shouldReconnect) {
                Log.i(TAG, "Recoverable error, attempting reconnection: ${error.message}")
                if (selfReconnectEnabled) {
                    attemptReconnect()
                } else {
                    Log.d(TAG, "selfReconnectEnabled=false; not auto-reconnecting after onFailure(${error.message})")
                    reconnecting.set(false)
                    _connectionState.value = TransportState.Idle
                }
            } else {
                reconnecting.set(false)
                _connectionState.value = TransportState.Failed(classifyFailureReason(throwable = error))
            }
        }
    }
}
