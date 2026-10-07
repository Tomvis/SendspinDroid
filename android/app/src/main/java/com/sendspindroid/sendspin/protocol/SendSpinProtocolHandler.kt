package com.sendspindroid.sendspin.protocol

import android.util.Log
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.MinBufferEstimator
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.NoiseCrypto
import com.sendspindroid.sendspin.crypto.NoiseTransport
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.asNoiseCrypto
import com.sendspindroid.sendspin.protocol.message.ArtworkReceiver
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import com.sendspindroid.sendspin.crypto.TrustStore
import com.sendspindroid.sendspin.pairing.DynamicPairingAction
import com.sendspindroid.sendspin.pairing.DynamicPairingCodeFlow
import com.sendspindroid.sendspin.pairing.DynamicPairingEvent
import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.pairing.PairMethod
import com.sendspindroid.sendspin.pairing.PairingAction
import com.sendspindroid.sendspin.pairing.PairingCounterStore
import com.sendspindroid.sendspin.pairing.PairingEvent
import com.sendspindroid.sendspin.pairing.PairingFailureCounter
import com.sendspindroid.sendspin.pairing.PairingPskFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Abstract base class for SendSpin protocol handling.
 *
 * Contains shared protocol logic used by [SendSpin]:
 * - Message building and sending
 * - Message parsing and dispatching
 * - Time synchronization
 * - Binary message handling
 *
 * Subclasses implement transport-specific behavior and connection state
 * management.
 *
 * @param tag Log tag for debugging
 */
