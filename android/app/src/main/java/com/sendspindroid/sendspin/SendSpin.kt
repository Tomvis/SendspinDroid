package com.sendspindroid.sendspin

import android.os.Build
import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.throwableSummary
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
import java.util.concurrent.Executors
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.pairing.PairMethod
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

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
 * - Connection state machine (Idle/Connecting/Ready/Failed)
 *
 * It does not retry. Every connection ends in [endConnection], which reports
 * it through [connectionState] and [Callback.onDisconnected]; whoever owns
 * this client decides what happens next.
 */
class SendSpin(
    private val deviceName: String,
    private val callback: Callback
) : SendSpinProtocolHandler(TAG) {

    companion object {
        private const val TAG = "SendSpin"

        // Stall watchdog: while connected+handshake-complete, if no bytes arrive for
        // this long, force-close the transport so the drop is noticed. Shorter than
        // Ktor's 30s ping-timeout.
        private const val STALL_TIMEOUT_MS = 7_000L
        private const val STALL_CHECK_INTERVAL_MS = 3_000L

        // Idle-mode stall threshold. Larger than the streaming threshold because during
        // idle the only regular server->client traffic is server/time responses to our
        // TimeSyncManager bursts. Burst cadence is 500ms-3s once converged, so ~9s is
        // the worst-case natural silence; 20s gives 2x headroom while still catching
        // server death. Issue #127.
        private const val IDLE_STALL_TIMEOUT_MS = 20_000L
    }

    /**
     * Callback interface for SendSpin events.
     */
    interface Callback {
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        // Fork: the whole object, so the now-playing UI gets album artist,
        // year and queue position. Each server/state carries the role's full
        // state, so a null field means the track has none.
        fun onMetadataUpdate(metadata: TrackMetadata)
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
         * Fork: group-level controller state (volume/mute/repeat/shuffle and
         * supported commands) for the TV now-playing controls. Also published
         * on [SendSpin.controllerState].
         */
        fun onControllerStateUpdate(state: ControllerState) {}

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

        /**
         * A connection that had got as far as `server/hello` has ended.
         *
         * @param reconnect true when nobody asked for it to end (a drop, a
         *   stall, a protocol failure, the server restarting, the network
         *   changing under it), so the owner should connect again. False
         *   when this client chose to leave: a user disconnect, a switch to
         *   another server, `server/unpair`, a rejected activation.
         */
        fun onDisconnected(reconnect: Boolean) {}
    }

    // Dedicated single-thread dispatcher for timer-dominated work: stall
    // watchdog polling, TimeSyncManager's periodic scheduler. Isolating this
    // from Dispatchers.IO means timer latency is bounded by a single thread's
    // scheduling, not by shared pool contention with blocking IO work.
    //
    // ExecutorCoroutineDispatcher is held as its concrete type so it can
    // be closed() during destroy() -- otherwise the executor thread leaks.
    private val timerDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val timerScope = CoroutineScope(SupervisorJob() + timerDispatcher)

    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Idle)
    val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    // Controller (group-level) state: supported_commands, group
    // volume/mute, repeat, shuffle. Null until the server first sends a
    // server/state controller object.
    private val _controllerState = MutableStateFlow<ControllerState?>(null)
    val controllerState: StateFlow<ControllerState?> = _controllerState.asStateFlow()

    // Transport abstraction - WebSocket. Assigned and cleared under
    // connectionLock; read from the timer thread.
    @Volatile
    private var transport: SendSpinTransport? = null
    private val connectionLock = Any()

    // Connection info
    private var serverAddress: String? = null
    private var serverPath: String? = null
    private var serverName: String? = null

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

    // -- Connection health telemetry (issue #128). All observational: updated on
    // event paths that already touch state (handshake-complete, onClosed,
    // onFailure); read by the stats poll and the structured [disconnect] log
    // line. No hot-path cost.
    @Volatile private var connectedAtMs: Long? = null
    @Volatile private var lastDisconnectCode: Int? = null
    @Volatile private var lastDisconnectReason: String? = null

    val isConnected: Boolean
        get() = _connectionState.value is TransportState.Ready

    // -- Connection health accessors (issue #128) --

    /** Milliseconds since the transport last delivered a text or binary frame. */
    fun getLastByteReceivedAgoMs(): Long =
        System.currentTimeMillis() - lastByteReceivedAtMs.get()

    /**
     * True when the stall watchdog would actually evaluate: the handshake is
     * complete. Whether a stream is active only picks the threshold (7 s
     * streaming, 20 s idle, per #127), so "armed" means "the watchdog is
     * running and will trip if the appropriate silence threshold is exceeded."
     */
    fun isStallWatchdogArmed(): Boolean = handshakeComplete

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
    private var handshakeTimeoutJob: Job? = null

    /** Test seam for the handshake-phase timeout. */
    internal var handshakeTimeoutMs = SendSpinProtocol.HANDSHAKE_TIMEOUT_MS

    private fun startEncryptedHandshake() {
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

        // "Implementations SHOULD apply a timeout (e.g., 30 seconds) for each
        // side to receive the next expected message during the prologue and
        // Noise-handshake phases." One window for the whole exchange, which
        // bounds each of its two messages. It only acts on the socket this
        // handshake started on: a stale timer must not fail a later
        // connection, or one the user has already left.
        val socket = transport
        handshakeTimeoutJob = timerScope.launch {
            delay(handshakeTimeoutMs)
            if (transport === socket && socket?.isConnected == true) {
                synchronized(driver) { driver.onTimeout() }
            }
        }
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
                endConnection(TransportState.Failed(reason), reconnect = true)?.let {
                    it.close(1002, "handshake failed")
                    it.destroy()
                }
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
        callback.onUnpaired(serverId)
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
     *
     * The transport reports its own close, so the connection ends in
     * [TransportEventListener.onClosed] like any other drop.
     */
    override fun onProtocolFailure(reason: String) {
        super.onProtocolFailure(reason)
        transport?.close(1002, "protocol failure")
    }

    /**
     * Reached only after this client has said why it is leaving: the goodbye
     * for `server/unpair` or a rejected activation, or a `pair/abort` whose
     * reason closes the connection. So the connection ends here as one we
     * left, not as a drop to recover from.
     */
    override fun closeConnectionAfterFlush() {
        // closeAfterFlush, not close: close() cancels the connection job, and
        // the sender coroutine is its child, so a goodbye still sitting in the
        // outgoing channel dies with it.
        endConnection(TransportState.Idle, reconnect = false)?.closeAfterFlush(1000, "goodbye")
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

        evaluateAndPublishSyncState()

        _connectionState.value = TransportState.Ready

        // Mark session start for uptime calculation. Issue #128.
        connectedAtMs = System.currentTimeMillis()

        startStallWatchdog()
    }

    override fun onMetadataUpdate(metadata: TrackMetadata) {
        // Per spec, extrapolate the reported position from the metadata's
        // server timestamp to "now" before publishing. Without this, the
        // position is stale by network latency plus however long the
        // snapshot sat on the server (and downstream anchors interpolate
        // from receive time). Requires a converged clock; fall back to the
        // raw value until then.
        val published = if (timeFilter.isReady) {
            val pos = metadata.progressAtServerTime(timeFilter.clientToServer(System.nanoTime() / 1000))
            metadata.progress?.let { metadata.copy(progress = it.copy(trackProgress = pos)) } ?: metadata
        } else {
            metadata
        }
        callback.onMetadataUpdate(published)
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
        callback.onStreamClear()
    }

    override fun onStreamEnd() {
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
        callback.onControllerStateUpdate(state)
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
     * Called when the network changes while the socket is still alive.
     *
     * Fork: the time filter is NOT reset. A reset muted audio for the 5-10 s
     * reconvergence even when the clock relationship was unchanged (e.g. a
     * DHCP renewal); the filter's adaptive forgetting absorbs a real route
     * step, and a new connection resets it in connectLocal anyway.
     */
    fun onNetworkChanged() {
        if (!isConnected) return

        Log.i(TAG, "Network changed - relying on adaptive forgetting for re-sync")
        callback.onNetworkChanged()
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
        if (transport != null) {
            // "A client that leaves one server for another MUST send this
            // reason to the server it is leaving." Nothing is sent when the
            // connection being replaced had not got as far as an activation.
            val reason = if (address == serverAddress) {
                GoodbyeReason.RESTART
            } else {
                GoodbyeReason.ANOTHER_SERVER
            }
            Log.i(TAG, "Leaving $serverAddress first (${reason.wire})")
            leave(reason, reconnect = false)
        }

        val normalizedPath = normalizePath(path)

        Log.d(TAG, "Connecting locally to: $address path=$normalizedPath")
        _connectionState.value = TransportState.Connecting

        // The clock estimate belongs to the connection that measured it. It
        // is discarded here and not in the teardown, so that audio already
        // handed to the output finishes against the clock it was scheduled
        // with.
        timeFilter.reset()
        resetSyncStateTracking()

        serverAddress = address
        serverPath = normalizedPath

        createLocalTransport(address, normalizedPath)
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
        synchronized(connectionLock) { transport = wsTransport }
        wsTransport.setListener(TransportEventListener())
        wsTransport.connect()
    }

    /**
     * Disconnect because the network changed under the connection (WiFi ->
     * Cellular). Unlike [disconnect] this is not a user action: the goodbye
     * says we will be back ("restart": the server should auto-reconnect too),
     * and the owner of this client reconnects on whatever network we are on
     * now.
     */
    fun disconnectForReselection() = leave(GoodbyeReason.RESTART, reconnect = true)

    /**
     * Disconnect from the current server because the user asked to.
     */
    fun disconnect() = leave(GoodbyeReason.USER_REQUEST, reconnect = false)

    /**
     * Say why we are leaving, then end the connection: every deliberate
     * disconnect.
     *
     * The goodbye is encrypted before the teardown retires the channel and
     * handed to the transport before this returns, and the close waits for
     * the transport to flush it. Both matter: the callers go straight on to
     * open the next connection or to cancel the coroutine scopes, and a
     * plain close() drops whatever is still queued.
     */
    private fun leave(reason: GoodbyeReason, reconnect: Boolean) {
        Log.d(TAG, "Disconnecting (${reason.wire})")
        val goodbye = encodeGoodbye(reason)
        val closing = endConnection(TransportState.Idle, reconnect) ?: return
        if (goodbye.isEmpty()) {
            // Nothing to flush: the connection never got as far as an
            // activation, so there is no one to say goodbye to.
            closing.close(1000, reason.wire)
            closing.destroy()
        } else {
            goodbye.forEach { closing.send(it) }
            closing.closeAfterFlush(1000, reason.wire)
        }
    }

    /**
     * End the current connection. Every way a connection can end comes
     * through here: a remote close, a transport failure, the stall watchdog,
     * a protocol or handshake failure, `server/unpair`, a rejected
     * activation, a user disconnect, a server switch.
     *
     * Nothing of the connection is left for the next one to inherit: time
     * sync and the handshake timer are stopped, the encrypted channel and
     * everything the handler learned over it are forgotten, and the pairing
     * flows are told the socket is gone.
     *
     * The transport is detached - so nothing it reports afterwards reaches
     * this client - and returned for the caller to close the way its case
     * needs. Null when there was none.
     *
     * @param reconnect what [Callback.onDisconnected] is told.
     */
    private fun endConnection(newState: TransportState, reconnect: Boolean): SendSpinTransport? =
        synchronized(connectionLock) {
            val ended = transport
            ended?.setListener(null)
            transport = null

            stopStallWatchdog()
            handshakeTimeoutJob?.cancel()
            handshakeTimeoutJob = null
            handshakeDriver = null

            val wasEstablished = handshakeComplete
            resetConnectionState()
            matchedPsk = null
            sessionFacts = null
            _controllerState.value = null
            connectedAtMs = null

            // Before the state changes, so whoever reacts to the new state
            // can already have been told what to do about it.
            if (wasEstablished) callback.onDisconnected(reconnect)
            _connectionState.value = newState
            ended
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

    /** Seek to an absolute position, clamped to 0..seek_max_ms. */
    fun seek(positionMs: Long) = sendCommand("seek", positionMs = positionMs)

    /** Seek by a signed offset from the current position. */
    fun seekRelative(offsetMs: Long) = sendCommand("seek_relative", offsetMs = offsetMs)

    /**
     * Clean up resources.
     */
    fun destroy() {
        // "When the device is powering off or otherwise not coming back ...
        // clients SHOULD send this reason."
        leave(GoodbyeReason.SHUTDOWN, reconnect = false)

        // Cancel the scope before closing the timer dispatcher. Cancelling
        // the scope cancels all its launched coroutines; closing the
        // dispatcher shuts down the underlying executor thread.
        timerScope.cancel()
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
     * Stop the stall watchdog. Called when the connection ends.
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
     * if so. Only acts when the client is connected and the handshake is complete.
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
        if (!handshakeComplete) return
        val t = transport ?: return
        if (!t.isConnected) return

        // The handler's view, which stream/clear does not end: audio keeps
        // arriving after a skip, so the short threshold must keep applying.
        val streaming = isStreamActive
        val threshold = if (streaming) STALL_TIMEOUT_MS else IDLE_STALL_TIMEOUT_MS
        val sinceLastByte = System.currentTimeMillis() - lastByteReceivedAtMs.get()
        if (sinceLastByte > threshold) {
            val mode = if (streaming) "streaming" else "idle"
            Log.w(TAG, "Stall watchdog: no data received in ${sinceLastByte}ms ($mode threshold ${threshold}ms) - forcing transport close")
            // Reported back through onClosed, which ends the connection.
            t.close(1001, "stall watchdog ($mode)")
        }
    }

    /**
     * Record disconnect state and emit a structured `[disconnect]` log line
     * consumable by anyone reading the on-device log file shared via Settings.
     *
     * Invariants (issue #128):
     *   * Fires on every close or failure the transport reports -- normal,
     *     abnormal or pre-handshake.
     *   * Uptime is derived from [connectedAtMs] (null -> "preconnect" when
     *     handshake had not yet completed).
     *   * Logs at INFO so `AppLog.level = WARN or above` suppresses emission
     *     without any formatting cost.
     */
    private fun recordDisconnectTelemetry(code: Int?, reasonText: String) {
        val now = System.currentTimeMillis()
        val connectedAt = connectedAtMs

        // Persist for the stats screen.
        lastDisconnectCode = code
        lastDisconnectReason = reasonText

        val codeField = code?.toString() ?: "none"
        val uptimeField = if (connectedAt != null) {
            "%.1f".format((now - connectedAt) / 1000.0)
        } else {
            "preconnect"
        }
        AppLog.Network.i(
            "[disconnect] code=$codeField reason=${reasonText.ifBlank { "unknown" }} " +
                "uptime_s=$uptimeField"
        )
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
        if (throwable != null) {
            // Fork: walk the cause chain. Ktor over OkHttp wraps the real cause
            // 2-3 levels deep and responseCode is never wired through, so a
            // proxy upgrade's 401/403 only shows up as a nested message like
            // "Expected HTTP 101 response but was '401 Unauthorized'".
            val chain = generateSequence(throwable) { it.cause }.take(16).toList()
            val combinedMessage = chain.mapNotNull { it.message }.joinToString(" | ").lowercase()
            // Network/handshake first, so a host:port containing 401/403 is
            // never read as auth.
            if (chain.any { it is javax.net.ssl.SSLException || it is java.net.UnknownHostException } ||
                combinedMessage.contains("refused")) {
                return FailureReason.HandshakeFailed
            }
            if (combinedMessage.contains("unauthorized") ||
                combinedMessage.contains("forbidden") ||
                Regex("""\b(401|403)\b""").containsMatchIn(combinedMessage)) {
                return FailureReason.AuthRejected
            }
        }
        return FailureReason.TransientNetwork
    }

    // ========== Transport Event Listener ==========

    /**
     * Event listener for the WebSocket transport.
     *
     * A close or a failure reported here is one this client did not ask for:
     * [leave] and [closeConnectionAfterFlush] detach the listener before
     * they close. That includes the closes the stall watchdog and a protocol
     * failure make themselves, and a server closing with code 1000.
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
                // Serialized against the handshake timeout, which fires on
                // the timer thread.
                synchronized(driver) { driver.onCleartextFrame(rawUtf8) }
                return
            }
            // "a cleartext message received after switching to transport mode
            // is a silent failure: the detecting side closes the connection".
            // A text frame out here was never decrypted, so dispatching it
            // would let anyone on the path inject server/command, server/unpair
            // or stream/* as if the server had sent them.
            onProtocolFailure("cleartext text frame outside the handshake")
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

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. Issue #128.
            recordDisconnectTelemetry(
                code = code,
                reasonText = reason.ifEmpty { "code=$code" },
            )

            endConnection(TransportState.Idle, reconnect = true)?.destroy()
        }

        override fun onFailure(error: Throwable, isRecoverable: Boolean) {
            if (handshakeComplete) {
                // Bounded summary, not the stack trace: getStackTraceString()
                // spikes the heap under memory pressure.
                Log.e(TAG, "Transport failure: " + throwableSummary(error))
            } else {
                // An attempt that did not get through, possibly one of many
                // while a server is away: the reason is enough.
                Log.w(TAG, "Connect failed: $error")
            }

            // Record telemetry for the stats screen + emit the structured [disconnect]
            // log line. onFailure has no WebSocket close code -- use `null` code and
            // the error class/message as the reason. Issue #128.
            recordDisconnectTelemetry(
                code = null,
                reasonText = error.message ?: error::class.java.simpleName,
            )

            // Failed is for an attempt that did not get through. A connection
            // that was up and broke has simply ended, whatever broke it.
            val state = if (isRecoverable || handshakeComplete) {
                TransportState.Idle
            } else {
                TransportState.Failed(classifyFailureReason(throwable = error))
            }
            endConnection(state, reconnect = true)?.destroy()
        }
    }
}
