package com.sendspindroid.conformance

import com.sendspindroid.sendspin.SendspinTimeFilter
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.MessageParser
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.WebSocketTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * JVM-native Sendspin client driven by [Main] under the Sendspin/conformance
 * harness. Reuses :shared's transport, time filter, time sync, parser, and
 * builder; reimplements the small amount of glue (handshake state machine,
 * onAudioChunk capture, summary aggregation) that lives in the Android app's
 * SendSpin.kt/SendSpinProtocolHandler.kt — those depend on Android APIs we
 * deliberately do not bring into this CLI.
 */
internal class ConformanceClient(private val args: CliArgs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFilter = SendspinTimeFilter()
    private val started = System.currentTimeMillis()

    // Aggregated state used in the final summary.
    private val handshakeComplete = CompletableDeferred<Boolean>()
    private val serverNameRef = AtomicReference<String?>(null)
    private val serverIdRef = AtomicReference<String?>(null)
    private val negotiatedStream = AtomicReference<NegotiatedStream?>(null)
    private val audioChunkCount = AtomicInteger(0)
    private val audioByteCount = AtomicLong(0)
    private val artworkChunkCount = AtomicInteger(0)
    private val metadataUpdateCount = AtomicInteger(0)
    private val lastTitleRef = AtomicReference<String?>(null)
    private val lastArtistRef = AtomicReference<String?>(null)
    private val lastPlaybackStateRef = AtomicReference<String?>(null)
    private val controllerUpdateCount = AtomicInteger(0)
    private val lastControllerVolumeRef = AtomicReference<Int?>(null)
    private val lastControllerRepeatRef = AtomicReference<String?>(null)
    private val lastControllerShuffleRef = AtomicReference<Boolean?>(null)
    private val colorUpdateCount = AtomicInteger(0)
    private val streamStartCount = AtomicInteger(0)
    private val streamEndCount = AtomicInteger(0)
    private val streamClearCount = AtomicInteger(0)
    private val clockMeasurements = AtomicInteger(0)
    // Thread-safe: appended from the OkHttp reader-thread callbacks (onFailure/
    // onClosed) and snapshotted via toList() on the runBlocking thread at
    // shutdown, so a plain ArrayList would risk a ConcurrentModificationException.
    private val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

    // Lazily wired once the transport is connected.
    private var transport: SendSpinTransport? = null
    private var timeSyncManager: TimeSyncManager? = null

    suspend fun run(): Summary {
        signalReady()

        val url = "${args.host}:${args.port}"
        val ws = WebSocketTransport(url, args.path, pingIntervalSeconds = 30L)
        transport = ws

        ws.setListener(object : SendSpinTransport.Listener {
            override fun onConnected() {
                sendClientHello()
            }

            override fun onMessage(text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(bytes: ByteArray) {
                handleBinaryMessage(bytes)
            }

            override fun onClosing(code: Int, reason: String) {}

            override fun onClosed(code: Int, reason: String) {
                if (!handshakeComplete.isCompleted) {
                    handshakeComplete.complete(false)
                }
            }

            override fun onFailure(error: Throwable, isRecoverable: Boolean) {
                errors += "transport failure: ${error.message}"
                if (!handshakeComplete.isCompleted) {
                    handshakeComplete.complete(false)
                }
            }
        })

        ws.connect()

        // Wait for handshake (or transport-level failure) up to half the
        // scenario timeout. After that, let the scenario run for the
        // remainder of the timeout so the server has time to stream audio,
        // emit metadata, etc.
        val handshakeOk = withTimeoutOrNull(args.timeoutSeconds * 500L) {
            handshakeComplete.await()
        } ?: false

        if (handshakeOk) {
            // Let the harness drive — wait the remaining scenario time.
            val remainingMs = (args.timeoutSeconds * 1000L) - (System.currentTimeMillis() - started)
            if (remainingMs > 0) delay(remainingMs)
        }

        // Clean shutdown
        timeSyncManager?.stop()
        try {
            transport?.send(MessageBuilder.buildGoodbye(SendSpinProtocol.GoodbyeReason.USER_REQUEST))
        } catch (_: Throwable) {
            // best-effort
        }
        transport?.close(1000, "scenario complete")
        transport?.destroy()
        scope.cancel()

        return buildSummary(handshakeOk)
    }

    private fun signalReady() {
        val ready = args.readyPath ?: return
        try {
            ready.createParentDirectories()
            ready.writeText("ready\n")
        } catch (e: Throwable) {
            errors += "could not write ready file: ${e.message}"
        }
    }

    private fun sendClientHello() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = args.preferredCodec,
            isCodecSupported = { it == "pcm" || it == "flac" || it == "opus" },
        )
        val bufferCapacity = MessageBuilder.calculateBufferCapacity(
            formats,
            SendSpinProtocol.Buffer.DURATION_NORMAL_SEC,
        )
        val hello = MessageBuilder.buildClientHello(
            clientId = UUID.randomUUID().toString(),
            deviceName = "conformance-client",
            bufferCapacity = bufferCapacity,
            manufacturer = "Conformance",
            supportedFormats = formats,
        )
        transport?.send(hello)
    }

    private fun sendClientTime() {
        val nowMicros = System.nanoTime() / 1000L
        transport?.send(MessageBuilder.buildClientTime(nowMicros))
    }

    private fun handleTextMessage(text: String) {
        val parsed = MessageParser.parseEnvelope(text)
        if (parsed == null) {
            errors += "bad text frame"
            return
        }
        val (type, payload) = parsed
        when (type) {
            SendSpinProtocol.MessageType.SERVER_HELLO -> {
                val hello = MessageParser.parseServerHello(payload, "Unknown") ?: return
                serverNameRef.set(hello.serverName)
                serverIdRef.set(hello.serverId)
                startTimeSync()
                sendInitialClientState()
                handshakeComplete.complete(true)
            }
            SendSpinProtocol.MessageType.SERVER_TIME -> {
                val now = System.nanoTime() / 1000L
                val measurement = MessageParser.parseServerTime(payload, now) ?: return
                clockMeasurements.incrementAndGet()
                timeSyncManager?.onServerTime(measurement)
            }
            SendSpinProtocol.MessageType.SERVER_STATE -> {
                val result = MessageParser.parseServerState(payload, previousMetadata, previousController)
                val metadata = result.metadata
                if (metadata != null) {
                    previousMetadata = metadata
                    metadataUpdateCount.incrementAndGet()
                    if (metadata.title.isNotEmpty()) lastTitleRef.set(metadata.title)
                    if (metadata.artist.isNotEmpty()) lastArtistRef.set(metadata.artist)
                }
                if (result.state != null) lastPlaybackStateRef.set(result.state)
                val controller = result.controllerState
                if (controller != null) {
                    previousController = controller
                    controllerUpdateCount.incrementAndGet()
                    lastControllerVolumeRef.set(controller.volume)
                    lastControllerRepeatRef.set(controller.repeat)
                    lastControllerShuffleRef.set(controller.shuffle)
                }
                if (result.colorState != null) {
                    colorUpdateCount.incrementAndGet()
                }
            }
            SendSpinProtocol.MessageType.STREAM_START -> {
                val cfg = MessageParser.parseStreamStart(payload) ?: return
                streamStartCount.incrementAndGet()
                negotiatedStream.set(
                    NegotiatedStream(cfg.codec, cfg.sampleRate, cfg.channels, cfg.bitDepth)
                )
            }
            SendSpinProtocol.MessageType.STREAM_END -> {
                streamEndCount.incrementAndGet()
            }
            SendSpinProtocol.MessageType.STREAM_CLEAR -> {
                streamClearCount.incrementAndGet()
            }
            SendSpinProtocol.MessageType.SERVER_COMMAND -> {
                // Ignored: the harness's server emits these to drive the
                // client's volume/mute, but a recording conformance client
                // doesn't need to act on them.
            }
            SendSpinProtocol.MessageType.GROUP_UPDATE -> {
                // Recorded counts only — no observable side effect in summary.
            }
            else -> {
                // Unknown message types are non-fatal per spec.
            }
        }
    }

    @Volatile private var previousMetadata: com.sendspindroid.sendspin.protocol.TrackMetadata? = null
    @Volatile private var previousController: com.sendspindroid.sendspin.protocol.ControllerState? = null

    private fun handleBinaryMessage(bytes: ByteArray) {
        val msg = BinaryMessageParser.parse(bytes) ?: return
        when (msg) {
            is BinaryMessageParser.BinaryMessage.Audio -> {
                audioChunkCount.incrementAndGet()
                audioByteCount.addAndGet(msg.payload.size.toLong())
            }
            is BinaryMessageParser.BinaryMessage.Artwork -> {
                artworkChunkCount.incrementAndGet()
            }
            is BinaryMessageParser.BinaryMessage.Visualizer,
            is BinaryMessageParser.BinaryMessage.Unknown -> {
                // Out of scope for the summary fields the harness validates.
            }
        }
    }

    private fun startTimeSync() {
        val manager = TimeSyncManager(
            timeFilter = timeFilter,
            sendClientTime = { sendClientTime() },
            tag = "ConformanceTimeSync",
        )
        timeSyncManager = manager
        manager.start(scope)
    }

    private fun sendInitialClientState() {
        transport?.send(
            MessageBuilder.buildPlayerState(
                volume = 100,
                muted = false,
                syncState = "error",
                staticDelayMs = 0,
            )
        )
    }

    private fun buildSummary(handshakeOk: Boolean): Summary {
        val stream = negotiatedStream.get()
        return Summary(
            scenarioId = args.scenarioId,
            preferredCodec = args.preferredCodec,
            handshakeComplete = handshakeOk,
            serverName = serverNameRef.get(),
            serverId = serverIdRef.get(),
            negotiatedCodec = stream?.codec,
            negotiatedSampleRate = stream?.sampleRate,
            negotiatedChannels = stream?.channels,
            negotiatedBitDepth = stream?.bitDepth,
            clockMeasurementsReceived = clockMeasurements.get(),
            clockConverged = timeFilter.isConverged,
            lastClockOffsetUs = if (timeFilter.isReady) timeFilter.offsetMicros else null,
            lastClockErrorUs = if (timeFilter.isReady) timeFilter.errorMicros else null,
            lastClockDriftPpm = if (timeFilter.isReady) timeFilter.driftPpm else null,
            audioChunksReceived = audioChunkCount.get(),
            audioBytesReceived = audioByteCount.get(),
            artworkChunksReceived = artworkChunkCount.get(),
            metadataUpdates = metadataUpdateCount.get(),
            lastTitle = lastTitleRef.get(),
            lastArtist = lastArtistRef.get(),
            lastPlaybackState = lastPlaybackStateRef.get(),
            controllerUpdates = controllerUpdateCount.get(),
            lastControllerVolume = lastControllerVolumeRef.get(),
            lastControllerRepeat = lastControllerRepeatRef.get(),
            lastControllerShuffle = lastControllerShuffleRef.get(),
            colorUpdates = colorUpdateCount.get(),
            streamStarts = streamStartCount.get(),
            streamEnds = streamEndCount.get(),
            streamClears = streamClearCount.get(),
            durationMs = System.currentTimeMillis() - started,
            errors = errors.toList(),
        )
    }

    private data class NegotiatedStream(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
    )
}