abstract class SendSpinProtocolHandler(
    protected val tag: String
) {
    // Protocol state
    @Volatile
    protected var handshakeComplete = false
    protected var currentVolume: Int = 100
    protected var currentMuted: Boolean = false
    // Per Sendspin spec, a client that has not yet synchronized to the
    // server timeline reports "error". Updated by [evaluateAndPublishSyncState].
    protected var currentSyncState: String = "error"

    private val syncStateLock = Any()
    private var hasEverConverged: Boolean = false
    private var lastPublishedMute: Boolean = false

    // True while the client's audio output is in use by an external system
    // (e.g. another app holds audio focus). Overrides synchronized/error
    // reporting until cleared. Per spec, the server reacts by parking this
    // client in a solo group and ending its streams.
    private var externalSourceActive: Boolean = false

    // Stream active tracking (mirrors CLI _stream_active)
    private var _streamActive = false
    private var _currentStreamConfig: StreamConfig? = null

    // Artwork stream (roles/artwork/v1.md). One channel is declared, so only
    // channel 0 is shown. [artworkLock] orders a pending image coming due on
    // the timer scope against the receive thread replacing or discarding it.
    private val artworkLock = Any()
    private val artworkReceiver = ArtworkReceiver()
    private var artworkStreamActive = false
    private var artworkChannelConfig: JsonElement? = null
    private var pendingArtwork: Job? = null

    // Last received values for change detection (avoids unnecessary UI recomposition)
    private var lastPlaybackState: String? = null
    private var lastGroupInfo: GroupInfo? = null

    // Scheduled metadata (roles/metadata/v1.md): at most one update waiting
    // for its timestamp. [metadataLock] orders it coming due on the timer
    // scope against the receive thread replacing or discarding it.
    private val metadataLock = Any()
    private var pendingMetadata: Job? = null

    // Controller (group-level) state from the latest server/state.
    private var currentControllerState: ControllerState? = null

    // Time sync manager (lazy initialized by subclass)
    protected var timeSyncManager: TimeSyncManager? = null

    // Sizes the min_buffer_ms we report from audio chunk arrival delay.
    private val minBufferEstimator = MinBufferEstimator()

    // Set once the first stream of this connection has started. Until then
    // the required_lead_time_ms we report has to cover a cold start.
    @Volatile
    private var outputStarted = false

    // ========== Abstract Transport Methods ==========

    /** Send a raw WebSocket BINARY frame. */
    protected abstract fun sendBinaryFrame(bytes: ByteArray)

    /**
     * Get the coroutine scope for async operations.
     */
    protected abstract fun getCoroutineScope(): CoroutineScope

    /**
     * Get the time filter for this connection.
     */
    abstract fun getTimeFilter(): SendspinTimeFilter

    /**
     * Whether the device is in low-memory mode (smaller buffer target).
     */
    protected abstract fun isLowMemoryMode(): Boolean

    /**
     * Get the client ID for this connection.
     */
    protected abstract fun getClientId(): String

    /**
     * Get the device name for this connection.
     */
    protected abstract fun getDeviceName(): String

    // ========== Abstract Event Callbacks ==========

    /**
     * Called when handshake completes with server.
     */
    protected abstract fun onHandshakeComplete(serverName: String, serverId: String)

    /**
     * Called when track metadata is updated.
     */
    protected abstract fun onMetadataUpdate(metadata: TrackMetadata)

    /**
     * Called when playback state changes.
     */
    protected abstract fun onPlaybackStateChanged(state: String)

    /**
     * Called when server sends a volume command.
     */
    protected abstract fun onVolumeCommand(volume: Int)

    /**
     * Called when server sends a mute command.
     */
    protected abstract fun onMuteCommand(muted: Boolean)

    /**
     * Called when group info is updated.
     */
    protected abstract fun onGroupUpdate(info: GroupInfo)

    /**
     * Called when audio stream starts.
     */
    protected abstract fun onStreamStart(config: StreamConfig)

    /**
     * Called when stream clear is requested.
     */
    protected abstract fun onStreamClear()

    /**
     * Called when stream ends (server terminates playback).
     */
    protected abstract fun onStreamEnd()

    /**
     * Called when audio chunk is received.
     */
    protected abstract fun onAudioChunk(timestampMicros: Long, audioData: ByteArray)

    /**
     * Called when a channel's current image changes. An empty [payload] means
     * the channel no longer displays artwork.
     */
    protected abstract fun onArtwork(channel: Int, payload: ByteArray)

    /**
     * Called when sync offset is received from GroupSync.
     */
    protected abstract fun onSyncOffsetApplied(offsetMs: Double, source: String)

    /**
     * Called when the controller (group-level) state changes:
     * supported_commands, group volume/mute, repeat, shuffle.
     * Default no-op for handlers that don't surface controller state.
     */
    protected open fun onControllerStateUpdate(state: ControllerState) {}

    /**
     * Called when the audio output should be silenced or unsilenced because
     * the client cannot maintain sync. Per Sendspin spec, clients in the
     * "error" state must mute their audio output and continue buffering
     * until they can resume synchronized playback.
     *
     * Fires only on transitions, not on every re-evaluation. The argument
     * is the desired mute state.
     */
    protected abstract fun onSyncMuteChanged(muted: Boolean)

    // ========== Protocol Message Sending ==========

    /**
     * Get the manufacturer name for device identification.
     */
    protected abstract fun getManufacturer(): String

    /**
     * Get the supported audio formats for the client/hello handshake.
     */
    protected abstract fun getSupportedFormats(): List<MessageBuilder.FormatEntry>

    /**
     * Get the client app version reported in device_info.software_version.
     */
    protected abstract fun getSoftwareVersion(): String

    /**
     * The `supported_formats` this connection's client/hello advertised. A
     * `format` preference in client/state "MUST be one of the entries" here.
     */
    @Volatile
    private var advertisedFormats: List<MessageBuilder.FormatEntry> = emptyList()

    /** The overridden format preference reported in client/state, if any. */
    @Volatile
    private var preferredFormat: MessageBuilder.FormatEntry? = null

    /**
     * Send client/hello, the reply to server/hello.
     *
     * Buffer capacity is computed from the format list and target duration
     * so the wire-byte cap scales with the highest PCM bitrate we advertise.
     */
    private fun sendClientHello() {
        val formats = getSupportedFormats()
        advertisedFormats = formats
        // The hello's priority order already puts the preferred codec first.
        preferredFormat = null
        val bufferDuration = if (isLowMemoryMode()) {
            SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            SendSpinProtocol.Buffer.DURATION_NORMAL_SEC
        }
        val bufferCapacity = MessageBuilder.calculateBufferCapacity(formats, bufferDuration)
        val text = MessageBuilder.buildClientHello(
            deviceName = getDeviceName(),
            bufferCapacity = bufferCapacity,
            manufacturer = getManufacturer(),
            supportedFormats = formats,
            lowMemoryMode = isLowMemoryMode(),
            softwareVersion = getSoftwareVersion(),
            unpairedAccessEnabled = isUnpairedAccessEnabled(),
            supportedPairMethods = getSupportedPairMethods(),
        )
        sendProtocolMessage(text)
        // Logged whole, not truncated. The pairing fields sit at the end of the
        // payload, and whether a server offers pairing at all is decided by
        // them - a 500-character cut hid exactly the thing worth checking when
        // Music Assistant reports "this player has nothing to pair". Nothing
        // here is secret: client_id is public, and the pair methods are names.
        Log.d(tag, "Sent client/hello: $text")
    }

    /**
     * Send client/time message for clock synchronization.
     */
    protected fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds
        sendProtocolMessage(MessageBuilder.buildClientTime(clientTransmitted))
    }

    /**
     * Encrypt a `client/goodbye` and retire the channel it was encrypted for.
     *
     * Returns the frames instead of sending them, and does so before
     * returning rather than on the coroutine scope: the caller is about to
     * close this connection - and may be about to open the next one or cancel
     * the scope - so the frames have to be in its hands first. A goodbye
     * queued behind an async encrypt loses that race every time.
     *
     * Empty when there is nothing to say goodbye to: before `server/hello`
     * (the [handshakeComplete] gate) or mid re-handshake, when no application
     * message may be started.
     */
    protected fun encodeGoodbye(reason: GoodbyeReason): List<ByteArray> {
        val codec = wireCodec
        if (codec == null || !handshakeComplete || rehandshakeInProgress) return emptyList()
        // Nothing may follow a goodbye under these keys.
        wireCodec = null
        Log.d(tag, "Sending client/goodbye reason=${reason.wire}")
        // The codec only suspends for its send mutex, which is never held
        // across anything but the encryption itself.
        return runBlocking { codec.encodeJson(MessageBuilder.buildGoodbye(reason)) }
    }

    /**
     * Whether this client can currently participate in playback.
     *
     * Two conditions, and they mean different things:
     *
     * - The time filter must have converged. "A player MUST NOT report
     *   `available: true` until its time filter has converged enough to begin
     *   scheduling playback." Reporting availability early invites the server to
     *   schedule audio against a clock estimate we do not trust yet.
     * - The output must not be held by an external system. That is the ONLY
     *   other meaning `available: false` carries since the spec replaced the old
     *   `state` string (#115) - it is not a way to signal a sync problem.
     *
     * Note the asymmetry with the old tri-state: there is no longer any way to
     * tell the server "I am here but unhealthy". A client that loses sync mid
     * stream stays `available: true` and mutes its own output; going
     * `available: false` would make the server move us to a solo group and
     * require an explicit `switch` to get back, which is much more disruptive
     * than a brief mute.
     */
    protected fun isAvailable(): Boolean {
        if (externalSourceActive) return false
        // Convergence gates only the FIRST `available: true`. Once reached it
        // is latched for the connection: the server ends our streams the
        // moment we report false, so a filter that wobbles when a stream
        // starts must not take us out of the playback it just joined.
        return hasEverConverged || getTimeFilter().isConverged
    }

    /**
     * Whether this client admits a server holding no pairing record.
     *
     * This is what decides whether an unpaired connection can carry audio at
     * all: the spec allows `'playback'` on an unpaired session "only when the
     * client has unpaired access enabled". Default on, so a fresh install plays
     * before anyone has paired anything.
     */
    protected open fun isUnpairedAccessEnabled(): Boolean = true

    /**
     * The pairing methods this client currently offers.
     *
     * "Every client offers at least the Pairing PSK method", and its PSK stays
     * in the handshake candidate set at all times.
     */
    protected open fun getSupportedPairMethods(): List<MessageBuilder.PairMethodDescriptor> =
        listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK)

    /**
     * Send player state update (volume/muted/availability).
     */
    protected fun sendPlayerStateUpdate() {
        // The spec's output_delay_ms, NOT our signed staticDelayMs: the
        // latter is the hardware latency we already compensate ourselves, and
        // reporting it would invite the server to compensate it again.
        val delayMs = getTimeFilter().outputDelayMs
        sendProtocolMessage(
            MessageBuilder.buildPlayerState(
                currentVolume, currentMuted, isAvailable(), delayMs,
                requiredLeadTimeMs = if (outputStarted) {
                    SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_WARM_MS
                } else {
                    SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS
                },
                minBufferMs = minBufferEstimator.minBufferMs,
                // A role's object may only appear once the role is active.
                playerRoleActive = activeRoles.contains(ROLE_PLAYER_V1),
                format = preferredFormat,
                artworkRoleActive = activeRoles.contains(SendSpinProtocol.Roles.ARTWORK),
            )
        )
    }

    /**
     * Measure how late an audio chunk arrived and report `min_buffer_ms` when
     * that changes it (roles/player/v1.md, "Measuring timing parameters").
     *
     * The server sent the chunk at `timestamp - send_ahead` on its clock; the
     * delay is the arrival time minus that instant on ours. It is clock
     * mapping only: `min_buffer_ms` "MUST NOT include `output_delay_ms`".
     */
    private fun measureChunkDelay(chunk: BinaryMessageParser.BinaryMessage.Audio) {
        // Both saturation values report that no lead was measured, and a
        // player "MUST NOT use a chunk carrying either as a delay sample".
        if (chunk.sendAheadMicros == 0L || chunk.sendAheadMicros == SendSpinProtocol.SEND_AHEAD_SATURATED) return
        val filter = getTimeFilter()
        if (!filter.isConverged) return

        val arrivalMicros = System.nanoTime() / 1000
        val sentMicros = filter.computeClientTime(chunk.timestampMicros - chunk.sendAheadMicros)
        if (minBufferEstimator.addChunk(chunk.timestampMicros, sentMicros, arrivalMicros) && handshakeComplete) {
            sendPlayerStateUpdate()
        }
    }

    /**
     * Public hook for code outside the protocol handler to push a fresh
     * `client/state` to the server.
     */
    fun sendClientStateSnapshot() {
        if (!handshakeComplete) return
        sendPlayerStateUpdate()
    }

    /**
     * Set sync state and notify server.
     *
     * Per spec: report "synchronized" when locked to server timeline,
     * report "error" when unable to maintain sync (buffer underrun, clock issues).
     *
     * @param syncState Either "synchronized" or "error"
     */
    fun setSyncState(syncState: String) {
        if (syncState != "synchronized" && syncState != "error") {
            Log.w(tag, "Invalid sync state: $syncState (must be 'synchronized' or 'error')")
            return
        }
        if (currentSyncState != syncState) {
            currentSyncState = syncState
            Log.d(tag, "Sync state changed to: $syncState")
            if (handshakeComplete) {
                sendPlayerStateUpdate()
            }
        }
    }

    /**
     * Report or clear the 'external_source' client state (spec: output is
     * in use by an external system, e.g. another app holds audio focus).
     *
     * While active, [evaluateAndPublishSyncState] is suspended so the
     * filter-derived synchronized/error states don't overwrite it. On
     * clear, the state is recomputed from the time filter and republished.
     *
     * Safe to call from any thread.
     */
    fun setExternalSource(active: Boolean) {
        val changed = synchronized(syncStateLock) {
            if (externalSourceActive == active) return
            externalSourceActive = active
            if (active) {
                currentSyncState = "external_source"
            } else {
                val filter = getTimeFilter()
                currentSyncState = if (filter.isReady && filter.isConverged) "synchronized" else "error"
            }
            true
        }
        if (changed) {
            Log.i(tag, "External source ${if (active) "active" else "cleared"}: state=$currentSyncState")
            if (handshakeComplete) sendPlayerStateUpdate()
        }
    }

    /**
     * Recompute the client's sync state from the time filter and publish
     * any change to the server and to the audio sink.
     *
     * Reports "synchronized" once the filter is converged for the first
     * time, "error" otherwise. Audio mute is requested only after a
     * successful sync has been established at least once and is then lost
     * — the initial pre-sync window does not silence playback.
     *
     * Idempotent: only fires server / mute notifications on transitions.
     * Safe to call from any thread.
     */
    fun evaluateAndPublishSyncState() {
        val muteChange: Boolean? = synchronized(syncStateLock) {
            // While an external source owns the output, synchronized/error
            // reporting (and its mute side effects) is suspended.
            if (externalSourceActive) return

            val filter = getTimeFilter()
            val converged = filter.isReady && filter.isConverged
            if (converged) {
                hasEverConverged = true
            }

            val desiredState = if (converged) "synchronized" else "error"
            setSyncState(desiredState)

            val desiredMute = hasEverConverged && desiredState == "error"
            if (desiredMute != lastPublishedMute) {
                lastPublishedMute = desiredMute
                desiredMute
            } else {
                null
            }
        }
        if (muteChange != null) {
            onSyncMuteChanged(muteChange)
        }
    }

    /**
     * Reset all sync-state tracking back to "before any sync has been
     * achieved on this server." Call this on a fresh connection to a new
     * server; do NOT call it during a normal reconnect cycle.
     *
     * Safe to call from any thread.
     */
    fun resetSyncStateTracking() {
        val needsUnmute = synchronized(syncStateLock) {
            hasEverConverged = false
            externalSourceActive = false
            currentSyncState = "error"
            if (lastPublishedMute) {
                lastPublishedMute = false
                true
            } else {
                false
            }
        }
        if (needsUnmute) {
            onSyncMuteChanged(false)
        }
    }

    /**
     * Send a controller command (play, pause, stop, next, previous, volume,
     * mute, repeat_off, repeat_one, repeat_all, shuffle, unshuffle, switch,
     * seek, seek_relative).
     *
     * "Only valid from clients whose `controller` role is active", and the
     * command "MUST be one of the values listed in `supported_commands` from
     * the latest controller state the client received". So nothing is sent
     * while the role is inactive, before a controller state has arrived, or
     * for a command outside the advertised set.
     *
     * @param volume only used when [command] is "volume"
     * @param mute only used when [command] is "mute"
     * @param positionMs required when [command] is "seek"; clamped to the
     *   spec's "range 0 to seek_max_ms"
     * @param offsetMs required when [command] is "seek_relative"
     */
    fun sendCommand(
        command: String,
        volume: Int? = null,
        mute: Boolean? = null,
        positionMs: Long? = null,
        offsetMs: Long? = null,
    ) {
        if (SendSpinProtocol.Roles.CONTROLLER !in activeRoles) {
            Log.w(tag, "Dropping controller command '$command': controller role is not active")
            return
        }
        val state = currentControllerState
        val supported = state?.supportedCommands
        if (supported == null || command !in supported) {
            Log.w(tag, "Dropping controller command '$command': not in server supported_commands $supported")
            return
        }
        var position = positionMs
        if (command == "seek") {
            // The server MUST send seek_max_ms whenever it offers 'seek'.
            val seekMaxMs = state?.seekMaxMs
            if (position == null || seekMaxMs == null) {
                Log.w(tag, "Dropping seek: position_ms=$position seek_max_ms=$seekMaxMs")
                return
            }
            position = position.coerceIn(0, seekMaxMs)
        } else if (command == "seek_relative" && offsetMs == null) {
            Log.w(tag, "Dropping seek_relative: offset_ms is required")
            return
        }
        sendProtocolMessage(MessageBuilder.buildCommand(command, volume, mute, position, offsetMs))
    }

    /**
     * Prefer [codec] for this player's stream.
     *
     * Reported as `format` in the client/state player object: "When `format`
     * changes while a `player` stream is active, the server re-derives the
     * stream format and sends a `stream/start` if it changed", which flows
     * through the normal format-change reconfiguration path.
     *
     * The preference "MUST be one of the entries in `supported_formats`", and
     * client/hello is sent once per connection, so a codec this connection did
     * not advertise can only take effect on the next one.
     */
    fun setPreferredCodec(codec: String) {
        val format = advertisedFormats.firstOrNull { it.codec == codec }
        if (format == null) {
            Log.i(tag, "Preferred codec $codec was not advertised on this connection; " +
                "it applies from the next connect")
            return
        }
        Log.i(tag, "Preferring stream format: $format")
        preferredFormat = format
        if (handshakeComplete) sendPlayerStateUpdate()
    }

    // ========== Player State Methods ==========

    /**
     * Set volume and notify server.
     *
     * @param volume Volume level from 0.0 to 1.0
     */
    fun setVolume(volume: Double) {
        val volumePercent = (volume * 100).toInt().coerceIn(0, 100)
        currentVolume = volumePercent
        Log.d(tag, "setVolume: $volumePercent%")
        sendPlayerStateUpdate()
    }

    /**
     * Set muted state and notify server.
     */
    fun setMuted(muted: Boolean) {
        currentMuted = muted
        Log.d(tag, "setMuted: $muted")
        sendPlayerStateUpdate()
    }

    /**
     * Set initial volume before handshake.
     *
     * @param volume Volume level from 0 to 100
     * @param muted Whether audio is muted
     */
    fun setInitialVolume(volume: Int, muted: Boolean = false) {
        currentVolume = volume.coerceIn(0, 100)
        currentMuted = muted
        Log.d(tag, "Initial volume set: $currentVolume, muted=$currentMuted")
    }

    // ========== Time Sync ==========

    /**
     * Start time synchronization.
     */
    protected fun startTimeSync() {
        val manager = timeSyncManager
        if (manager != null && !manager.isRunning) {
            manager.start(getCoroutineScope())
        }
    }

    /**
     * Stop time synchronization.
     */
    protected fun stopTimeSync() {
        timeSyncManager?.stop()
    }

    /**
     * Initialize time sync manager.
     */
    protected fun initTimeSyncManager(timeFilter: SendspinTimeFilter) {
        timeSyncManager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendClientTime() },
            onMeasurementApplied = { evaluateAndPublishSyncState() },
            tag = tag
        )
    }

    // ========== Encrypted channel ==========

    /**
     * Set once the Noise handshake completes. Every application message, in
     * both directions, goes through it; before then nothing may be sent or
     * dispatched at all.
     */
    @Volatile
    private var wireCodec: NoiseWireCodec? = null

    /** Install the transport produced by the handshake driver. */
    fun installEncryptedTransport(transport: NoiseTransport) =
        installEncryptedChannel(transport.asNoiseCrypto())

    /** [installEncryptedTransport], taking the codec's narrow view so tests can fake it. */
    internal fun installEncryptedChannel(crypto: NoiseCrypto) {
        wireCodec = NoiseWireCodec(crypto)
        rehandshakeInProgress = false
        // This is the sole call site for a FRESH handshake - a re-handshake
        // never calls it, going through resetForRehandshake() instead - so
        // without this reset the counter would keep accumulating across
        // reconnects on a reused SendSpin instance while each new server
        // connection counts from 1, making our index look "ahead of the
        // server's count".
        resetPairingIndexForFreshHandshake()
        Log.i(tag, "Encrypted channel established")
    }

    /**
     * Forget the previous connection's channel. Called before every fresh
     * handshake, so that nothing sent while the new one is in progress can be
     * encrypted under the old session's keys.
     */
    protected fun clearEncryptedChannel() {
        wireCodec = null
        rehandshakeInProgress = false
    }

    /**
     * True from the moment Noise message 1 of a re-handshake is received until
     * the `server/activate` that follows it.
     *
     * "The server MUST NOT start new application messages after sending Noise
     * message 1, nor the client after receiving it, except for the handshake
     * and `server/activate`." [sendProtocolMessage] drops everything while
     * this is set; the activation re-sends client/state, so nothing that
     * matters is lost.
     */
    @Volatile
    private var rehandshakeInProgress = false

    /**
     * Reset [pairingIndex] to 0: pairing_index counts "the number of pairing
     * server/activate messages received since the last Noise handshake"
     * (pairing.md, "Pairing index"), and that count must restart on EVERY
     * Noise handshake - fresh or re-handshake alike.
     */
    protected fun resetPairingIndexForFreshHandshake() {
        pairingIndex = 0
    }

    /**
     * A `noise/handshake` arrived inside the encrypted channel.
     *
     * Overridden by the connection, which owns the identity, the candidate set
     * and the prior handshake hash. The base implementation closes: a
     * `noise/handshake` is only ever valid in transport mode, and a handler
     * that cannot run one must not silently ignore it.
     */
    protected open fun onRehandshakeMessage(payload: JsonObject?) {
        onProtocolFailure("noise/handshake received but re-handshake is not supported here")
    }

    /**
     * Reset what a re-handshake invalidates, once the new keys are in place.
     *
     * "Connection state, such as open streams with their buffered data or the
     * time filter, persists across a re-handshake; only the session keys and
     * what the handshake itself derives change." Neither hello is re-sent and
     * the next `server/activate` "is a subsequent one on the same connection",
     * so the active roles, the activities and [handshakeComplete] all stay as
     * they are: an activation that omits `active_roles` inherits them.
     */
    protected fun resetForRehandshake() {
        // The dynamic flow's sid is derived from this session's handshake
        // hash, and pairing_index counts "activations since the last Noise
        // handshake" - both go stale the moment a new handshake completes.
        dynamicPairingFlow = null
        resetPairingIndexForFreshHandshake()
        dynamicAttemptTimeoutJob?.cancel()
        dynamicAttemptTimeoutJob = null
        activePairingMethod = null
        // A re-handshake landing mid-attempt must not strand the pairing UI:
        // the flow above is dropped without going through StopEmittingCode, so
        // this is the same clear that action would have triggered.
        onDynamicPairingCodeCleared()
    }

    /**
     * Send an application protocol message over the encrypted channel.
     */
    protected fun sendProtocolMessage(text: String) {
        // encodeJson takes the send mutex, so this has to be in a coroutine.
        getCoroutineScope().launch { sendProtocolMessageAwaiting(text) }
    }

    /**
     * [sendProtocolMessage], but the caller can tell when the frames have been
     * handed to the transport.
     *
     * Needed wherever something must happen strictly after a message is on the
     * wire - closing the connection after a goodbye, for instance. The
     * fire-and-forget version returns while the encrypt is still queued, so
     * "send, then close" written in that order does not execute in it.
     */
    protected suspend fun sendProtocolMessageAwaiting(text: String) {
        val codec = wireCodec
        if (codec == null) {
            Log.w(tag, "Dropping outbound message: no encrypted channel")
            return
        }
        if (rehandshakeInProgress) {
            Log.d(tag, "Dropping outbound message during re-handshake")
            return
        }
        // client/pair-finalize carries the new long-term PSK; it never goes
        // to the log.
        if (SendSpinProtocol.MessageType.CLIENT_PAIR_FINALIZE in text) {
            Log.d(tag, "Sent: client/pair-finalize (payload withheld)")
        } else {
            Log.d(tag, "Sent: ${text.take(500)}")
        }
        try {
            codec.encodeJson(text).forEach { sendBinaryFrame(it) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to encrypt outbound message", e)
            onProtocolFailure("outbound encryption failed: ${e.message}")
        }
    }

    /**
     * Send [text] under the current keys, then promote the channel to [next].
     *
     * The completing frame of a re-handshake. Ordering is the codec's problem
     * (it holds the send mutex across encrypt-then-install); ordering with
     * respect to the *application* is this method's: [onSwapped] runs once the
     * new keys are installed and BEFORE the frame goes out. The server answers
     * that frame with `server/activate` straight away, and the activation must
     * be judged against the PSK this handshake matched, not the previous one.
     */
    protected fun sendAndSwapKeys(text: String, next: NoiseCrypto, onSwapped: () -> Unit) {
        val codec = wireCodec
        if (codec == null) {
            onProtocolFailure("re-handshake attempted with no encrypted channel")
            return
        }
        getCoroutineScope().launch {
            try {
                val frames = codec.encodeAndSwap(
                    SendSpinProtocol.BinaryType.JSON,
                    text.encodeToByteArray(),
                    next,
                )
                onSwapped()
                frames.forEach { sendBinaryFrame(it) }
            } catch (e: Exception) {
                Log.e(tag, "Re-handshake key swap failed", e)
                onProtocolFailure("re-handshake key swap failed: ${e.message}")
            }
        }
    }

    /**
     * A protocol-level failure that requires closing the socket.
     *
     * The spec allows no application-level error message for these, so the only
     * thing to do is close - and the only diagnostic anyone will ever have is
     * the log line the implementation writes here.
     */
    protected open fun onProtocolFailure(reason: String) {
        Log.e(tag, "Protocol failure: $reason")
    }

    // ========== Message Handling ==========

    /**
     * Handle incoming text (JSON) message.
     * Dispatches to appropriate handler based on message type.
     */
    protected fun handleTextMessage(text: String) {
        Log.d(tag, "Received: ${text.take(500)}")

        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = json["payload"]?.jsonObject

            when (type) {
                // An in-band re-handshake. It arrives as an ordinary encrypted
                // JSON message inside the current channel, which is why it is
                // dispatched here and not by the cleartext handshake driver.
                SendSpinProtocol.MessageType.NOISE_HANDSHAKE -> {
                    rehandshakeInProgress = true
                    onRehandshakeMessage(payload)
                }

                // Deliberately not gated on `activities`, and deliberately
                // discarding the payload: "Valid regardless of the current
                // `activities`", and the message has no fields.
                SendSpinProtocol.MessageType.SERVER_UNPAIR -> handleServerUnpair()

                SendSpinProtocol.MessageType.PAIR_ABORT -> handlePairAbort(payload)
                SendSpinProtocol.MessageType.SERVER_PAIR_INIT -> handleServerPairInit(payload)
                SendSpinProtocol.MessageType.SERVER_PAIR_AUTH -> handleServerPairAuth(payload)
                SendSpinProtocol.MessageType.SERVER_PAIR_CONFIRM -> handleServerPairConfirm(payload)
                SendSpinProtocol.MessageType.SERVER_PAIR_FINALIZE -> handleServerPairFinalize()
                SendSpinProtocol.MessageType.SERVER_HELLO -> handleServerHello(payload)
                SendSpinProtocol.MessageType.SERVER_ACTIVATE -> handleServerActivate(payload)
                SendSpinProtocol.MessageType.SERVER_TIME -> handleServerTime(payload)
                SendSpinProtocol.MessageType.SERVER_STATE -> handleServerState(payload)
                SendSpinProtocol.MessageType.SERVER_COMMAND -> handleServerCommand(payload)
                SendSpinProtocol.MessageType.GROUP_UPDATE -> handleGroupUpdate(payload)
                SendSpinProtocol.MessageType.STREAM_START -> handleStreamStart(payload)
                SendSpinProtocol.MessageType.STREAM_END -> handleStreamEnd(payload)
                SendSpinProtocol.MessageType.STREAM_CLEAR -> handleStreamClear(payload)
                SendSpinProtocol.MessageType.CLIENT_SYNC_OFFSET -> handleClientSyncOffset(payload)
                // "Clients and servers MUST ignore JSON messages with an
                // unrecognized `type`."
                else -> Log.d(tag, "Unhandled message type: $type")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    protected open fun handleServerHello(payload: JsonObject?) {
        val serverName = MessageParser.parseServerHello(payload, "Unknown")
        if (serverName == null) {
            Log.e(tag, "Failed to parse server/hello")
            return
        }
        Log.i(tag, "server/hello: name=$serverName")

        handshakeComplete = true

        // Clear cached values so the first post-handshake messages always propagate
        _streamActive = false
        _currentStreamConfig = null
        outputStarted = false
        resetArtworkStream()
        resetServerState()
        lastPlaybackState = null
        lastGroupInfo = null
        activationSeen = false
        activeRoles = emptyList()

        // The server's identity is its static key from server/init;
        // server/hello carries only the friendly name.
        onHandshakeComplete(serverName, currentServerId().orEmpty())

        // "Sent by the client once it has received server/hello." Nothing else
        // may follow until the initial server/activate: "The client MUST NOT
        // send other Sendspin messages until it receives that activation."
        sendClientHello()
    }

    /** The versioned player role, as it appears in active_roles. */
    protected val ROLE_PLAYER_V1 = SendSpinProtocol.Roles.PLAYER

    /** True once the first server/activate has been accepted on this connection. */
    @Volatile
    protected var activationSeen = false
        private set

    /** Roles the server has activated, persisted across activations that omit them. */
    @Volatile
    protected var activeRoles: List<String> = emptyList()
        private set

    /** Activities currently declared on this connection. */
    @Volatile
    protected var activities: Set<Activity> = emptySet()
        private set

    /**
     * The PSK category that admitted this connection. Drives the admissibility
     * table; item 2.3 (#204) makes it follow the real handshake result.
     */
    protected open fun matchedPskCategory(): PskCategory = PskCategory.SENTINEL

    /** Pairing methods this client currently offers, as live configuration. */
    protected open fun offeredPairMethods(): Set<String> = setOf(PairMethod.PAIRING_PSK)

    protected fun handleServerActivate(payload: JsonObject?) {
        // The activation that follows a re-handshake ends its quiet period.
        rehandshakeInProgress = false
        val activate = ServerActivateRules.parse(payload)
        if (activate == null) {
            Log.e(tag, "server/activate missing required activities")
            onProtocolFailure("malformed server/activate")
            return
        }
        if (activate.unknownActivities.isNotEmpty()) {
            // Forward compatibility: ignore, but say so - an unknown activity
            // usually means the server is newer than we are.
            Log.i(tag, "Ignoring unknown activities: ${activate.unknownActivities}")
        }

        val pairing = Activity.PAIRING in activate.activities
        if (pairing) {
            // "The number of pairing server/activate messages received since
            // the last Noise handshake": every one counts, admissible or not,
            // because the server's own count advances whatever we answer. An
            // index that only counted the accepted ones would fall behind
            // after a single method_not_supported, and the server silently
            // discards a client/pair-init carrying a lower index than its own.
            pairingIndex += 1
        }
        // "A client that has aborted an attempt likewise silently discards
        // pairing messages received before the next server/activate."
        pairingAborted = false

        val outcome = ServerActivateRules.evaluate(
            activate = activate,
            category = matchedPskCategory(),
            unpairedAccessEnabled = isUnpairedAccessEnabled(),
            previousRoles = activeRoles,
            isFirstActivation = !activationSeen,
            offeredPairMethods = offeredPairMethods(),
        )

        when (outcome) {
            is ActivationOutcome.Close -> {
                Log.w(tag, "Rejecting server/activate: ${outcome.goodbyeReason} " +
                    "(activities=${activate.activities}, roles=${activate.activeRoles})")
                // Sequenced in one coroutine, as in handleServerUnpair: the
                // reason is the whole point of the rejection, and a close
                // issued straight after the send would outrun the frame.
                getCoroutineScope().launch {
                    sendProtocolMessageAwaiting(MessageBuilder.buildGoodbye(outcome.goodbyeReason))
                    closeConnectionAfterFlush()
                }
            }

            is ActivationOutcome.AbortPairing -> {
                // Connection stays open; the server may re-activate with a
                // method we do offer. A new pairing activation supersedes the
                // attempt in flight even when it is one we refuse.
                Log.w(tag, "Aborting pairing: ${outcome.reason}")
                activePairingMethod = null
                runPairingActions(PairingEvent.NonPairingActivation)
                runDynamicPairingActions(DynamicPairingEvent.NonPairingActivation)
                sendPairAbort(outcome.reason)
            }

            is ActivationOutcome.Accept -> {
                val removedRoles = activeRoles - outcome.activeRoles.toSet()
                activities = activate.activities
                activeRoles = outcome.activeRoles
                activationSeen = true
                Log.i(tag, "server/activate accepted: activities=${activate.activities} " +
                    "roles=${outcome.activeRoles}")
                if (removedRoles.isNotEmpty()) discardRemovedRoles(removedRoles)
                onAdmissionStateChanged(
                    AdmissionState.from(activate.activities, outcome.activeRoles)
                )

                // "Pairing can run alongside playback. A server/activate that
                // adds 'pairing' to activities does not by itself affect
                // active_roles, streams, or group membership." So every
                // accepted activation reports state and keeps the clock
                // synchronized, pairing or not. The state is also what a role
                // added by this activation is waiting for: "When a role that
                // defines a state object becomes active in active_roles, the
                // client MUST send an update that includes that role's object."
                sendPlayerStateUpdate()
                startTimeSync()

                // Every accepted activation reaches both flows. The one it
                // admits an attempt for starts it; the other is told its
                // attempt, if any, is over - an activation is how the server
                // ends an attempt without finalizing, and a pairing activation
                // supersedes whatever attempt was in flight. The old attempt
                // is ended first so its timer and code are gone before the new
                // one starts.
                activePairingMethod = if (pairing) activate.pairingMethod else null
                when (activePairingMethod) {
                    PairMethod.PAIRING_PSK -> {
                        runDynamicPairingActions(DynamicPairingEvent.NonPairingActivation)
                        runPairingActions(
                            PairingEvent.PairingActivation(
                                pairingIndex = pairingIndex,
                                // From the handshake, never re-derived: this is the
                                // only thing keeping a long-term secret off an
                                // unauthenticated connection.
                                matchedCategory = matchedPskCategory(),
                            )
                        )
                    }

                    PairMethod.DYNAMIC_PAIRING_CODE -> {
                        runPairingActions(PairingEvent.NonPairingActivation)
                        runDynamicPairingActions(DynamicPairingEvent.PairingActivation(pairingIndex))
                    }

                    else -> {
                        runPairingActions(PairingEvent.NonPairingActivation)
                        runDynamicPairingActions(DynamicPairingEvent.NonPairingActivation)
                    }
                }
            }
        }
    }

    /**
     * `messaging.md#server--client-serveractivate`, "When applying a
     * `server/activate`, the client MUST": stop the output and clear the
     * buffers of every removed stream role, and "immediately discard the
     * current state and any pending scheduled update" of every removed role
     * with a `server/state` object.
     *
     * [removed] is what dropped out of `active_roles`, which covers all three
     * kinds of removal the spec lists: explicit, implicit (the connection is
     * no longer playback-capable) and a replaced role version.
     */
    private fun discardRemovedRoles(removed: List<String>) {
        Log.i(tag, "Roles removed by server/activate: $removed")
        // The server owes a stream/end before removing a stream role, and ours
        // leaves nothing playing, so these only act when that did not arrive.
        if (SendSpinProtocol.Roles.PLAYER in removed && _streamActive) endPlayerStream()
        if (SendSpinProtocol.Roles.ARTWORK in removed && artworkStreamActive) endArtworkStream()
        if (SendSpinProtocol.Roles.METADATA in removed) {
            synchronized(metadataLock) {
                discardPendingMetadata()
                onMetadataUpdate(TrackMetadata())
            }
        }
        if (SendSpinProtocol.Roles.CONTROLLER in removed) {
            currentControllerState = null
            onControllerStateUpdate(ControllerState())
        }
    }

    /**
     * Send `pair/abort`.
     *
     * "With reason `concurrent_attempt` the sender closes the connection after
     * sending, otherwise the connection stays open." The close is the sender's
     * job only - see [handlePairAbort] for the receiving side, which never
     * closes.
     *
     * The send and the close are sequenced in one coroutine for the same reason
     * the unpair goodbye is: `sendProtocolMessage` returns while the encrypt is
     * still queued, so a close issued after it can outrun the frame.
     */
    protected fun sendPairAbort(reason: String) {
        Log.w(tag, "Pairing aborted (sent): reason=$reason")
        pairingAborted = true
        val closes = reason in PairAbortReason.CLOSES_CONNECTION
        getCoroutineScope().launch {
            sendProtocolMessageAwaiting(MessageBuilder.buildPairAbort(reason))
            if (closes) closeConnectionAfterFlush()
        }
    }

    /**
     * The accepted activation, projected to what the user needs to be told.
     *
     * Fires on every accepted `server/activate` rather than only on a change:
     * activations are rare (handshake, a role change, a pairing transition),
     * and the consumers hold it in state that already collapses repeats.
     */
    protected open fun onAdmissionStateChanged(state: AdmissionState) {}

    /**
     * `pair/abort` from the server.
     *
     * Never closes the connection, for any reason including
     * `concurrent_attempt`: the spec makes the *sender* close, and closing here
     * too would race the peer's close and report the wrong reason for it.
     *
     * The reason is passed through unvalidated on purpose. A reason we do not
     * recognise still means the peer has abandoned the attempt, and treating it
     * as a protocol error would leave us waiting on an attempt that is over.
     */
    protected fun handlePairAbort(payload: JsonObject?) {
        val reason = payload?.get("reason")?.jsonPrimitive?.contentOrNull ?: "unspecified"
        Log.w(tag, "Pairing aborted (received): reason=$reason")
        runPairingActions(PairingEvent.PairAbortReceived(reason))
        if (activePairingMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
            runDynamicPairingActions(DynamicPairingEvent.PairAbortReceived(reason))
        }
    }

    /**
     * The operator cancelled pairing from the UI: sends `pair/abort` reason
     * `user_cancelled` for the attempt in flight. Leaves the connection open.
     */
    fun cancelPairing() {
        runPairingActions(PairingEvent.UserCancelled)
        if (activePairingMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
            runDynamicPairingActions(DynamicPairingEvent.UserCancelled)
        }
    }

    /**
     * The operator performed the "Allow pairing" gesture, ending a
     * gesture-gated wait on an escalated attempt.
     */
    fun confirmDynamicPairingGesture() {
        if (activePairingMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
            runDynamicPairingActions(DynamicPairingEvent.WindowOpened)
        }
    }

    // ========== Pairing PSK flow (item 2.5) ==========

    /** One attempt at a time, owned by the connection. */
    private val pairingFlow = PairingPskFlow()

    private var attemptTimeoutJob: Job? = null

    /**
     * The `server_id` this connection authenticated against, for the record a
     * successful pairing persists. Null before the handshake.
     */
    protected open fun currentServerId(): String? = null

    /** Where a completed pairing stores its record. */
    protected open fun trustStore(): TrustStore? = null

    /** Surfaced for the pairing UI (#225). */
    protected open fun onPaired(serverId: String) {}

    // ========== server/unpair (item 2.7) ==========

    /**
     * The PSK that admitted this connection, or null before the handshake.
     *
     * This is the single source of truth for whether the session is paired,
     * and `server/unpair` must read it rather than ask "do we hold a record for
     * this server?". The two differ in a case that matters: during a pairing
     * handshake we may well hold a record for that same server from a previous
     * pairing, while the current session was admitted by the Pairing PSK and is
     * unpaired. Deciding on the record would delete it.
     *
     * A re-handshake replaces it at the key swap, so an unpair arriving just
     * after a promotion sees the post-swap value.
     */
    protected open fun matchedPsk(): Psk? = null

    /** The record this connection dropped. Drives the UI and reconnect policy. */
    protected open fun onUnpaired(pskId: String, serverId: String?) {}

    /**
     * Close the connection once the goodbye is on the wire.
     *
     * Separate from an ordinary close because the frame must actually be
     * flushed first; see [handleServerUnpair].
     */
    protected open fun closeConnectionAfterFlush() {}

    /** One unpair per connection; a repeat is a no-op. */
    private var unpairHandled = false

    /**
     * `messaging.md#server--client-serverunpair`: "Remove the matched pairing
     * record, send `client/goodbye` reason `'unpaired'`, and close the
     * connection."
     *
     * Takes no payload: the message has no fields, and ignoring whatever
     * arrives is exactly the required tolerance for unknown ones.
     */
    protected fun handleServerUnpair() {
        val matched = matchedPsk()

        // "If the session is unpaired, ignore the message and continue
        // unchanged." Not an error, and specifically not a close: the
        // connection carries on.
        if (matched == null || matched.category != PskCategory.LONG_TERM) {
            Log.i(tag, "server/unpair on an unpaired session (psk=${matched?.category}) - ignoring")
            return
        }

        if (unpairHandled) {
            Log.d(tag, "server/unpair already handled on this connection - ignoring")
            return
        }

        val store = trustStore()
        if (store == null) {
            Log.e(tag, "server/unpair with no trust store - cannot drop the record")
            return
        }
        // Durable before we say a word. A crash between the two must not
        // leave a record the server has already forgotten, and telling the
        // server we unpaired while the record survives is worse still: the
        // device keeps authenticating with a credential that is gone, and
        // it looks like a working pairing until the next handshake fails.
        try {
            store.removeRecord(matched.pskId)
        } catch (e: Exception) {
            Log.e(tag, "server/unpair could not remove record ${matched.pskId}", e)
            return
        }
        Log.i(tag, "server/unpair removed record ${matched.pskId} for ${matched.serverId}")

        unpairHandled = true
        onUnpaired(matched.pskId, matched.serverId)

        // The goodbye has to reach the wire before the close, and on the
        // encrypted path sending is a suspending encrypt. Sequencing them in
        // one coroutine is what makes "send then close" true rather than
        // merely written in that order.
        getCoroutineScope().launch {
            sendProtocolMessageAwaiting(MessageBuilder.buildGoodbye(GoodbyeReason.UNPAIRED))
            closeConnectionAfterFlush()
        }
    }

    protected fun handleServerPairFinalize() {
        if (pairingAborted) {
            Log.d(tag, "Discarding server/pair-finalize after pair/abort")
            return
        }
        runPairingActions(PairingEvent.ServerPairFinalize)
        if (activePairingMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
            runDynamicPairingActions(DynamicPairingEvent.ServerPairFinalize)
        }
    }

    /** Called by the connection when the socket goes away mid-attempt. */
    fun onConnectionClosedForPairing() {
        runPairingActions(PairingEvent.ConnectionClosed)
        if (activePairingMethod == PairMethod.DYNAMIC_PAIRING_CODE) {
            runDynamicPairingActions(DynamicPairingEvent.ConnectionClosed)
        }
    }

    private fun runPairingActions(event: PairingEvent) {
        for (action in pairingFlow.onEvent(event)) {
            when (action) {
                is PairingAction.SendPairInit ->
                    sendProtocolMessage(MessageBuilder.buildClientPairInit(action.pairingIndex))

                is PairingAction.SendPairFinalize -> {
                    // Metadata only. The payload carries the long-term PSK in
                    // the clear (inside the encrypted channel), so logging the
                    // message itself would put a live credential in logcat.
                    Log.i(tag, "Pairing: sending client/pair-finalize (32-byte PSK)")
                    sendProtocolMessage(
                        MessageBuilder.buildClientPairFinalize(action.longTermPsk)
                    )
                }

                is PairingAction.SendPairAbort -> sendPairAbort(action.reason)

                is PairingAction.PersistRecord -> persistPairingRecord(action.psk)

                PairingAction.StartAttemptTimeout -> {
                    attemptTimeoutJob?.cancel()
                    attemptTimeoutJob = getCoroutineScope().launch {
                        delay(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS)
                        Log.w(tag, "Pairing attempt timed out")
                        runPairingActions(PairingEvent.AttemptTimeout)
                    }
                }

                PairingAction.ClearAttemptTimeout -> {
                    attemptTimeoutJob?.cancel()
                    attemptTimeoutJob = null
                }
            }
        }
    }

    private fun persistPairingRecord(psk: ByteArray) {
        val store = trustStore()
        val serverId = currentServerId()
        if (store == null || serverId == null) {
            // The server has already stored its half, so this is not
            // recoverable by retrying - say so loudly rather than leaving a
            // half-pairing that fails as `unauthorized` on the next connect.
            Log.e(tag, "Paired, but there is nowhere to store the record")
            return
        }
        // Replaces any record already held for this server.
        when (val result = store.addRecord(psk, serverId)) {
            is TrustStore.AddRecordResult.Ok -> {
                Log.i(tag, "Paired with $serverId (psk_id=${result.record.pskId})")
                onPaired(serverId)
            }
            // Astronomically unlikely, and not worth a silent retry: the server
            // holds a PSK we cannot store, so the pairing is already broken.
            TrustStore.AddRecordResult.AlreadyExists ->
                Log.e(tag, "Cannot store pairing record: psk_id already claimed")
            TrustStore.AddRecordResult.Invalid ->
                Log.e(tag, "Cannot store pairing record: PSK rejected as invalid")
            TrustStore.AddRecordResult.StorageFailed ->
                // The one failure a user could actually act on, so it names the
                // cause rather than the symptom.
                Log.e(tag, "Cannot store pairing record: the write did not persist")
        }
    }

    // ========== Dynamic Pairing Code flow (item 3.2) ==========

    /**
     * The method name of the pairing attempt currently in flight on this
     * connection ("pairing_psk" or "dynamic_pairing_code"), or null when none
     * is. Set only by [handleServerActivate], which is the sole place a new
     * attempt starts or an old one is superseded - matching how both flows
     * themselves only truly reset on the next activation.
     *
     * This is what keeps `server/pair-init`, `server/pair-auth` and
     * `server/pair-confirm` (exclusive to the dynamic method) from being fed
     * to a Pairing-PSK attempt, and what keeps `server/pair-finalize` from
     * being fed to a dynamic attempt that never asked for it.
     */
    private var activePairingMethod: String? = null

    /** One attempt at a time, lazily built once a Noise session exists to bind it to. */
    private var dynamicPairingFlow: DynamicPairingCodeFlow? = null

    private var dynamicAttemptTimeoutJob: Job? = null

    /**
     * Pairing activations received since the last Noise handshake: any
     * method, admissible or not. Reset on every fresh handshake in
     * [installEncryptedTransport] and on every re-handshake in
     * [resetForRehandshake].
     */
    private var pairingIndex = 0

    /**
     * True from the moment this client sends `pair/abort` until the next
     * `server/activate`. Pairing messages the server sent before it saw the
     * abort are discarded silently while it is set, not treated as a sequence
     * violation.
     */
    private var pairingAborted = false

    /** Backing store for the method's brute-force counter. Null on paths that never offer it. */
    protected open fun pairingCounterStore(): PairingCounterStore? = null

    /**
     * The current session's Noise handshake hash `h`, needed to derive the
     * CPace `sid`. Null before a handshake completes.
     */
    protected open fun currentHandshakeHash(): ByteArray? = null

    /**
     * The negotiated AEAD suite, needed to wrap `client/pair-confirm`'s
     * `nonce_B` opening and `client/pair-finalize`'s PSK. Null before a
     * handshake completes.
     */
    protected open fun negotiatedCipherSuite(): NoiseCipherSuite? = null

    /** Surfaced for the pairing UI: show this code to the operator. */
    protected open fun onDynamicPairingCodeEmitted(code: String) {}

    /** Surfaced for the pairing UI: stop showing a code (attempt ended, one way or another). */
    protected open fun onDynamicPairingCodeCleared() {}

    /** Surfaced for the pairing UI: the attempt is gesture-gated; show the "Allow pairing" prompt. */
    protected open fun onDynamicPairingGestureRequested() {}

    /**
     * Whether a message exclusive to the dynamic method may be acted on:
     * discarded silently after our own `pair/abort`, a protocol error outside
     * a dynamic attempt.
     */
    private fun dynamicPairingMessageExpected(type: String): Boolean {
        if (pairingAborted) {
            Log.d(tag, "Discarding $type after pair/abort")
            return false
        }
        if (activePairingMethod != PairMethod.DYNAMIC_PAIRING_CODE) {
            onProtocolFailure("$type received outside a dynamic pairing attempt")
            return false
        }
        return true
    }

    private fun handleServerPairInit(payload: JsonObject?) {
        if (!dynamicPairingMessageExpected("server/pair-init")) return
        val nonceA = MessageParser.parseServerPairInit(payload)
        if (nonceA == null) {
            onProtocolFailure("malformed server/pair-init")
            return
        }
        runDynamicPairingActions(DynamicPairingEvent.ServerPairInit(nonceA))
    }

    private fun handleServerPairAuth(payload: JsonObject?) {
        if (!dynamicPairingMessageExpected("server/pair-auth")) return
        val ya = MessageParser.parseServerPairAuth(payload)
        if (ya == null) {
            onProtocolFailure("malformed server/pair-auth")
            return
        }
        runDynamicPairingActions(DynamicPairingEvent.ServerPairAuth(ya))
    }

    private fun handleServerPairConfirm(payload: JsonObject?) {
        if (!dynamicPairingMessageExpected("server/pair-confirm")) return
        val ta = MessageParser.parseServerPairConfirm(payload)
        if (ta == null) {
            onProtocolFailure("malformed server/pair-confirm")
            return
        }
        runDynamicPairingActions(DynamicPairingEvent.ServerPairConfirm(ta))
    }

    private fun runDynamicPairingActions(event: DynamicPairingEvent) {
        val flow = dynamicPairingFlow ?: run {
            // Only worth building the flow for an event that starts or
            // advances an attempt. A terminal event with no flow yet has
            // nothing to end - most connections never touch this method at
            // all, and treating every ordinary non-pairing activation as a
            // reason to construct one (and fail loudly if the session context
            // is not ready) would be wrong for all of them.
            if (event !is DynamicPairingEvent.PairingActivation) return
            val hash = currentHandshakeHash()
            val store = pairingCounterStore()
            val suite = negotiatedCipherSuite()
            if (hash == null || store == null || suite == null) {
                onProtocolFailure("dynamic pairing activation with no handshake context")
                return
            }
            DynamicPairingCodeFlow(hash, PairingFailureCounter(store), suite)
                .also { dynamicPairingFlow = it }
        }

        for (action in flow.onEvent(event)) {
            when (action) {
                is DynamicPairingAction.SendPairInit ->
                    sendProtocolMessage(
                        MessageBuilder.buildClientPairInit(action.pairingIndex, action.commitB)
                    )

                is DynamicPairingAction.SendPairPending ->
                    sendProtocolMessage(MessageBuilder.buildClientPairPending(action.pairingIndex))

                DynamicPairingAction.RequestGesture -> onDynamicPairingGestureRequested()

                DynamicPairingAction.StartAttemptTimeout -> {
                    dynamicAttemptTimeoutJob?.cancel()
                    dynamicAttemptTimeoutJob = getCoroutineScope().launch {
                        delay(SendSpinProtocol.PAIR_ATTEMPT_TIMEOUT_MS)
                        Log.w(tag, "Dynamic pairing attempt timed out")
                        runDynamicPairingActions(DynamicPairingEvent.AttemptTimeout)
                    }
                }

                is DynamicPairingAction.EmitPairingCode -> onDynamicPairingCodeEmitted(action.code)

                is DynamicPairingAction.SendPairAuth ->
                    sendProtocolMessage(MessageBuilder.buildClientPairAuth(action.yb))

                is DynamicPairingAction.SendPairAbort -> sendPairAbort(action.reason)

                is DynamicPairingAction.SendPairConfirm ->
                    sendProtocolMessage(
                        MessageBuilder.buildClientPairConfirm(action.tb, action.wrappedNonceB)
                    )

                is DynamicPairingAction.SendPairFinalize -> {
                    // Metadata only - the payload carries the wrapped PSK.
                    Log.i(tag, "Dynamic pairing: sending client/pair-finalize (wrapped PSK)")
                    sendProtocolMessage(
                        MessageBuilder.buildClientPairFinalizeWrapped(action.wrappedPsk)
                    )
                }

                is DynamicPairingAction.PersistRecord -> persistPairingRecord(action.psk)

                DynamicPairingAction.StopEmittingCode -> {
                    // No separate "clear the timer" action exists on this flow
                    // (unlike PairingAction.ClearAttemptTimeout): StopEmittingCode
                    // is emitted on every exit path - success, abort, mismatch,
                    // timeout, and a superseding activation - so it doubles as
                    // that signal here. Without this, a completed pairing's timer
                    // would still fire minutes later and abort a connection
                    // that already succeeded.
                    dynamicAttemptTimeoutJob?.cancel()
                    dynamicAttemptTimeoutJob = null
                    onDynamicPairingCodeCleared()
                }

                // "Close the WebSocket with no application-level message and
                // persist nothing." onProtocolFailure is the handler's
                // existing silent-close path: every other protocol-level
                // failure in this file (a malformed server/activate, a
                // handshake message out of phase) already routes through it,
                // and it sends nothing itself - only the caller's log line.
                DynamicPairingAction.ProtocolError ->
                    onProtocolFailure("dynamic pairing protocol error")
            }
        }
    }

    protected fun handleServerTime(payload: JsonObject?) {
        val clientReceived = System.nanoTime() / 1000
        val measurement = MessageParser.parseServerTime(payload, clientReceived)

        if (measurement != null) {
            timeSyncManager?.onServerTime(measurement)
        }
    }

    /**
     * `server/state`: "Every message MUST carry the full state of each role
     * object it includes. Omitting a role object leaves that role's state
     * unchanged and any pending scheduled update in place."
     *
     * So an included role object replaces that role's state outright. A field
     * it does not carry is gone: a `metadata` object with only a `timestamp`
     * is an empty track, and one without `progress` has no position.
     */
    protected fun handleServerState(payload: JsonObject?) {
        val (metadata, state, controller) = MessageParser.parseServerState(payload)

        if (metadata != null) scheduleMetadata(metadata)

        if (state != null && state != lastPlaybackState) {
            lastPlaybackState = state
            onPlaybackStateChanged(state)
        }

        if (controller != null && controller != currentControllerState) {
            currentControllerState = controller
            onControllerStateUpdate(controller)
        }
    }

    /**
     * roles/metadata/v1.md, "Scheduled metadata updates": "Clients keep a
     * current state plus at most one pending update." The current state is
     * whatever [onMetadataUpdate] last delivered.
     *
     * "A message whose `timestamp`, translated to the local clock via the
     * time filter (current best estimate, no waiting for convergence), is
     * still in the future becomes the pending update, replacing any held one,
     * and is applied when that moment is reached. A message whose translated
     * timestamp is in the past or present is applied immediately and discards
     * any held pending update." Before the filter has any estimate there is
     * nothing to translate with, so the update is applied at once, which the
     * spec allows ("Clients MAY show the pending update early").
     */
    private fun scheduleMetadata(metadata: TrackMetadata) = synchronized(metadataLock) {
        discardPendingMetadata()
        val filter = getTimeFilter()
        val timestamp = metadata.timestamp
        val delayMicros = if (timestamp == null || !filter.isReady) {
            0L
        } else {
            filter.serverToClient(timestamp) - System.nanoTime() / 1000
        }
        if (delayMicros <= 0) {
            // Delivered even when nothing changed: progress extrapolation
            // needs a fresh anchor on every message.
            onMetadataUpdate(metadata)
            return@synchronized
        }
        Log.d(tag, "Metadata pending for ${delayMicros / 1000}ms")
        pendingMetadata = getCoroutineScope().launch {
            delay(delayMicros / 1000)
            // A cancel takes the same lock, so an update discarded while this
            // was waiting for it is no longer active here.
            synchronized(metadataLock) {
                if (isActive) {
                    pendingMetadata = null
                    onMetadataUpdate(metadata)
                }
            }
        }
    }

    private fun discardPendingMetadata() {
        pendingMetadata?.cancel()
        pendingMetadata = null
    }

    /**
     * Forget the `server/state` roles, on a new connection or on leaving one,
     * so a metadata update still pending cannot come due afterwards. Does not
     * touch what is on display.
     */
    protected fun resetServerState() {
        synchronized(metadataLock) { discardPendingMetadata() }
        currentControllerState = null
    }

    protected fun handleServerCommand(payload: JsonObject?) {
        Log.i(tag, "[cmd-trace] T1 handleServerCommand ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        when (val result = MessageParser.parseServerCommand(payload)) {
            is ServerCommandResult.Volume -> {
                Log.d(tag, "Server command: set volume to ${result.volume}%")
                currentVolume = result.volume
                onVolumeCommand(result.volume)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Mute -> {
                Log.d(tag, "Server command: set mute to ${result.muted}")
                currentMuted = result.muted
                onMuteCommand(result.muted)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.SetOutputDelay -> {
                Log.i(tag, "Server command: set output delay to ${result.delayMs}ms")
                // This is NOT a sync-offset correction, which is how it used to
                // be applied. roles/player/v1.md defines it as delay BEYOND the
                // audio port - an external amplifier or powered speaker - which
                // sits on top of the hardware latency the client compensates
                // itself. It must also survive a reboot.
                getTimeFilter().setOutputDelayMs(result.delayMs.toDouble())
                UserSettings.setOutputDelayMs(result.delayMs)
                sendPlayerStateUpdate()
            }
            is ServerCommandResult.Unknown -> {
                Log.d(tag, "Unknown player command: ${result.command}")
            }
            null -> { /* No player command in payload */ }
        }
    }

    protected fun handleGroupUpdate(payload: JsonObject?) {
        val info = MessageParser.parseGroupUpdate(payload)
        if (info != null) {
            lastGroupInfo = info
            Log.v(tag, "group/update: id=${info.groupId}, name=${info.groupName}, state=${info.playbackState}")
            onGroupUpdate(info)
        }
    }

    protected fun handleStreamStart(payload: JsonObject?) {
        // A stream/start carries an object per role it starts or reconfigures,
        // so one for artwork alone has no `player` object.
        (payload?.get("artwork") as? JsonObject)?.let { handleArtworkStreamStart(it) }

        val config = MessageParser.parseStreamStart(payload)
        if (config == null) return

        val formatChanged = _streamActive && config != _currentStreamConfig
        if (_streamActive) {
            if (formatChanged) {
                Log.i(tag, "Stream format changed: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth} - reconfiguring pipeline")
            } else {
                Log.d(tag, "Stream restart (same format): codec=${config.codec}, rate=${config.sampleRate}")
            }
        } else {
            Log.i(tag, "Stream started: codec=${config.codec}, rate=${config.sampleRate}, ch=${config.channels}, bits=${config.bitDepth}, header=${config.codecHeader?.size ?: 0} bytes")
        }

        _streamActive = true
        _currentStreamConfig = config
        onStreamStart(config)

        if (!outputStarted) {
            // The pipeline is running from here on: later starts need less lead.
            outputStarted = true
            sendPlayerStateUpdate()
        }
    }

    protected fun handleStreamClear(payload: JsonObject?) {
        Log.i(tag, "[cmd-trace] T1 handleStreamClear ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")

        // The dispatcher used to call this with no payload at all, so every
        // clear was treated as global: a visualizer-only clear reset the
        // decoder and discarded the whole chunk queue mid-track. The field is
        // unversioned, as in stream/end - "which roles to clear: 'player',
        // 'visualizer', or both. If omitted, clears both roles".
        val roles = payload?.get("roles")?.jsonArray?.map { it.jsonPrimitive.content }
        if (roles != null && roles.none {
                SendSpinProtocol.isStreamRole(it, SendSpinProtocol.StreamRoles.PLAYER)
            }
        ) {
            Log.d(tag, "Stream clear for non-player roles: $roles - leaving audio alone")
            return
        }

        Log.v(tag, "Stream clear - flushing audio buffers (roles=${roles ?: "all"})")
        onStreamClear()
    }

    protected fun handleStreamEnd(payload: JsonObject?) {
        Log.i(tag, "[cmd-trace] T1 handleStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        val rolesArray = payload?.get("roles")?.jsonArray
        val roles = rolesArray?.map { it.jsonPrimitive.content }

        // messaging.md writes this field UNVERSIONED - "roles to end streams
        // for ('player', 'artwork', 'visualizer')" - while active_roles is
        // versioned. Comparing against "player@v1" here matched nothing, so
        // every stream/end carrying a roles array was silently dropped and the
        // stream never ended.
        if (artworkStreamActive && (roles == null || roles.any {
                SendSpinProtocol.isStreamRole(it, SendSpinProtocol.StreamRoles.ARTWORK)
            })
        ) {
            endArtworkStream()
        }

        if (roles != null && roles.none {
                SendSpinProtocol.isStreamRole(it, SendSpinProtocol.StreamRoles.PLAYER)
            }
        ) {
            Log.d(tag, "Stream end for non-player roles: $roles - ignoring")
            return
        }

        Log.i(tag, "Stream end - server terminated playback (roles=${roles ?: "all"})")
        endPlayerStream()
    }

    /** Stop the player's output and clear its buffers; the stream is over. */
    private fun endPlayerStream() {
        _streamActive = false
        _currentStreamConfig = null
        onStreamEnd()
    }

    /**
     * "On stream/end for the artwork role, clients MUST clear the current
     * image and discard any pending image."
     */
    private fun endArtworkStream() {
        Log.i(tag, "Artwork stream ended - clearing artwork")
        synchronized(artworkLock) {
            resetArtworkStream()
            onArtwork(0, ByteArray(0))
        }
    }

    protected fun handleClientSyncOffset(payload: JsonObject?) {
        val result = MessageParser.parseSyncOffset(payload)
        if (result == null) {
            Log.w(tag, "client/sync_offset: missing or invalid payload")
            return
        }

        Log.i(tag, "client/sync_offset: offset=${result.offsetMs}ms from ${result.source}")

        val clampedOffset = result.offsetMs.coerceIn(-5000.0, 5000.0)
        if (clampedOffset != result.offsetMs) {
            Log.w(tag, "client/sync_offset: clamped from ${result.offsetMs}ms to ${clampedOffset}ms")
        }

        getTimeFilter().setServerSyncOffsetMs(clampedOffset)
        Log.d(tag, "client/sync_offset: static delay set to ${clampedOffset}ms")

        onSyncOffsetApplied(clampedOffset, result.source)
    }

    // ========== Binary Message Handling ==========

    /**
     * Handle binary message from the transport.
     */
    protected fun handleBinaryMessage(bytes: ByteArray) {
        val codec = wireCodec
        if (codec == null) {
            // A binary frame before the Noise handshake completed. There is
            // no channel to decrypt it with, and no cleartext binary dialect.
            Log.w(tag, "Dropping binary frame: no encrypted channel")
            return
        }
        when (val decoded = codec.decode(bytes)) {
            is NoiseWireCodec.Decoded.Json -> handleTextMessage(decoded.text)
            is NoiseWireCodec.Decoded.Typed ->
                if (decoded.type - SendSpinProtocol.BinaryType.ARTWORK_BASE in 0..3) {
                    handleArtworkMessage(decoded.type, decoded.body)
                } else {
                    BinaryMessageParser.parse(decoded.type, decoded.body)
                        ?.let { dispatchBinaryMessage(it) }
                }
            is NoiseWireCodec.Decoded.Buffered -> {
                // A fragment landed and the message is still incomplete. The
                // codec holds the partial buffer; nothing to dispatch until the
                // last fragment arrives.
            }
            is NoiseWireCodec.Decoded.ProtocolError ->
                onProtocolFailure(decoded.reason)
        }
    }

    /**
     * Dispatch parsed binary message to appropriate handler.
     */
    private fun dispatchBinaryMessage(message: BinaryMessageParser.BinaryMessage) {
        when (message) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                // Spec: binary messages should be rejected if there is no
                // active stream (e.g. chunks in flight after stream/end).
                if (!_streamActive) {
                    Log.v(tag, "Dropping audio chunk: no active stream")
                    return
                }
                measureChunkDelay(message)
                onAudioChunk(message.timestampMicros, message.payload)
            }
        }
    }

    // ========== Artwork (roles/artwork/v1.md) ==========

    private fun handleArtworkStreamStart(artwork: JsonObject) {
        val config = (artwork["channels"] as? JsonArray)?.getOrNull(0)
        synchronized(artworkLock) {
            artworkStreamActive = true
            // "A stream/start that changes a channel's configuration likewise
            // discards that channel's pending image."
            if (config != artworkChannelConfig) discardPendingArtwork()
            artworkChannelConfig = config
        }
    }

    /**
     * Forget the artwork stream, on a new connection or on leaving one, so an
     * image still pending cannot come due afterwards. Does not touch the image
     * on display.
     */
    protected fun resetArtworkStream() = synchronized(artworkLock) {
        artworkStreamActive = false
        artworkChannelConfig = null
        artworkReceiver.reset()
        discardPendingArtwork()
    }

    private fun discardPendingArtwork() {
        pendingArtwork?.cancel()
        pendingArtwork = null
    }

    private fun handleArtworkMessage(type: Int, body: ByteArray) {
        val result = synchronized(artworkLock) {
            if (artworkStreamActive) {
                artworkReceiver.accept(type, body)
            } else {
                // "Servers MUST NOT send artwork messages outside an active
                // artwork stream." A malformed message is a protocol error
                // regardless; the sequence rules only apply within a stream,
                // so a well-formed stray is dropped.
                artworkReceiver.malformed(body)?.let { ArtworkReceiver.Result.ProtocolError(it) }
                    ?: ArtworkReceiver.Result.None
            }
        }
        when (result) {
            is ArtworkReceiver.Result.None -> {}
            is ArtworkReceiver.Result.ProtocolError -> onProtocolFailure(result.reason)
            is ArtworkReceiver.Result.Discard ->
                if (result.channel == 0) synchronized(artworkLock) { discardPendingArtwork() }
            is ArtworkReceiver.Result.Image ->
                if (result.channel == 0) scheduleArtwork(result.timestampMicros, result.data)
        }
    }

    /**
     * Make [image] the pending image, and the current one once its timestamp
     * is reached: "translated to the local clock via the time filter (current
     * best estimate, no waiting for convergence) ... artwork is never dropped
     * for lateness". Before the filter has any estimate there is nothing to
     * translate with, so the image is shown at once, which the spec allows
     * ("or show it early").
     */
    private fun scheduleArtwork(timestampMicros: Long, image: ByteArray) = synchronized(artworkLock) {
        discardPendingArtwork()
        val filter = getTimeFilter()
        val delayMicros = filter.serverToClient(timestampMicros) - System.nanoTime() / 1000
        if (!filter.isReady || delayMicros <= 0) {
            onArtwork(0, image)
            return@synchronized
        }
        Log.d(tag, "Artwork (${image.size} bytes) pending for ${delayMicros / 1000}ms")
        pendingArtwork = getCoroutineScope().launch {
            delay(delayMicros / 1000)
            // A cancel takes the same lock, so an image discarded while this
            // was waiting for it is no longer active here.
            synchronized(artworkLock) {
                if (isActive) {
                    pendingArtwork = null
                    onArtwork(0, image)
                }
            }
        }
    }
}
