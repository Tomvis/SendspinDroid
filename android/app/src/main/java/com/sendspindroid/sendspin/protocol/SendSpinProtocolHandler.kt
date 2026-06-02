package com.sendspindroid.sendspin.protocol

import android.util.Log
import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope

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
    // Volatile: written from the WebSocket dispatcher thread (via
    // handleServerCommand) and from the Main thread (via setVolume/setMuted).
    // Without volatility, a write from one thread may not be observed by the
    // other when sendPlayerStateUpdate composes the wire message.
    @Volatile
    protected var currentVolume: Int = 100
    @Volatile
    protected var currentMuted: Boolean = false
    // Per Sendspin spec, a client that has not yet synchronized to the
    // server timeline reports "error". Updated by [evaluateAndPublishSyncState].
    // Volatile + guarded by [syncStateLock] for writes from setSyncState /
    // evaluateAndPublishSyncState which can fire from different threads.
    @Volatile
    protected var currentSyncState: String = "error"

    private val syncStateLock = Any()
    private var hasEverConverged: Boolean = false
    private var lastPublishedMute: Boolean = false

    // Stream active tracking (mirrors CLI _stream_active)
    private var _streamActive = false
    private var _currentStreamConfig: StreamConfig? = null

    // Last received values for change detection (avoids unnecessary UI recomposition)
    private var lastMetadata: TrackMetadata? = null
    private var lastPlaybackState: String? = null
    private var lastGroupInfo: GroupInfo? = null
    private var lastColorState: ColorState? = null
    private var lastControllerState: ControllerState? = null
    // Server identity of the most recent handshake. Used to decide whether the
    // diff-merge anchors (lastMetadata, lastControllerState) are still valid:
    // they must be cleared when switching to a different server, but preserved
    // across a reconnect to the same server (see handleServerHello).
    private var lastServerId: String? = null

    // Time sync manager (lazy initialized by subclass)
    protected var timeSyncManager: TimeSyncManager? = null

    // ========== Abstract Transport Methods ==========

    /**
     * Send a text message over the WebSocket.
     */
    protected abstract fun sendTextMessage(text: String)

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
     * Called when group-level controller state arrives via `server/state`.
     * Reports the application's supported MediaCommand values and the
     * group's current volume/mute. Only fires for clients that advertise the
     * controller@v1 role.
     *
     * Default no-op for handlers that don't expose group-level controls.
     */
    protected open fun onControllerStateUpdate(state: ControllerState) {}

    /**
     * Called when artwork-derived color state arrives via `server/state.color`.
     * Only fires for clients that advertise the `color@v1` role. Idempotent
     * dedup is done by [handleServerState] -- only fires on changes.
     *
     * Default no-op for handlers that don't render color-based theming.
     */
    protected open fun onColorStateUpdate(state: ColorState) {}

    /**
     * Called when the artwork color stream ends and any prior color state
     * should be cleared.
     *
     * Default no-op for handlers that don't render color-based theming.
     */
    protected open fun onColorStateCleared() {}

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
     * Called when artwork is received.
     */
    protected abstract fun onArtwork(channel: Int, payload: ByteArray)

    /**
     * Called when sync offset is received from GroupSync.
     */
    protected abstract fun onSyncOffsetApplied(offsetMs: Double, source: String)

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
     * Send client/hello message to start handshake.
     *
     * Buffer capacity is computed from the format list and target duration
     * so the wire-byte cap scales with the highest PCM bitrate we advertise.
     */
    protected fun sendClientHello() {
        val formats = getSupportedFormats()
        val bufferDuration = if (isLowMemoryMode()) {
            SendSpinProtocol.Buffer.DURATION_LOW_MEM_SEC
        } else {
            SendSpinProtocol.Buffer.DURATION_NORMAL_SEC
        }
        val bufferCapacity = MessageBuilder.calculateBufferCapacity(formats, bufferDuration)
        val text = MessageBuilder.buildClientHello(
            clientId = getClientId(),
            deviceName = getDeviceName(),
            bufferCapacity = bufferCapacity,
            manufacturer = getManufacturer(),
            supportedFormats = formats
        )
        sendTextMessage(text)
        Log.d(tag, "Sent client/hello: ${text.take(500)}")
    }

    /**
     * Send client/time message for clock synchronization.
     */
    protected fun sendClientTime() {
        val clientTransmitted = System.nanoTime() / 1000 // Convert to microseconds
        sendTextMessage(MessageBuilder.buildClientTime(clientTransmitted))
    }

    /**
     * Send goodbye message before disconnecting.
     */
    protected fun sendGoodbye(reason: String) {
        if (!handshakeComplete) return
        sendTextMessage(MessageBuilder.buildGoodbye(reason))
    }

    /**
     * Send player state update (volume/muted/sync state).
     */
    protected fun sendPlayerStateUpdate() {
        // The filter tracks signed-Double ms (negative user offsets are valid
        // internally). The wire requires unsigned int [0, 5000] — round and
        // clamp here; buildPlayerState clamps again as a defence in depth.
        val delayMs = getTimeFilter().staticDelayMs
            .let { kotlin.math.round(it).toInt() }
            .coerceIn(SendSpinProtocol.StaticDelay.MIN_MS, SendSpinProtocol.StaticDelay.MAX_MS)
        sendTextMessage(MessageBuilder.buildPlayerState(currentVolume, currentMuted, currentSyncState, delayMs))
    }

    /**
     * Public hook for code outside the protocol handler (e.g.
     * [OutputLatencyEstimator] via [SyncAudioPlayer]) to push a fresh
     * `client/state` to the server, for example after auto-measured
     * `static_delay_ms` converges.
     */
    fun sendClientStateSnapshot() {
        if (!handshakeComplete) return
        sendPlayerStateUpdate()
    }

    /**
     * Set sync state and notify server.
     *
     * Per spec: report "synchronized" when locked to server timeline,
     * "error" when unable to maintain sync, or "external_source" when audio
     * output has been taken by another app and Sendspin cannot participate.
     */
    fun setSyncState(syncState: String) {
        when (syncState) {
            SendSpinProtocol.ClientState.SYNCHRONIZED,
            SendSpinProtocol.ClientState.ERROR,
            SendSpinProtocol.ClientState.EXTERNAL_SOURCE -> Unit
            else -> {
                Log.w(tag, "Invalid sync state: $syncState")
                return
            }
        }
        // Take syncStateLock so the compare-and-set is atomic relative to
        // evaluateAndPublishSyncState (which writes the same field from
        // inside the lock). Without the lock, an "external_source" call from
        // the audio-focus path racing with the time-filter evaluator could
        // lose the transition.
        val shouldSend = synchronized(syncStateLock) {
            if (currentSyncState != syncState) {
                currentSyncState = syncState
                Log.d(tag, "Sync state changed to: $syncState")
                handshakeComplete
            } else {
                false
            }
        }
        if (shouldSend) {
            sendPlayerStateUpdate()
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
     * Send a media command (play, pause, next, previous, switch).
     */
    fun sendCommand(command: String) {
        sendTextMessage(MessageBuilder.buildCommand(command))
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

    // ========== Message Handling ==========

    /**
     * Handle incoming text (JSON) message.
     *
     * Envelope decoding goes through Moshi's generic Map adapter (no
     * kotlinx.serialization on this path). The envelope is `{type, payload}`;
     * the payload value is forwarded to MessageParser as a raw Moshi JSON
     * value (typically Map<String, Any?>), and MessageParser feeds it to the
     * appropriate KSP-generated wire adapter via fromJsonValue.
     */
    protected fun handleTextMessage(text: String) {
        Log.d(tag, "Received: ${text.take(500)}")

        try {
            val (type, payload) = MessageParser.parseEnvelope(text) ?: return

            when (type) {
                SendSpinProtocol.MessageType.SERVER_HELLO -> handleServerHello(payload)
                SendSpinProtocol.MessageType.SERVER_TIME -> handleServerTime(payload)
                SendSpinProtocol.MessageType.SERVER_STATE -> handleServerState(payload)
                SendSpinProtocol.MessageType.SERVER_COMMAND -> handleServerCommand(payload)
                SendSpinProtocol.MessageType.GROUP_UPDATE -> handleGroupUpdate(payload)
                SendSpinProtocol.MessageType.STREAM_START -> handleStreamStart(payload)
                SendSpinProtocol.MessageType.STREAM_END -> handleStreamEnd(payload)
                SendSpinProtocol.MessageType.STREAM_CLEAR -> handleStreamClear(payload)
                SendSpinProtocol.MessageType.CLIENT_SYNC_OFFSET -> handleClientSyncOffset(payload)
                else -> Log.d(tag, "Unhandled message type: $type")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: ${text.take(100)}", e)
        }
    }

    protected open fun handleServerHello(payload: Any?) {
        val result = MessageParser.parseServerHello(payload, "Unknown")
        if (result == null) {
            Log.e(tag, "Failed to parse server/hello")
            return
        }

        Log.i(tag, "server/hello: name=${result.serverName}, id=${result.serverId}, reason=${result.connectionReason}")
        Log.d(tag, "Active roles: ${result.activeRoles}")

        handshakeComplete = true

        // Clear cached values that gate on equality-dedup so the first
        // post-handshake message always propagates. lastColorState MUST be
        // cleared: on reconnect or server switch, the dedup check
        // `colorState != lastColorState` in handleServerState would otherwise
        // suppress the first color update from the new server (if it happened
        // to be byte-identical) or, worse, leave the previous server's palette
        // in place when the new server does not advertise color@v1 at all.
        //
        // Preserve lastMetadata / lastControllerState ONLY across reconnects
        // to the same server. The Sendspin server/state stream is diff-style:
        // after a reconnect MA may send a progress-only or volume-only update
        // first, and clearing the anchors would collapse every Absent field
        // to "" / 0, sending the NowPlaying screen to standby mid-playback.
        // But on a *different* server, preserving these would bleed the prior
        // server's title/artist/artwork into the new session until the new
        // server happens to send a fully-populated update.
        //
        // An empty incomingServerId means the server omitted server_id from its
        // hello (the wire field is optional; the parser maps absent/null to "").
        // We cannot prove it is a different server, so preserve the anchors --
        // clearing them would silently reintroduce the standby-mid-playback bug
        // for any server that does not populate server_id.
        val incomingServerId = result.serverId
        // Kotlin == on String? is null-safe: null == "x" is false without a
        // separate null check on lastServerId.
        val sameServer = incomingServerId.isEmpty() || lastServerId == incomingServerId
        if (incomingServerId.isEmpty() && lastServerId != null) {
            // Server omitted server_id; we are preserving the prior server's
            // metadata anchors. Surface this so a "wrong metadata after server
            // switch" report has a signal in field logs.
            Log.w(tag, "server/hello: server_id missing - preserving metadata anchors from lastServerId=$lastServerId")
        }
        if (!sameServer) {
            lastMetadata = null
            lastControllerState = null
        }
        lastServerId = incomingServerId.ifEmpty { lastServerId }

        _streamActive = false
        _currentStreamConfig = null
        lastPlaybackState = null
        lastGroupInfo = null
        if (lastColorState != null) {
            lastColorState = null
            onColorStateCleared()
        }

        onHandshakeComplete(result.serverName, result.serverId)

        sendPlayerStateUpdate()
        startTimeSync()
    }

    protected fun handleServerTime(payload: Any?) {
        val clientReceived = System.nanoTime() / 1000
        val measurement = MessageParser.parseServerTime(payload, clientReceived)

        if (measurement != null) {
            timeSyncManager?.onServerTime(measurement)
        }
    }

    protected fun handleServerState(payload: Any?) {
        // Pass the previous metadata + controller so the parser can merge
        // partial updates. The server only sends fields that changed; without
        // merging we'd wipe artist/album/artwork/progress on every title-only
        // or progress-only update. The parser owns the tri-state distinction
        // for repeat/shuffle (Absent inherits, Present(null) explicit clear,
        // Present(v) wins) and for required fields (volume/muted/cmds inherit
        // from previous when absent on a diff-style controller update).
        val result = MessageParser.parseServerState(payload, lastMetadata, lastControllerState)
        val metadata = result.metadata
        val state = result.state
        val controllerState = result.controllerState
        val colorState = result.colorState

        if (metadata != null) {
            lastMetadata = metadata
            onMetadataUpdate(metadata)
        }

        if (state != null && state != lastPlaybackState) {
            lastPlaybackState = state
            onPlaybackStateChanged(state)
        }

        if (controllerState != null) {
            lastControllerState = controllerState
            onControllerStateUpdate(controllerState)
        }

        if (colorState != null && colorState != lastColorState) {
            lastColorState = colorState
            onColorStateUpdate(colorState)
        }
    }

    protected fun handleServerCommand(payload: Any?) {
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
            is ServerCommandResult.Unknown -> {
                Log.d(tag, "Unknown player command: ${result.command}")
            }
            null -> { /* No player command in payload */ }
        }
    }

    protected fun handleGroupUpdate(payload: Any?) {
        val info = MessageParser.parseGroupUpdate(payload)
        if (info != null) {
            lastGroupInfo = info
            Log.v(tag, "group/update: id=${info.groupId}, name=${info.groupName}, state=${info.playbackState}")
            onGroupUpdate(info)
        }
    }

    protected fun handleStreamStart(payload: Any?) {
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
    }

    protected fun handleStreamClear(payload: Any?) {
        Log.i(tag, "[cmd-trace] T1 handleStreamClear ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        // `roles` field carries unversioned family names per spec
        // (STREAM_CLEAR_ROLE_FAMILIES = {"player", "visualizer"}). If the field
        // is present and "player" is not in it, the clear targets a role we
        // don't host (e.g. visualizer-only) and must not wipe our audio buffer.
        val roles = MessageParser.parseRoles(payload)

        if (roles != null && SendSpinProtocol.RoleFamily.PLAYER !in roles) {
            Log.d(tag, "Stream clear for non-player roles: $roles - ignoring")
            return
        }

        Log.v(tag, "Stream clear - flushing audio buffers (roles=${roles ?: "all"})")
        onStreamClear()
    }

    protected fun handleStreamEnd(payload: Any?) {
        Log.i(tag, "[cmd-trace] T1 handleStreamEnd ts=${System.nanoTime() / 1_000_000} thread=${Thread.currentThread().name}")
        // `roles` field carries unversioned family names per spec
        // (STREAM_END_ROLE_FAMILIES = {"player", "artwork", "visualizer", "color"}).
        // Compare against the family name, not the versioned [Roles.PLAYER]
        // (= "player@v1"); the latter never matches and silently swallows
        // every stream/end the server emits.
        val roles = MessageParser.parseRoles(payload)

        // Color role: clears any cached palette regardless of which other roles
        // are ending. Independent of the player-end branch below because a
        // color-only end (just artwork swap with no audio change) should not
        // tear down the audio stream.
        val endColor = roles == null || SendSpinProtocol.RoleFamily.COLOR in roles
        if (endColor && lastColorState != null) {
            lastColorState = null
            onColorStateCleared()
        }

        if (roles != null && SendSpinProtocol.RoleFamily.PLAYER !in roles) {
            Log.d(tag, "Stream end for non-player roles: $roles - ignoring")
            return
        }

        Log.i(tag, "Stream end - server terminated playback (roles=${roles ?: "all"})")
        _streamActive = false
        _currentStreamConfig = null
        // Do NOT clear lastMetadata here. The Sendspin server/state stream is
        // diff-style (see JsonOptional doc): clients are expected to carry
        // unchanged fields across updates, and on pause MA emits a
        // progress-only server/state right after stream/end (title / artist /
        // album / artwork_url all Absent). With lastMetadata cleared, the
        // parser has nothing to inherit from, collapses Absent to "" for
        // strings, and downstream withMetadata interprets the empty
        // artwork_url as an explicit clear -- the NowPlaying screen's
        // artwork-zombie safeguard then flips metadata to EMPTY after ~1.7 s
        // and the user lands on the standby idle screen mid-pause.
        //
        // On a real track change, MA includes every field that actually
        // changed in the next server/state, so the carryover from the prior
        // track is correct (same artist/album when staying on an album, fresh
        // values when crossing albums). Cross-server leakage is still guarded
        // by the post-handshake clear in handleServerHello.
        onStreamEnd()
    }

    protected fun handleClientSyncOffset(payload: Any?) {
        val result = MessageParser.parseSyncOffset(payload)
        if (result == null) {
            Log.w(tag, "client/sync_offset: missing or invalid payload")
            return
        }

        Log.i(tag, "client/sync_offset: offset=${result.offsetMs}ms from ${result.source}")

        if (result.offsetMs.isNaN() || result.offsetMs.isInfinite()) {
            Log.w(tag, "client/sync_offset: rejecting non-finite offset ${result.offsetMs}")
            return
        }

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
     *
     * Gated on [handshakeComplete]: the Sendspin protocol guarantees
     * `server/hello` and `stream/start` precede any audio frames, so binary
     * data arriving before the handshake either indicates a misbehaving
     * server or a late frame from a previous (already-torn-down) session
     * crossing the new connection's setup window. Either way, feeding such
     * a chunk into the audio sink before the codec / sample-rate / bit-depth
     * are known is at best wasted work and at worst a mis-decoded burst.
     * Drop with a warn so server bugs surface in field logs.
     */
    protected fun handleBinaryMessage(bytes: ByteArray) {
        if (!handshakeComplete) {
            Log.w(tag, "Dropping ${bytes.size}-byte binary frame: received before handshake complete")
            return
        }
        val message = BinaryMessageParser.parse(bytes)
        if (message != null) {
            dispatchBinaryMessage(message)
        }
    }

    /**
     * Dispatch parsed binary message to appropriate handler.
     */
    private fun dispatchBinaryMessage(message: BinaryMessageParser.BinaryMessage) {
        when (message) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                onAudioChunk(message.timestampMicros, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Artwork -> {
                Log.v(tag, "Received artwork channel ${message.channel}: ${message.payload.size} bytes")
                onArtwork(message.channel, message.payload)
            }
            is BinaryMessageParser.BinaryMessage.Visualizer -> {
                // Visualization data - currently not used, no logging needed
            }
            is BinaryMessageParser.BinaryMessage.Unknown -> {
                Log.v(tag, "Unknown binary message type: ${message.type}")
            }
        }
    }
}
