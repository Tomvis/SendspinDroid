package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkObject(Platform)
        every { Platform.base64Decode(any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- parseServerHello ---

    @Test
    fun parseServerHello_returnsTheServerName() {
        val payload = buildJsonObject {
            put("name", "TestServer")
            put("languages", buildJsonArray { add(JsonPrimitive("en")) })
        }
        assertEquals("TestServer", MessageParser.parseServerHello(payload, "default"))
    }

    @Test
    fun parseServerHello_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerHello(null, "default"))
    }

    @Test
    fun parseServerHello_missingName_usesDefault() {
        assertEquals("MyDefault", MessageParser.parseServerHello(buildJsonObject { }, "MyDefault"))
    }

    // --- parseServerTime ---

    @Test
    fun parseServerTime_validTimestamps_returnsCorrectOffset() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 200L)
            put("server_transmitted", 300L)
        }
        val clientReceived = 400L

        val result = MessageParser.parseServerTime(payload, clientReceived)

        assertNotNull(result)
        assertEquals(0L, result!!.offset)
    }

    @Test
    fun parseServerTime_withClockOffset_calculatesCorrectly() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 1200L)
            put("server_transmitted", 1300L)
        }
        val result = MessageParser.parseServerTime(payload, 200L)

        assertNotNull(result)
        assertEquals(1100L, result!!.offset)
    }

    @Test
    fun parseServerTime_calculatesRtt() {
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 200L)
            put("server_transmitted", 300L)
        }
        val result = MessageParser.parseServerTime(payload, 400L)

        assertNotNull(result)
        assertEquals(200L, result!!.rtt)
    }

    @Test
    fun parseServerTime_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerTime(null, 0L))
    }

    @Test
    fun parseServerTime_zeroTimestamps_returnsResult() {
        // Zero is a valid timestamp value (M-01 fix: nullable distinguishes absent from zero)
        val payload = buildJsonObject {
            put("client_transmitted", 0L)
            put("server_received", 0L)
            put("server_transmitted", 0L)
        }
        val result = MessageParser.parseServerTime(payload, 0L)
        assertNotNull(result)
        assertEquals(0L, result!!.offset)
        assertEquals(0L, result.rtt)
    }

    @Test
    fun parseServerTime_missingFields_returnsNull() {
        // Missing required fields should return null
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            // server_received and server_transmitted absent
        }
        assertNull(MessageParser.parseServerTime(payload, 400L))
    }

    @Test
    fun parseServerTime_partiallyMissingFields_returnsNull() {
        // Only two of three required fields present
        val payload = buildJsonObject {
            put("client_transmitted", 100L)
            put("server_received", 200L)
            // server_transmitted absent
        }
        assertNull(MessageParser.parseServerTime(payload, 400L))
    }

    @Test
    fun parseServerTime_emptyPayload_returnsNull() {
        val payload = buildJsonObject { }
        assertNull(MessageParser.parseServerTime(payload, 400L))
    }

    // --- parseServerState ---

    @Test
    fun parseServerState_fullMetadata_parsesEveryField() {
        val payload = buildJsonObject {
            put("metadata", buildJsonObject {
                put("timestamp", 1234567890L)
                put("title", "Test Song")
                put("artist", "Test Artist")
                put("album_artist", "Album Artist")
                put("album", "Test Album")
                put("artwork_url", "https://example.com/art.jpg")
                put("year", 2024)
                put("track", 5)
                put("progress", buildJsonObject {
                    put("track_progress", 45000L)
                    put("track_duration", 180000L)
                    put("playback_speed", 1000)
                })
            })
            put("state", "playing")
        }

        val (metadata, state) = MessageParser.parseServerState(payload)

        assertEquals(1234567890L, metadata!!.timestamp)
        assertEquals("Test Song", metadata.title)
        assertEquals("Test Artist", metadata.artist)
        assertEquals("Album Artist", metadata.albumArtist)
        assertEquals("Test Album", metadata.album)
        assertEquals("https://example.com/art.jpg", metadata.artworkUrl)
        assertEquals(2024, metadata.year)
        assertEquals(5, metadata.track)
        assertEquals(45000L, metadata.progress!!.trackProgress)
        assertEquals(180000L, metadata.progress!!.trackDuration)
        assertEquals("playing", state)
    }

    @Test
    fun parseServerState_trackfieldsServerFrame_parsesSplitTrackFields() {
        // Fork contract (HW-81): a server/state frame exactly as aiosendspin
        // 10.0.0+trackfields serializes Metadata.snapshot_update(). If the wheel and
        // this parser drift apart, the TV screen silently loses "X of Y".
        val frame = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"payload":{"metadata":{"timestamp":5,"title":"T","artist":"A","album_artist":"AA","album":"Al","year":2001,"album_track":3,"queue_track":7,"total_tracks":12,"progress":{"track_progress":1000,"track_duration":200000,"playback_speed":1000}}},"type":"server/state"}"""
        ) as JsonObject

        val (metadata, _) = MessageParser.parseServerState(frame["payload"] as JsonObject)

        assertEquals(3, metadata!!.albumTrack)
        assertEquals(7, metadata.queueTrack)
        assertEquals(12, metadata.totalTracks)
        assertNull(metadata.track)
        assertEquals("AA", metadata.albumArtist)
        assertEquals(200000L, metadata.progress!!.trackDuration)
    }

    @Test
    fun parseServerState_nullPayload_returnsNulls() {
        val result = MessageParser.parseServerState(null)
        assertNull(result.metadata)
        assertNull(result.playbackState)
        assertNull(result.controller)
    }

    @Test
    fun parseServerState_omittedRoleObjects_areNull() {
        // "Omitting a role object leaves that role's state unchanged."
        val result = MessageParser.parseServerState(buildJsonObject { put("state", "paused") })

        assertNull(result.metadata)
        assertNull(result.controller)
        assertEquals("paused", result.playbackState)
    }

    @Test
    fun parseServerState_metadataWithOnlyATimestamp_isAnEmptyTrack() {
        // What the reference server sends when nothing is playing. The object
        // is the role's full state, so everything it omits has no value.
        val payload = buildJsonObject {
            put("metadata", buildJsonObject { put("timestamp", 42L) })
        }

        val metadata = MessageParser.parseServerState(payload).metadata

        assertEquals(42L, metadata!!.timestamp)
        assertNull(metadata.title)
        assertNull(metadata.artist)
        assertNull(metadata.album)
        assertNull(metadata.artworkUrl)
        assertNull("omitting progress clears the position", metadata.progress)
    }

    @Test
    fun parseServerState_nullLeaves_readAsNoValue() {
        // Observed on a device 2026-04-23: idle metadata with every leaf a
        // JSON null. "progress": null used to throw.
        val payload = buildJsonObject {
            put("metadata", buildJsonObject {
                put("timestamp", 9730008767707L)
                put("title", JsonPrimitive(null as String?))
                put("artist", JsonPrimitive(null as String?))
                put("album_artist", JsonPrimitive(null as String?))
                put("album", JsonPrimitive(null as String?))
                put("artwork_url", JsonPrimitive(null as String?))
                put("year", JsonPrimitive(null as Int?))
                put("track", JsonPrimitive(null as Int?))
                put("progress", JsonPrimitive(null as String?))
            })
        }

        val metadata = MessageParser.parseServerState(payload).metadata

        assertEquals(9730008767707L, metadata!!.timestamp)
        assertNull(metadata.title)
        assertNull(metadata.artist)
        assertNull(metadata.year)
        assertNull(metadata.progress)
    }

    @Test
    fun parseServerState_nullRoleObject_isTreatedAsOmitted() {
        // Not something the spec defines: a role object is present and
        // complete, or it is not sent.
        val payload = buildJsonObject {
            put("metadata", JsonPrimitive(null as String?))
            put("controller", JsonPrimitive(null as String?))
        }
        val result = MessageParser.parseServerState(payload)

        assertNull(result.metadata)
        assertNull(result.controller)
    }

    @Test
    fun parseServerState_controllerObject_parsesAllFields() {
        val payload = buildJsonObject {
            put("controller", buildJsonObject {
                put("supported_commands", buildJsonArray {
                    add(JsonPrimitive("play"))
                    add(JsonPrimitive("pause"))
                    add(JsonPrimitive("volume"))
                })
                put("volume", 60)
                put("muted", false)
                put("repeat", "all")
                put("shuffle", true)
                put("seek_max_ms", 180000L)
            })
        }

        val controller = MessageParser.parseServerState(payload).controller

        assertEquals(listOf("play", "pause", "volume"), controller!!.supportedCommands)
        assertEquals(60, controller.volume)
        assertEquals(false, controller.muted)
        assertEquals("all", controller.repeat)
        assertEquals(true, controller.shuffle)
        assertEquals(180000L, controller.seekMaxMs)
    }

    @Test
    fun parseServerState_controllerWithoutSeek_hasNoSeekMax() {
        val payload = buildJsonObject {
            put("controller", buildJsonObject {
                put("supported_commands", buildJsonArray { add(JsonPrimitive("play")) })
                put("volume", 42)
                put("muted", false)
                put("repeat", "off")
                put("shuffle", false)
            })
        }

        assertNull(MessageParser.parseServerState(payload).controller!!.seekMaxMs)
    }

    @Test
    fun parseServerCommand_setOutputDelay_returnsResult() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "set_output_delay")
                put("output_delay_ms", 150)
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.SetOutputDelay)
        assertEquals(150, (result as ServerCommandResult.SetOutputDelay).delayMs)
    }

    @Test
    fun parseServerCommand_setOutputDelayOutOfRange_isClamped() {
        // "Clients MUST clamp output_delay_ms to the range 0-5000."
        fun delayFor(value: Long): Int {
            val payload = buildJsonObject {
                put("player", buildJsonObject {
                    put("command", "set_output_delay")
                    put("output_delay_ms", value)
                })
            }
            return (MessageParser.parseServerCommand(payload) as ServerCommandResult.SetOutputDelay).delayMs
        }
        assertEquals(5000, delayFor(6000))
        assertEquals(5000, delayFor(10_000_000_000L))
        assertEquals(0, delayFor(-1))
    }

    @Test
    fun parseServerCommand_setOutputDelayMissing_returnsNull() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "set_output_delay")
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    // --- parseServerCommand ---

    @Test
    fun parseServerCommand_volume_returnsVolumeResult() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", 75)
            })
        }
        val result = MessageParser.parseServerCommand(payload)

        assertTrue(result is ServerCommandResult.Volume)
        assertEquals(75, (result as ServerCommandResult.Volume).volume)
    }

    @Test
    fun parseServerCommand_volumeOutOfRange_returnsNull() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", 101)
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_volumeNegative_returnsNull() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "volume")
                put("volume", -1)
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_mute_returnsMuteResult() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "mute")
                put("mute", true)
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Mute)
        assertTrue((result as ServerCommandResult.Mute).muted)
    }

    @Test
    fun parseServerCommand_muteExplicitFalse_returnsFalse() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "mute")
                put("mute", false)
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Mute)
        assertFalse((result as ServerCommandResult.Mute).muted)
    }

    @Test
    fun parseServerCommand_muteMissing_returnsNull() {
        // `mute` is "required if command is mute". Without it there is nothing
        // to apply, and defaulting to false would unmute a muted player.
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "mute")
            })
        }
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_unknownCommand_returnsUnknown() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("command", "custom_cmd")
            })
        }
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Unknown)
        assertEquals("custom_cmd", (result as ServerCommandResult.Unknown).command)
    }

    @Test
    fun parseServerCommand_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerCommand(null))
    }

    @Test
    fun parseServerCommand_noPlayerObject_returnsNull() {
        assertNull(MessageParser.parseServerCommand(buildJsonObject { }))
    }

    // --- parseGroupUpdate ---

    @Test
    fun parseGroupUpdate_validPayload_returnsGroupInfo() {
        val payload = buildJsonObject {
            put("group_id", "group-1")
            put("group_name", "Living Room")
            put("playback_state", "playing")
        }
        val result = MessageParser.parseGroupUpdate(payload)

        assertNotNull(result)
        assertEquals("group-1", result!!.groupId)
        assertEquals("Living Room", result.groupName)
        assertEquals("playing", result.playbackState)
    }

    @Test
    fun parseGroupUpdate_nullPayload_returnsNull() {
        assertNull(MessageParser.parseGroupUpdate(null))
    }

    // --- parseStreamStart ---

    @Test
    fun parseStreamStart_validPayload_returnsStreamConfig() {
        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("codec", "flac")
                put("sample_rate", 44100)
                put("channels", 2)
                put("bit_depth", 24)
            })
        }
        val result = MessageParser.parseStreamStart(payload)

        assertNotNull(result)
        assertEquals("flac", result!!.codec)
        assertEquals(44100, result.sampleRate)
        assertEquals(2, result.channels)
        assertEquals(24, result.bitDepth)
        assertNull(result.codecHeader)
    }

    @Test
    fun parseStreamStart_withCodecHeader_decodesBase64() {
        val headerBytes = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"
        val headerBase64 = java.util.Base64.getEncoder().encodeToString(headerBytes)

        val payload = buildJsonObject {
            put("player", buildJsonObject {
                put("codec", "flac")
                put("sample_rate", 48000)
                put("channels", 2)
                put("bit_depth", 16)
                put("codec_header", headerBase64)
            })
        }
        val result = MessageParser.parseStreamStart(payload)

        assertNotNull(result)
        assertNotNull(result!!.codecHeader)
        assertArrayEquals(headerBytes, result.codecHeader)
    }

    @Test
    fun parseStreamStart_nullPayload_returnsNull() {
        assertNull(MessageParser.parseStreamStart(null))
    }

    @Test
    fun parseStreamStart_noPlayerObject_returnsNull() {
        assertNull(MessageParser.parseStreamStart(buildJsonObject { }))
    }

    // --- parseSyncOffset ---

    @Test
    fun parseSyncOffset_validPayload_returnsResult() {
        val payload = buildJsonObject {
            put("player_id", "player-1")
            put("offset_ms", 5.5)
            put("source", "calibration")
        }
        val result = MessageParser.parseSyncOffset(payload)

        assertNotNull(result)
        assertEquals("player-1", result!!.playerId)
        assertEquals(5.5, result.offsetMs, 0.001)
        assertEquals("calibration", result.source)
    }

    @Test
    fun parseSyncOffset_nullPayload_returnsNull() {
        assertNull(MessageParser.parseSyncOffset(null))
    }
}
