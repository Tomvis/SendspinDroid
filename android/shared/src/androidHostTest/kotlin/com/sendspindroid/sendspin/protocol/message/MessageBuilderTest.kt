package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class MessageBuilderTest {

    // --- buildClientTime ---

    @Test
    fun buildClientTime_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildClientTime(12345L)).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_TIME, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildClientTime_hasClientTransmittedInPayload() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildClientTime(12345L)).jsonObject
        val payload = msg["payload"]!!.jsonObject
        assertEquals(12345L, payload["client_transmitted"]?.jsonPrimitive?.long)
    }

    // --- buildGoodbye ---

    @Test
    fun buildGoodbye_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildGoodbye("user_disconnect")).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_GOODBYE, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildGoodbye_hasReasonInPayload() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildGoodbye("user_disconnect")).jsonObject
        val payload = msg["payload"]!!.jsonObject
        assertEquals("user_disconnect", payload["reason"]?.jsonPrimitive?.content)
    }

    // --- buildPlayerState ---

    @Test
    fun buildPlayerState_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false, available = true)).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_STATE, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildPlayerState_hasPlayerObjectWithFields() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(75, true, available = false)).jsonObject
        val payload = msg["payload"]!!.jsonObject
        val player = payload["player"]!!.jsonObject
        assertEquals(75, player["volume"]?.jsonPrimitive?.int)
        assertTrue(player["muted"]?.jsonPrimitive?.boolean ?: false)
        // Per spec, `state` is a top-level payload field, not part of the
        // player object.
        assertEquals("false", payload["available"]?.jsonPrimitive?.content)
        assertNull("state must not be nested in player", player["state"])
    }

    @Test
    fun buildPlayerState_defaultSyncState() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false, available = true)).jsonObject
        val payload = msg["payload"]!!.jsonObject
        assertEquals("true", payload["available"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildPlayerState_outputDelayMsRoundedToInt() {
        // Spec: output_delay_ms is an integer.
        val msg = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, true, 12.5)
        ).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(13, player["output_delay_ms"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildPlayerState_outputDelayMsDefaultsToZero() {
        val msg = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, available = true)
        ).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(0, player["output_delay_ms"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildPlayerState_outputDelayMsClampedToSpecRange() {
        // Spec: 0-5000, negative values not supported. A negative user sync
        // offset is applied locally but reported as 0.
        val negative = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, true, -120.0)
        ).jsonObject["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(0, negative["output_delay_ms"]?.jsonPrimitive?.int)

        val huge = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, true, 9999.0)
        ).jsonObject["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(5000, huge["output_delay_ms"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildPlayerState_declaresSetOutputDelaySupport() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false, available = true)).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        val commands = player["supported_commands"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("volume", "mute", "set_output_delay"), commands)
    }

    @Test
    fun buildPlayerState_includesRequiredTimingFields() {
        // Spec: required_lead_time_ms and min_buffer_ms are always required
        // for players.
        val msg = Json.parseToJsonElement(MessageBuilder.buildPlayerState(50, false, available = true)).jsonObject
        val player = msg["payload"]!!.jsonObject["player"]!!.jsonObject
        assertEquals(
            SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS,
            player["required_lead_time_ms"]?.jsonPrimitive?.int
        )
        assertEquals(
            SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS,
            player["min_buffer_ms"]?.jsonPrimitive?.int
        )
    }

    // --- buildCommand ---

    @Test
    fun buildCommand_hasCorrectType() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("play")).jsonObject
        assertEquals(SendSpinProtocol.MessageType.CLIENT_COMMAND, msg["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildCommand_hasCommandInControllerObject() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("next")).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("next", controller["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildCommand_volumeCommandIncludesVolume() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("volume", volume = 65)).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("volume", controller["command"]?.jsonPrimitive?.content)
        assertEquals(65, controller["volume"]?.jsonPrimitive?.int)
        assertNull(controller["mute"])
    }

    @Test
    fun buildCommand_muteCommandIncludesMute() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("mute", mute = true)).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("mute", controller["command"]?.jsonPrimitive?.content)
        assertEquals(true, controller["mute"]?.jsonPrimitive?.boolean)
        assertNull(controller["volume"])
    }

    @Test
    fun buildCommand_plainCommandOmitsOptionalParams() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("repeat_all")).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("repeat_all", controller["command"]?.jsonPrimitive?.content)
        assertNull(controller["volume"])
        assertNull(controller["mute"])
    }

    @Test
    fun buildCommand_seekCarriesPositionMs() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("seek", positionMs = 42_000)).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("seek", controller["command"]?.jsonPrimitive?.content)
        assertEquals(42_000L, controller["position_ms"]?.jsonPrimitive?.long)
        assertNull(controller["offset_ms"])
    }

    @Test
    fun buildCommand_seekRelativeCarriesSignedOffsetMs() {
        val msg = Json.parseToJsonElement(MessageBuilder.buildCommand("seek_relative", offsetMs = -10_000)).jsonObject
        val controller = msg["payload"]!!.jsonObject["controller"]!!.jsonObject
        assertEquals("seek_relative", controller["command"]?.jsonPrimitive?.content)
        assertEquals(-10_000L, controller["offset_ms"]?.jsonPrimitive?.long)
        assertNull(controller["position_ms"])
    }

    // --- buildPlayerState: format preference and artwork ---

    @Test
    fun buildPlayerState_omitsFormatWhenThereIsNoOverriddenPreference() {
        // "Absent means no overridden preference."
        val player = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, available = true)
        ).jsonObject["payload"]!!.jsonObject["player"]!!.jsonObject
        assertNull(player["format"])
    }

    @Test
    fun buildPlayerState_reportsThePreferredFormatAsAWholeFormatObject() {
        // roles/player/v1.md: `format` replaces stream/request-format. It is a
        // complete supported_formats entry, never a partial one.
        val player = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(
                50, false, available = true,
                format = MessageBuilder.FormatEntry("flac", 48000, 2, 16),
            )
        ).jsonObject["payload"]!!.jsonObject["player"]!!.jsonObject
        val format = player["format"]!!.jsonObject
        assertEquals(setOf("codec", "sample_rate", "channels", "bit_depth"), format.keys)
        assertEquals("flac", format["codec"]?.jsonPrimitive?.content)
        assertEquals(48000, format["sample_rate"]?.jsonPrimitive?.int)
        assertEquals(2, format["channels"]?.jsonPrimitive?.int)
        assertEquals(16, format["bit_depth"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildPlayerState_omitsTheArtworkObjectUnlessTheRoleIsActive() {
        val payload = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, available = true)
        ).jsonObject["payload"]!!.jsonObject
        assertNull(payload["artwork"])
    }

    @Test
    fun buildPlayerState_declaresOneAlbumArtworkChannelWithRc1KeyNames() {
        // roles/artwork/v1.md: channels are configured here, not in
        // client/hello, with `width`/`height` (not media_width/media_height).
        val payload = Json.parseToJsonElement(
            MessageBuilder.buildPlayerState(50, false, available = true, artworkRoleActive = true)
        ).jsonObject["payload"]!!.jsonObject
        val channels = payload["artwork"]!!.jsonObject["channels"]!!.jsonArray
        assertEquals(1, channels.size)
        val channel = channels[0].jsonObject
        assertEquals(setOf("source", "format", "width", "height"), channel.keys)
        assertEquals("album", channel["source"]?.jsonPrimitive?.content)
        assertEquals("jpeg", channel["format"]?.jsonPrimitive?.content)
        assertEquals(500, channel["width"]?.jsonPrimitive?.int)
        assertEquals(500, channel["height"]?.jsonPrimitive?.int)
    }

    @Test
    fun buildPlayerState_wholeMessageWithEveryRoleObject() {
        // Byte for byte. aiosendspin 10.0.0 parses this text with no deviation
        // reported by its player or artwork role.
        assertEquals(
            """{"type":"client/state","payload":{"available":true,"player":{"volume":80,""" +
                """"muted":false,"output_delay_ms":120,"required_lead_time_ms":1500,""" +
                """"min_buffer_ms":350,"supported_commands":["volume","mute","set_output_delay"],""" +
                """"format":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}},""" +
                """"artwork":{"channels":[{"source":"album","format":"jpeg","width":500,"height":500}]}}}""",
            MessageBuilder.buildPlayerState(
                80, false, available = true, outputDelayMs = 120.0,
                format = MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
                artworkRoleActive = true,
            ),
        )
    }

    @Test
    fun buildPlayerState_withNoActiveRolesCarriesOnlyAvailable() {
        assertEquals(
            """{"type":"client/state","payload":{"available":true}}""",
            MessageBuilder.buildPlayerState(80, false, available = true, playerRoleActive = false),
        )
    }

    // --- buildSupportedFormats ---

    @Test
    fun buildSupportedFormats_preferredCodecFirst_pcmLast() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it in listOf("opus", "pcm") }
        )
        assertTrue(formats.isNotEmpty())
        assertEquals("opus", formats.first().codec)
        assertEquals("pcm", formats.last().codec)
    }

    @Test
    fun buildSupportedFormats_onlyPreferredAndPcm_noSecondaryCodec() {
        // Regression guard for issue #26: previously a hardcoded [flac, opus] secondary
        // list was appended after the preferred codec. Now only the preferred codec
        // and PCM are advertised.
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it in listOf("opus", "flac", "pcm") }
        )
        val codecsAdvertised = formats.map { it.codec }.toSet()
        assertEquals(setOf("opus", "pcm"), codecsAdvertised)
    }

    @Test
    fun buildSupportedFormats_preferredPcmProducesPcmOnly() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "pcm",
            isCodecSupported = { it == "pcm" }
        )
        // Pin the dedup: pcm stereo + pcm mono = 2, not 4.
        assertEquals(2, formats.size)
        assertTrue(formats.all { it.codec == "pcm" })
    }

    @Test
    fun buildSupportedFormats_preferredUnsupportedFallsBackToPcm() {
        // On a device where the preferred codec is not decodable, we still advertise
        // PCM so the session is not silently broken.
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { it == "pcm" }
        )
        val codecsAdvertised = formats.map { it.codec }.toSet()
        assertEquals(setOf("pcm"), codecsAdvertised)
        // Default supportedBitDepths = [16], so pcm stereo + pcm mono = 2.
        assertEquals(2, formats.size)
    }

    @Test
    fun buildSupportedFormats_noCodecsSupportedReturnsEmpty() {
        // Pins the current behaviour for the degenerate case where not even PCM
        // is supported. A real Android device should never produce this (PCM is
        // always supported) but we lock it in so a future refactor can't
        // silently change the contract.
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "opus",
            isCodecSupported = { false }
        )
        assertTrue(formats.isEmpty())
    }

    @Test
    fun buildSupportedFormats_stereoAndMonoForEachCodec() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "pcm") }
        )
        // flac at 16-bit stereo + mono = 2
        // pcm at 16-bit stereo + mono = 2 (default supportedBitDepths = [16])
        // total = 4
        assertEquals(4, formats.size)
        val channelSet = formats.map { it.channels }.toSet()
        assertEquals(setOf(2, 1), channelSet)
    }

    @Test
    fun buildSupportedFormats_multiBitDepthOnlyAppliesToPcm() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "flac",
            isCodecSupported = { it in listOf("flac", "pcm") },
            supportedBitDepths = listOf(16, 32)
        )
        // flac: 16-bit only (stereo + mono) = 2
        // pcm:  32-bit stereo/mono + 16-bit stereo/mono = 4 (higher depths first)
        assertEquals(6, formats.size)
        assertEquals("flac", formats[0].codec)
        assertEquals(16, formats[0].bitDepth)
        assertEquals("pcm", formats[2].codec)
        assertEquals(32, formats[2].bitDepth)
        assertEquals("pcm", formats[4].codec)
        assertEquals(16, formats[4].bitDepth)
    }

    @Test
    fun buildSupportedFormats_defaultBitDepthIs16Only() {
        val formats = MessageBuilder.buildSupportedFormats(
            preferredCodec = "pcm",
            isCodecSupported = { it == "pcm" }
        )
        assertEquals(2, formats.size)
        assertTrue(formats.all { it.bitDepth == 16 })
    }

    // --- calculateBufferCapacity ---

    @Test
    fun calculateBufferCapacity_16bitStereo35sec() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_32bitStereo35sec() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 32),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 32),
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // Uses max PCM entry: 35 * 48000 * 2 * 4 = 13,440,000
        assertEquals(13_440_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_lowMemory16bit() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // 10 * 48000 * 2 * 2 = 1,920,000
        assertEquals(1_920_000, MessageBuilder.calculateBufferCapacity(formats, 10))
    }

    @Test
    fun calculateBufferCapacity_ignoresCompressedCodecs() {
        val formats = listOf(
            MessageBuilder.FormatEntry("flac", 48000, 2, 16),
            MessageBuilder.FormatEntry("opus", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16),
            MessageBuilder.FormatEntry("pcm", 48000, 1, 16)
        )
        // Only PCM entries matter: 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    @Test
    fun calculateBufferCapacity_fallbackWhenNoPcm() {
        val formats = listOf(
            MessageBuilder.FormatEntry("flac", 48000, 2, 16)
        )
        // Fallback: 35 * 48000 * 2 * 2 = 6,720,000
        assertEquals(6_720_000, MessageBuilder.calculateBufferCapacity(formats, 35))
    }

    // --- buildClientHello ---
    // The message's shape is covered by ClientHelloFieldsTest.

    @Test
    fun buildClientHello_hasCorrectBufferCapacity() {
        val formats = listOf(
            MessageBuilder.FormatEntry("pcm", 48000, 2, 16)
        )
        val text = MessageBuilder.buildClientHello(
            deviceName = "Test Device",
            bufferCapacity = 6_720_000,
            manufacturer = "Test",
            supportedFormats = formats
        )
        val payload = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject
        val playerSupport = payload["player@v1_support"]!!.jsonObject
        assertEquals(6_720_000, playerSupport["buffer_capacity"]?.jsonPrimitive?.int)
    }

    // --- No serialize needed (returns String directly) ---

    @Test
    fun buildClientTime_outputIsValidJson() {
        val text = MessageBuilder.buildClientTime(12345L)
        // Should not throw
        Json.parseToJsonElement(text)
        assertFalse("Should not contain escaped slashes", text.contains("\\/"))
    }
}
