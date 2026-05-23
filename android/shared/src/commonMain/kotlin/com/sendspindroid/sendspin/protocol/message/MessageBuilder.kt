package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.MoshiInstance
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.sendspin.protocol.wire.WireArtworkChannel
import com.sendspindroid.sendspin.protocol.wire.WireArtworkSupport
import com.sendspindroid.sendspin.protocol.wire.WireAudioFormat
import com.sendspindroid.sendspin.protocol.wire.WireClientCommandEnvelope
import com.sendspindroid.sendspin.protocol.wire.WireClientCommandPayload
import com.sendspindroid.sendspin.protocol.wire.WireClientControllerCommand
import com.sendspindroid.sendspin.protocol.wire.WireClientGoodbyeEnvelope
import com.sendspindroid.sendspin.protocol.wire.WireClientGoodbyePayload
import com.sendspindroid.sendspin.protocol.wire.WireClientHelloEnvelope
import com.sendspindroid.sendspin.protocol.wire.WireClientHelloPayload
import com.sendspindroid.sendspin.protocol.wire.WireClientPlayerState
import com.sendspindroid.sendspin.protocol.wire.WireClientStateEnvelope
import com.sendspindroid.sendspin.protocol.wire.WireClientStatePayload
import com.sendspindroid.sendspin.protocol.wire.WireClientTimeEnvelope
import com.sendspindroid.sendspin.protocol.wire.WireClientTimePayload
import com.sendspindroid.sendspin.protocol.wire.WireDeviceInfo
import com.sendspindroid.sendspin.protocol.wire.WirePlayerSupport
import com.sendspindroid.sendspin.protocol.wire.WireProxyAuthMessage

/**
 * Builds outgoing Sendspin client messages as JSON strings.
 *
 * Each method constructs the appropriate Wire-level data class and serialises
 * it through Moshi via [MoshiInstance]. KSP-generated adapters keep this
 * entirely off the reflection path at runtime.
 */
object MessageBuilder {

    data class FormatEntry(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
    )

    private val helloAdapter by lazy {
        MoshiInstance.moshi.adapter(WireClientHelloEnvelope::class.java)
    }
    private val timeAdapter by lazy {
        MoshiInstance.moshi.adapter(WireClientTimeEnvelope::class.java)
    }
    private val goodbyeAdapter by lazy {
        MoshiInstance.moshi.adapter(WireClientGoodbyeEnvelope::class.java)
    }
    private val stateAdapter by lazy {
        MoshiInstance.moshi.adapter(WireClientStateEnvelope::class.java)
    }
    private val commandAdapter by lazy {
        MoshiInstance.moshi.adapter(WireClientCommandEnvelope::class.java)
    }
    private val proxyAuthAdapter by lazy {
        MoshiInstance.moshi.adapter(WireProxyAuthMessage::class.java)
    }

    fun buildClientHello(
        clientId: String,
        deviceName: String,
        bufferCapacity: Int,
        manufacturer: String,
        supportedFormats: List<FormatEntry>,
        lowMemoryMode: Boolean = false,
    ): String {
        val roles = buildList {
            add(SendSpinProtocol.Roles.PLAYER)
            add(SendSpinProtocol.Roles.CONTROLLER)
            add(SendSpinProtocol.Roles.METADATA)
            add(SendSpinProtocol.Roles.COLOR)
            if (!lowMemoryMode) add(SendSpinProtocol.Roles.ARTWORK)
        }
        val artworkSupport = if (lowMemoryMode) null else WireArtworkSupport(
            channels = listOf(
                WireArtworkChannel(
                    source = "album",
                    format = "jpeg",
                    mediaWidth = SendSpinProtocol.Artwork.REQUEST_SIZE,
                    mediaHeight = SendSpinProtocol.Artwork.REQUEST_SIZE,
                )
            )
        )
        val envelope = WireClientHelloEnvelope(
            payload = WireClientHelloPayload(
                clientId = clientId,
                name = deviceName,
                version = SendSpinProtocol.VERSION,
                supportedRoles = roles,
                deviceInfo = WireDeviceInfo(
                    productName = "SendSpinDroid",
                    manufacturer = manufacturer,
                    softwareVersion = "1.0.0",
                ),
                playerSupport = WirePlayerSupport(
                    supportedFormats = supportedFormats.map {
                        WireAudioFormat(it.codec, it.sampleRate, it.channels, it.bitDepth)
                    },
                    bufferCapacity = bufferCapacity,
                ),
                artworkSupport = artworkSupport,
            )
        )
        // Moshi default: nullable null fields are omitted from output. That is
        // exactly what we want for `artwork@v1_support` in low-memory mode.
        return helloAdapter.toJson(envelope)
    }

    fun buildClientTime(clientTransmittedMicros: Long): String =
        timeAdapter.toJson(
            WireClientTimeEnvelope(
                payload = WireClientTimePayload(clientTransmitted = clientTransmittedMicros)
            )
        )

