package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.ServerCommandResult
import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
    fun parseServerHello_validPayload_returnsResult() {
        val payload = mapOf<String, Any?>(
            "name" to "TestServer",
            "server_id" to "abc-123",
            "connection_reason" to "user_request",
            "active_roles" to listOf("player@v1", "controller@v1"),
        )
        val result = MessageParser.parseServerHello(payload, "default")

        assertNotNull(result)
        assertEquals("TestServer", result!!.serverName)
        assertEquals("abc-123", result.serverId)
        assertEquals("user_request", result.connectionReason)
        assertEquals(2, result.activeRoles.size)
        assertEquals("player@v1", result.activeRoles[0])
    }

    @Test
    fun parseServerHello_nullPayload_returnsNull() {
        assertNull(MessageParser.parseServerHello(null, "default"))
    }

    @Test
    fun parseServerHello_missingOptionalFields_usesDefaults() {
        val result = MessageParser.parseServerHello(emptyMap<String, Any?>(), "MyDefault")

        assertNotNull(result)
        assertEquals("MyDefault", result!!.serverName)
        assertEquals("", result.serverId)
        assertEquals("discovery", result.connectionReason)
        assertTrue(result.activeRoles.isEmpty())
    }

    // --- parseServerTime ---

    @Test
    fun parseServerTime_validTimestamps_returnsCorrectOffset() {
        val payload = mapOf<String, Any?>(
            "client_transmitted" to 100L,
            "server_received" to 200L,
            "server_transmitted" to 300L,
        )
        val clientReceived = 400L

        val result = MessageParser.parseServerTime(payload, clientReceived)

        assertNotNull(result)
        assertEquals(0L, result!!.offset)
    }

    @Test
    fun parseServerTime_withClockOffset_calculatesCorrectly() {
        val payload = mapOf<String, Any?>(
            "client_transmitted" to 100L,
            "server_received" to 1200L,
            "server_transmitted" to 1300L,
        )
        val result = MessageParser.parseServerTime(payload, 200L)

        assertNotNull(result)
        assertEquals(1100L, result!!.offset)
    }

    @Test
    fun parseServerTime_calculatesRtt() {
        val payload = mapOf<String, Any?>(
            "client_transmitted" to 100L,
            "server_received" to 200L,
            "server_transmitted" to 300L,
        )
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
        val payload = mapOf<String, Any?>(
            "client_transmitted" to 0L,
            "server_received" to 0L,
            "server_transmitted" to 0L,
        )
        val result = MessageParser.parseServerTime(payload, 0L)
        assertNotNull(result)
        assertEquals(0L, result!!.offset)
        assertEquals(0L, result.rtt)
    }

    @Test
    fun parseServerTime_missingFields_returnsNull() {
        // Missing required fields should return null
        val payload = mapOf<String, Any?>("client_transmitted" to 100L)
        assertNull(MessageParser.parseServerTime(payload, 400L))
    }

    @Test
    fun parseServerTime_partiallyMissingFields_returnsNull() {
        // Only two of three required fields present
        val payload = mapOf<String, Any?>(
            "client_transmitted" to 100L,
            "server_received" to 200L,
        )
        assertNull(MessageParser.parseServerTime(payload, 400L))
    }

    @Test
    fun parseServerTime_emptyPayload_returnsNull() {
        assertNull(MessageParser.parseServerTime(emptyMap<String, Any?>(), 400L))
    }

    // --- parseServerState ---

    @Test
    fun parseServerState_specCompliantNested_parsesCorrectly() {
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "timestamp" to 1234567890L,
                "title" to "Test Song",
                "artist" to "Test Artist",
                "album_artist" to "Album Artist",
                "album" to "Test Album",
                "artwork_url" to "https://example.com/art.jpg",
                "year" to 2024,
                "album_track" to 3,
                "queue_track" to 5,
                "total_tracks" to 12,
                "progress" to mapOf<String, Any?>(
                    "track_progress" to 45000L,
                    "track_duration" to 180000L,
                    "playback_speed" to 1000,
                ),
            ),
            "state" to "playing",
        )

        val (metadata, state) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals("Test Song", metadata!!.title)
        assertEquals("Test Artist", metadata.artist)
        assertEquals("Album Artist", metadata.albumArtist)
        assertEquals(2024, metadata.year)
        assertEquals(3, metadata.albumTrack)
        assertEquals(5, metadata.queueTrack)
        assertEquals(12, metadata.totalTracks)
        assertEquals(45000L, metadata.progress.trackProgress)
        assertEquals(180000L, metadata.progress.trackDuration)
        assertEquals("playing", state)
    }

    @Test
    fun parseServerState_missingIntFields_defaultToZero() {
        // When album_track / queue_track / total_tracks / year are omitted, parser returns 0.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "Stream",
                "artist" to "Radio",
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals(0, metadata!!.year)
        assertEquals(0, metadata.albumTrack)
        assertEquals(0, metadata.queueTrack)
        assertEquals(0, metadata.totalTracks)
    }

    @Test
    fun parseServerState_legacyTrackKey_mapsToAlbumTrack() {
        // Pre-rename servers emit `track` instead of `album_track`. The parser
        // must fall back to the legacy key so older servers still surface a
        // track number, rather than silently reporting 0.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "Old Server Song",
                "artist" to "Old Server Artist",
                "track" to 7,
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals(7, metadata!!.albumTrack)
    }

    @Test
    fun parseServerState_albumTrackTakesPriorityOverLegacyTrack() {
        // If a server emits both (unlikely but possible during migration),
        // the spec-compliant `album_track` wins.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "Song",
                "album_track" to 3,
                "track" to 99,
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals(3, metadata!!.albumTrack)
    }

    @Test
    fun parseServerState_albumTrackExplicitZeroFallsBackToLegacyTrack() {
        // Per spec, 0 means "not set" for integer fields. A mid-migration
        // server that emits `album_track: 0` (because the field is unknown
        // in their database) but still has the legacy `track` populated
        // should surface the legacy value, not the sentinel zero.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "Song",
                "album_track" to 0,
                "track" to 7,
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals(7, metadata!!.albumTrack)
    }

    @Test
    fun parseServerState_legacyFlatStructure_parsesAsFallback() {
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "Legacy Song",
                "artist" to "Legacy Artist",
                "position_ms" to 30000L,
                "duration_ms" to 200000L,
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload)

        assertNotNull(metadata)
        assertEquals("Legacy Song", metadata!!.title)
        assertEquals(30000L, metadata.progress.trackProgress)
        assertEquals(200000L, metadata.progress.trackDuration)
    }

    @Test
    fun parseServerState_nullPayload_returnsNulls() {
        val (metadata, state) = MessageParser.parseServerState(null)
        assertNull(metadata)
        assertNull(state)
    }

    @Test
    fun parseServerState_noMetadata_returnsNullMetadata() {
        val payload = mapOf<String, Any?>("state" to "paused")
        val (metadata, state) = MessageParser.parseServerState(payload)
        assertNull(metadata)
        assertEquals("paused", state)
    }

    @Test
    fun parseServerState_idleMetadataWithNullFields_doesNotThrow() {
        // Reproduces the on-device exception observed 2026-04-23: server emits
        // idle metadata with every field JsonNull ("progress": null in particular
        // triggered IllegalArgumentException: ... is not a JsonObject).
        // Parser must treat JsonNull the same as absent.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "timestamp" to 9730008767707L,
                "title" to null,
                "artist" to null,
                "album_artist" to null,
                "album" to null,
                "artwork_url" to null,
                "year" to null,
                "track" to null,
                "progress" to null,
                "repeat" to null,
                "shuffle" to null,
            ),
        )

        val (metadata, state) = MessageParser.parseServerState(payload)

        assertNotNull("idle metadata should still yield a TrackMetadata", metadata)
        assertEquals("", metadata!!.title)
        assertEquals("", metadata.artist)
        assertEquals(0L, metadata.progress.trackProgress)
        assertEquals(0L, metadata.progress.trackDuration)
        assertNull(state)
    }

    @Test
    fun parseServerState_partialUpdate_preservesAbsentFields() {
        // The server's diff_update path emits only changed fields. With no
        // previous to merge against, absent fields fall through to defaults
        // (this preserves the existing missingIntFields contract). When a
        // previous metadata is supplied, absent fields must inherit so that
        // a title-only update does not wipe artist/album/artwork/progress.
        val previous = com.sendspindroid.sendspin.protocol.TrackMetadata(
            timestamp = 1000L,
            title = "Old Title",
            artist = "Old Artist",
            albumArtist = "Old Album Artist",
            album = "Old Album",
            artworkUrl = "https://example.com/old.jpg",
            year = 2020,
            albumTrack = 4,
            queueTrack = 2,
            totalTracks = 10,
            progress = com.sendspindroid.sendspin.protocol.TrackProgress(
                trackProgress = 60000L,
                trackDuration = 240000L,
                playbackSpeed = 1000,
            ),
        )
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "timestamp" to 2000L,
                "title" to "New Title",
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload, previous)

        assertNotNull(metadata)
        assertEquals("New Title", metadata!!.title)
        assertEquals("Old Artist", metadata.artist)
        assertEquals("Old Album Artist", metadata.albumArtist)
        assertEquals("Old Album", metadata.album)
        assertEquals("https://example.com/old.jpg", metadata.artworkUrl)
        assertEquals(2020, metadata.year)
        assertEquals(4, metadata.albumTrack)
        assertEquals(2, metadata.queueTrack)
        assertEquals(10, metadata.totalTracks)
        assertEquals(60000L, metadata.progress.trackProgress)
        assertEquals(240000L, metadata.progress.trackDuration)
        assertEquals(1000, metadata.progress.playbackSpeed)
    }

    @Test
    fun parseServerState_partialUpdate_clearsExplicitlyNullFields() {
        // Field present with `null` means "clear" per the server's
        // cleared_update path; the client must reset to the type default
        // (empty string / 0) even when a previous value exists.
        val previous = com.sendspindroid.sendspin.protocol.TrackMetadata(
            timestamp = 1000L,
            title = "Old Title",
            artist = "Old Artist",
            albumArtist = "",
            album = "",
            artworkUrl = "https://example.com/old.jpg",
            year = 2020,
            albumTrack = 0,
            queueTrack = 0,
            totalTracks = 0,
            progress = com.sendspindroid.sendspin.protocol.TrackProgress(
                trackProgress = 60000L,
                trackDuration = 240000L,
                playbackSpeed = 1000,
            ),
        )
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "timestamp" to 2000L,
                "artist" to null,
                "artwork_url" to null,
                "progress" to null,
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload, previous)

        assertNotNull(metadata)
        assertEquals("Old Title", metadata!!.title)
        assertEquals("", metadata.artist)
        assertEquals("", metadata.artworkUrl)
        assertEquals(0L, metadata.progress.trackProgress)
        assertEquals(0L, metadata.progress.trackDuration)
    }

    @Test
    fun parseServerState_progressOnlyUpdate_keepsTitleAndArtist() {
        // The server typically emits progress separately from title changes
        // mid-track. Pre-fix this regressed title to "" on every progress
        // tick.
        val previous = com.sendspindroid.sendspin.protocol.TrackMetadata(
            timestamp = 1000L,
            title = "Currently Playing",
            artist = "Currently Playing Artist",
            albumArtist = "",
            album = "Album",
            artworkUrl = "",
            year = 0,
            albumTrack = 0,
            queueTrack = 0,
            totalTracks = 0,
            progress = com.sendspindroid.sendspin.protocol.TrackProgress(0L, 240000L, 1000),
        )
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "timestamp" to 2000L,
                "progress" to mapOf<String, Any?>(
                    "track_progress" to 12345L,
                    "track_duration" to 240000L,
                    "playback_speed" to 1000,
                ),
            ),
        )

        val (metadata, _) = MessageParser.parseServerState(payload, previous)

        assertNotNull(metadata)
        assertEquals("Currently Playing", metadata!!.title)
        assertEquals("Currently Playing Artist", metadata.artist)
        assertEquals("Album", metadata.album)
        assertEquals(12345L, metadata.progress.trackProgress)
    }

    @Test
    fun parseServerState_nullMetadataField_returnsNullMetadata() {
        // Defensive: if the server ever sends `{"metadata": null}` instead of
        // an object, we must treat it like missing rather than throwing.
        val payload = mapOf<String, Any?>(
            "metadata" to null,
            "state" to "stopped",
        )
        val (metadata, state) = MessageParser.parseServerState(payload)
        assertNull(metadata)
        assertEquals("stopped", state)
    }

    // --- parseServerState controller object ---

    @Test
    fun parseServerState_controllerState_extractsAllFields() {
        val payload = mapOf<String, Any?>(
            "controller" to mapOf<String, Any?>(
                "supported_commands" to listOf("play", "pause", "next"),
                "volume" to 73,
                "muted" to false,
            ),
        )

        val result = MessageParser.parseServerState(payload)

        assertNotNull(result.controllerState)
        val cs = result.controllerState!!
        assertEquals(listOf("play", "pause", "next"), cs.supportedCommands)
        assertEquals(73, cs.volume)
        assertFalse(cs.muted)
    }

    @Test
    fun parseServerState_noControllerObject_returnsNullControllerState() {
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>("timestamp" to 1L, "title" to "X"),
        )
        val result = MessageParser.parseServerState(payload)
        assertNull(result.controllerState)
    }

    @Test
    fun parseServerState_controllerVolumeOutOfRange_returnsNullControllerState() {
        val payload = mapOf<String, Any?>(
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 101,
                "muted" to false,
            ),
        )
        assertNull(MessageParser.parseServerState(payload).controllerState)
    }

    @Test
    fun parseServerState_controllerMissingField_returnsNullControllerState() {
        // muted missing -> reject; spec marks all three fields as required.
        val payload = mapOf<String, Any?>(
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 50,
            ),
        )
        assertNull(MessageParser.parseServerState(payload).controllerState)
    }

    // --- parseServerState controller.repeat / controller.shuffle ---

    @Test
    fun parseServerState_controllerRepeatShuffle_parsedFromControllerObject() {
        val payload = mapOf<String, Any?>(
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 50,
                "muted" to false,
                "repeat" to "one",
                "shuffle" to true,
            ),
        )
        val cs = MessageParser.parseServerState(payload).controllerState
        assertNotNull(cs)
        assertEquals("one", cs!!.repeat)
        assertEquals(true, cs.shuffle)
    }

    @Test
    fun parseServerState_legacyRepeatShuffleOnMetadata_fallsBack() {
        // Pre-controller.repeat servers emit repeat/shuffle inside metadata.
        // Parser must fall back to those positions when the controller object
        // does not carry them.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "X",
                "repeat" to "all",
                "shuffle" to false,
            ),
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 50,
                "muted" to false,
            ),
        )
        val cs = MessageParser.parseServerState(payload).controllerState
        assertNotNull(cs)
        assertEquals("all", cs!!.repeat)
        assertEquals(false, cs.shuffle)
    }

    @Test
    fun parseServerState_controllerRepeatShuffleWinsOverLegacy() {
        // If a server emits both for safety during migration, the spec-current
        // controller-object position wins.
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>(
                "title" to "X",
                "repeat" to "all",
                "shuffle" to false,
            ),
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 50,
                "muted" to false,
                "repeat" to "off",
                "shuffle" to true,
            ),
        )
        val cs = MessageParser.parseServerState(payload).controllerState
        assertNotNull(cs)
        assertEquals("off", cs!!.repeat)
        assertEquals(true, cs.shuffle)
    }

    @Test
    fun parseServerState_controllerWithoutRepeatShuffle_nullFields() {
        val payload = mapOf<String, Any?>(
            "controller" to mapOf<String, Any?>(
                "supported_commands" to emptyList<String>(),
                "volume" to 50,
                "muted" to false,
            ),
        )
        val cs = MessageParser.parseServerState(payload).controllerState
        assertNotNull(cs)
        assertNull(cs!!.repeat)
        assertNull(cs.shuffle)
    }

    // --- parseServerState color@v1 ---

    @Test
    fun parseServerState_colorState_parsesAllPaletteSlots() {
        val payload = mapOf<String, Any?>(
            "color" to mapOf<String, Any?>(
                "timestamp" to 1234567890L,
                "background_dark" to listOf(20, 30, 40),
                "background_light" to listOf(220, 230, 240),
                "primary" to listOf(100, 110, 120),
                "accent" to listOf(200, 150, 50),
                "on_dark" to listOf(255, 255, 255),
                "on_light" to listOf(0, 0, 0),
            ),
        )
        val color = MessageParser.parseServerState(payload).colorState
        assertNotNull(color)
        assertEquals(1234567890L, color!!.timestamp)
        assertEquals(listOf(20, 30, 40), color.backgroundDark)
        assertEquals(listOf(220, 230, 240), color.backgroundLight)
        assertEquals(listOf(100, 110, 120), color.primary)
        assertEquals(listOf(200, 150, 50), color.accent)
        assertEquals(listOf(255, 255, 255), color.onDark)
        assertEquals(listOf(0, 0, 0), color.onLight)
    }

    @Test
    fun parseServerState_colorState_partialPaletteAllowsNulls() {
        // A server may extract only some colors for a given artwork. Missing
        // slots stay null rather than defaulting to black.
        val payload = mapOf<String, Any?>(
            "color" to mapOf<String, Any?>(
                "timestamp" to 1L,
                "primary" to listOf(10, 20, 30),
            ),
        )
        val color = MessageParser.parseServerState(payload).colorState
        assertNotNull(color)
        assertNull(color!!.backgroundDark)
        assertNull(color.backgroundLight)
        assertEquals(listOf(10, 20, 30), color.primary)
        assertNull(color.accent)
    }

    @Test
    fun parseServerState_colorState_rejectsBadRgb() {
        // 3-int triple is the only valid shape per the spec; anything else
        // (wrong length, out-of-range, non-integer) yields null for that slot.
        val payload = mapOf<String, Any?>(
            "color" to mapOf<String, Any?>(
                "primary" to listOf(10, 20),  // length 2
                "accent" to listOf(300, 10, 10),  // 300 out of range
            ),
        )
        val color = MessageParser.parseServerState(payload).colorState
        assertNotNull(color)
        assertNull(color!!.primary)
        assertNull(color.accent)
    }

    @Test
    fun parseServerState_noColorObject_returnsNullColorState() {
        val payload = mapOf<String, Any?>(
            "metadata" to mapOf<String, Any?>("title" to "X"),
        )
        assertNull(MessageParser.parseServerState(payload).colorState)
    }

    // --- parseServerCommand ---

    @Test
    fun parseServerCommand_volume_returnsVolumeResult() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "command" to "volume",
                "volume" to 75,
            ),
        )
        val result = MessageParser.parseServerCommand(payload)

        assertTrue(result is ServerCommandResult.Volume)
        assertEquals(75, (result as ServerCommandResult.Volume).volume)
    }

    @Test
    fun parseServerCommand_volumeOutOfRange_returnsNull() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "command" to "volume",
                "volume" to 101,
            ),
        )
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_volumeNegative_returnsNull() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "command" to "volume",
                "volume" to -1,
            ),
        )
        assertNull(MessageParser.parseServerCommand(payload))
    }

    @Test
    fun parseServerCommand_mute_returnsMuteResult() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "command" to "mute",
                "mute" to true,
            ),
        )
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Mute)
        assertTrue((result as ServerCommandResult.Mute).muted)
    }

    @Test
    fun parseServerCommand_muteExplicitFalse_returnsFalse() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "command" to "mute",
                "mute" to false,
            ),
        )
        val result = MessageParser.parseServerCommand(payload)
        assertTrue(result is ServerCommandResult.Mute)
        assertFalse((result as ServerCommandResult.Mute).muted)
    }

    @Test
    fun parseServerCommand_muteMissing_returnsNullAsMalformed() {
        // The `mute` field carries the desired state. Missing it is malformed:
        // defaulting to false (unmute) would silently invert the user's intent
        // on a "set mute on, drop the value" send. The parser rejects so the
        // dispatcher logs/skips instead of issuing the wrong command.
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>("command" to "mute"),
        )
        val result = MessageParser.parseServerCommand(payload)
        assertNull(result)
    }

    @Test
    fun parseServerCommand_unknownCommand_returnsUnknown() {
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>("command" to "custom_cmd"),
        )
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
        assertNull(MessageParser.parseServerCommand(emptyMap<String, Any?>()))
    }

    // --- parseGroupUpdate ---

    @Test
    fun parseGroupUpdate_validPayload_returnsGroupInfo() {
        val payload = mapOf<String, Any?>(
            "group_id" to "group-1",
            "group_name" to "Living Room",
            "playback_state" to "playing",
        )
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
        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "codec" to "flac",
                "sample_rate" to 44100,
                "channels" to 2,
                "bit_depth" to 24,
            ),
        )
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

        val payload = mapOf<String, Any?>(
            "player" to mapOf<String, Any?>(
                "codec" to "flac",
                "sample_rate" to 48000,
                "channels" to 2,
                "bit_depth" to 16,
                "codec_header" to headerBase64,
            ),
        )
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
        assertNull(MessageParser.parseStreamStart(emptyMap<String, Any?>()))
    }

    // --- parseSyncOffset ---

    @Test
    fun parseSyncOffset_validPayload_returnsResult() {
        val payload = mapOf<String, Any?>(
            "player_id" to "player-1",
            "offset_ms" to 5.5,
            "source" to "calibration",
        )
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

    // --- parseEnvelope ---

    @Test
    fun parseEnvelope_extractsTypeAndPayload() {
        val text = """
            {
                "type": "server/state",
                "payload": {"metadata": {"title": "X"}}
            }
        """.trimIndent()
        val (type, payload) = MessageParser.parseEnvelope(text)!!
        assertEquals("server/state", type)
        @Suppress("UNCHECKED_CAST")
        val payloadMap = payload as Map<String, Any?>
        assertNotNull(payloadMap["metadata"])
    }

    @Test
    fun parseEnvelope_missingType_returnsNull() {
        val text = """{"payload": {"x": 1}}"""
        assertNull(MessageParser.parseEnvelope(text))
    }

    @Test
    fun parseEnvelope_malformedJson_returnsNull() {
        assertNull(MessageParser.parseEnvelope("not json"))
    }
}