    fun buildGoodbye(reason: String): String =
        goodbyeAdapter.toJson(
            WireClientGoodbyeEnvelope(
                payload = WireClientGoodbyePayload(reason = reason)
            )
        )

    fun buildPlayerState(
        volume: Int,
        muted: Boolean,
        syncState: String = "synchronized",
        staticDelayMs: Int = 0,
    ): String {
        // Per spec the wire field is int in [0, 5000]. Anything outside drops
        // the connection. The caller (time filter) tracks a signed Double so
        // it can apply a negative user offset internally — but it must not
        // leak negative or fractional values onto the wire.
        val clampedDelay = staticDelayMs.coerceIn(
            SendSpinProtocol.StaticDelay.MIN_MS,
            SendSpinProtocol.StaticDelay.MAX_MS,
        )
        return stateAdapter.toJson(
            WireClientStateEnvelope(
                payload = WireClientStatePayload(
                    state = syncState,
                    player = WireClientPlayerState(
                        state = syncState,
                        volume = volume,
                        muted = muted,
                        staticDelayMs = clampedDelay,
                    ),
                )
            )
        )
    }

    fun buildCommand(command: String): String =
        commandAdapter.toJson(
            WireClientCommandEnvelope(
                payload = WireClientCommandPayload(
                    controller = WireClientControllerCommand(command = command)
                )
            )
        )

    /**
     * Build the proxy-mode authentication message. Not part of the Sendspin
     * protocol — specific to the authenticated reverse-proxy transport on
     * this fork. The proxy server expects a JSON message
     * `{"type":"auth","token":"...","client_id":"..."}` as the very first
     * WebSocket frame, before any `client/hello`.
     */
    fun buildProxyAuth(token: String, clientId: String): String =
        proxyAuthAdapter.toJson(
            WireProxyAuthMessage(token = token, clientId = clientId)
        )

    /**
     * Calculate buffer_capacity (wire bytes) from target duration and format list.
     *
     * Uses the highest-bitrate PCM entry we advertise as the basis, so the cap
     * is tight for PCM and gives compressed codecs proportionally more seconds
     * of look-ahead (but bounded decoded memory).
     */
    fun calculateBufferCapacity(formats: List<FormatEntry>, durationSec: Int): Int {
        val maxPcmBytesPerSec = formats
            .filter { it.codec == "pcm" }
            .maxOfOrNull { it.sampleRate * it.channels * (it.bitDepth / 8) }
            ?: (SendSpinProtocol.AudioFormat.SAMPLE_RATE
                * SendSpinProtocol.AudioFormat.CHANNELS
                * (SendSpinProtocol.AudioFormat.BIT_DEPTH / 8))
        return durationSec * maxPcmBytesPerSec
    }

    /**
     * Build the supported_formats list for the client/hello message.
     *
     * The advertised list never contains a codec other than [preferredCodec] or
     * `"pcm"`, and when both are present the preferred codec appears first
     * (each with stereo+mono variants at the appropriate bit depths). Edge cases:
     *  - If [preferredCodec] is not supported on this device, it is silently dropped
     *    and only PCM is advertised. The Settings UI surfaces supported codecs
     *    explicitly; this fallback exists so a connection can still succeed even
     *    if support state was stale at the time the preference was set.
     *  - If [preferredCodec] is `"pcm"`, PCM is advertised once (not twice).
     *  - If neither the preferred codec nor PCM is supported (shouldn't happen on
     *    any real Android device; PCM is always supported), the list is empty.
     *
     * Compressed codecs (FLAC, Opus) are always advertised at 16-bit. PCM is
     * advertised at every entry in [supportedBitDepths], highest first (so the
     * server picks the best-quality match).
     */
    fun buildSupportedFormats(
        preferredCodec: String,
        isCodecSupported: (String) -> Boolean,
        supportedBitDepths: List<Int> = listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH),
    ): List<FormatEntry> {
        val codecOrder = mutableListOf<String>()
        if (preferredCodec != "pcm" && isCodecSupported(preferredCodec)) {
            codecOrder.add(preferredCodec)
        }
        if (isCodecSupported("pcm")) {
            codecOrder.add("pcm")
        }
        return buildList {
            for (codec in codecOrder) {
                val depths = if (codec == "pcm") {
                    supportedBitDepths.sortedDescending()
                } else {
                    listOf(SendSpinProtocol.AudioFormat.BIT_DEPTH)
                }
                for (bitDepth in depths) {
                    add(
                        FormatEntry(
                            codec = codec,
                            sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                            channels = SendSpinProtocol.AudioFormat.CHANNELS,
                            bitDepth = bitDepth,
                        )
                    )
                    add(
                        FormatEntry(
                            codec = codec,
                            sampleRate = SendSpinProtocol.AudioFormat.SAMPLE_RATE,
                            channels = SendSpinProtocol.AudioFormat.CHANNELS_MONO,
                            bitDepth = bitDepth,
                        )
                    )
                }
            }
        }
    }
}
