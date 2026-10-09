package com.sendspindroid.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `server/state` as Sendspin 1.0.0-rc1 defines it: each role object a message
 * includes is that role's full state, a future `timestamp` schedules a
 * metadata update, and a role removed by `server/activate` loses its state.
 */
class ServerStateTest {

    private lateinit var handler: TestProtocolHandler

    private val serverHello = """{"type":"server/hello","payload":{"name":"Dev"}}"""

    private fun activate(roles: String) =
        """{"type":"server/activate","payload":{"activities":["playback"],"active_roles":[$roles]}}"""

    private val allRoles = "\"player@v1\",\"controller@v1\",\"metadata@v1\",\"artwork@v1\""

    private fun serverState(roleObjects: String) =
        handler.handleTextMessageForTest("""{"type":"server/state","payload":{$roleObjects}}""")

    private fun metadata(timestampMicros: Long, title: String, progress: Boolean = true) = serverState(
        """"metadata":{"timestamp":$timestampMicros,"title":"$title","artist":"Artist"""" +
            (if (progress) ""","progress":{"track_progress":1000,"track_duration":9000,"playback_speed":1000}""" else "") +
            "}"
    )

    private val controller = """"controller":{"supported_commands":["play","seek"],""" +
        """"volume":60,"muted":false,"repeat":"all","shuffle":true,"seek_max_ms":9000}"""

    /** A timestamp the time filter places this far from now, once [syncClock] ran. */
    private fun serverTime(offsetSeconds: Long) = System.nanoTime() / 1000 + offsetSeconds * 1_000_000

    private val past get() = serverTime(-1)
    private val future get() = serverTime(15)

    /** Give the time filter an estimate: server and client clocks equal. */
    private fun syncClock() {
        val filter = handler.exposedTimeFilter()
        val now = System.nanoTime() / 1000
        filter.addMeasurement(0, 1000, now - 1000)
        filter.addMeasurement(0, 1000, now)
        assertTrue(filter.isReady)
    }

    private fun advance(seconds: Long) {
        handler.testScheduler.advanceTimeBy(seconds * 1000)
        handler.testScheduler.runCurrent()
    }

    private fun titles() = handler.metadataUpdates.map { it.title }

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        handler.handleTextMessageForTest(serverHello)
        handler.handleTextMessageForTest(activate(allRoles))
    }

    // ========== full state ==========

    @Test
    fun `a metadata object replaces the previous one instead of merging into it`() {
        metadata(0, "Song A")
        serverState(""""metadata":{"timestamp":1,"title":"Song B"}""")

        val current = handler.metadataUpdates.last()
        assertEquals("Song B", current.title)
        assertNull("artist was not in the new state", current.artist)
        assertNull("an omitted progress clears the position", current.progress)
    }

    @Test
    fun `a metadata object with only a timestamp clears the display`() {
        // What the reference server sends when nothing is playing.
        metadata(0, "Song A")
        serverState(""""metadata":{"timestamp":1}""")

        assertEquals(TrackMetadata(timestamp = 1), handler.metadataUpdates.last())
    }

    @Test
    fun `null leaves clear the display as omitted ones do`() {
        metadata(0, "Song A")
        serverState(
            """"metadata":{"timestamp":1,"title":null,"artist":null,"album":null,""" +
                """"artwork_url":null,"progress":null}"""
        )

        assertEquals(TrackMetadata(timestamp = 1), handler.metadataUpdates.last())
    }

    @Test
    fun `a message without a role object leaves that role's state alone`() {
        metadata(0, "Song A")
        serverState(controller)

        assertEquals(listOf<String?>("Song A"), titles())
        assertEquals(1, handler.controllerStateUpdates.size)

        metadata(1, "Song B")
        assertEquals(1, handler.controllerStateUpdates.size)
    }

    @Test
    fun `a controller object replaces the previous one instead of merging into it`() {
        serverState(controller)
        serverState(
            """"controller":{"supported_commands":["play"],"volume":80,"muted":false,""" +
                """"repeat":"off","shuffle":false}"""
        )

        val current = handler.controllerStateUpdates.last()
        assertEquals(80, current.volume)
        assertEquals(listOf("play"), current.supportedCommands)
        assertNull("seek is no longer offered, so its range is gone", current.seekMaxMs)
    }

    // ========== scheduled metadata ==========

    @Test
    fun `a future timestamp stays pending until it is reached`() {
        syncClock()
        metadata(past, "Now")

        metadata(future, "Next")
        assertEquals(listOf<String?>("Now"), titles())
        advance(14)
        assertEquals(listOf<String?>("Now"), titles())
        advance(2)

        assertEquals(listOf<String?>("Now", "Next"), titles())
    }

    @Test
    fun `a new scheduled update replaces the pending one`() {
        syncClock()
        metadata(past, "Now")

        metadata(future, "Next")
        metadata(serverTime(30), "Other")
        advance(20)
        assertEquals(listOf<String?>("Now"), titles())
        advance(11)

        assertEquals(listOf<String?>("Now", "Other"), titles())
    }

    @Test
    fun `a current state cancels the pending update`() {
        // "To cancel a scheduled update, resend the current state with a past
        // or present timestamp."
        syncClock()
        metadata(past, "Now")
        metadata(future, "Next")

        metadata(past, "Now")
        advance(60)

        assertEquals(listOf<String?>("Now", "Now"), titles())
    }

    @Test
    fun `a message without metadata leaves the pending update in place`() {
        syncClock()
        metadata(future, "Next")

        serverState(controller)
        advance(16)

        assertEquals(listOf<String?>("Next"), titles())
    }

    @Test
    fun `metadata is applied at once while the time filter has no estimate`() {
        metadata(Long.MAX_VALUE / 2, "Next")

        assertEquals(listOf<String?>("Next"), titles())
    }

    @Test
    fun `a pending update does not survive the connection`() {
        syncClock()
        metadata(future, "Next")

        handler.resetConnectionStateForTest()
        advance(60)

        assertEquals(emptyList<String?>(), titles())
    }

    // ========== roles removed by server/activate ==========

    @Test
    fun `removing the metadata role discards its state and its pending update`() {
        syncClock()
        metadata(past, "Now")
        metadata(future, "Next")

        handler.handleTextMessageForTest(activate("\"player@v1\",\"controller@v1\",\"artwork@v1\""))
        advance(60)

        assertEquals(listOf<String?>("Now", null), titles())
        assertEquals(TrackMetadata(), handler.metadataUpdates.last())
    }

    @Test
    fun `removing the controller role discards its state`() {
        serverState(controller)

        handler.handleTextMessageForTest(activate("\"player@v1\",\"metadata@v1\",\"artwork@v1\""))

        assertEquals(ControllerState(), handler.controllerStateUpdates.last())
        // A controller object is carried "only if the controller role is
        // active", so one for the removed role is not taken up.
        serverState(controller)
        assertEquals(2, handler.controllerStateUpdates.size)
        // With no controller state there is no supported_commands to hold a
        // command against, and once the role is back the same state is news.
        handler.handleTextMessageForTest(activate(allRoles))
        serverState(controller)
        assertEquals(3, handler.controllerStateUpdates.size)
    }

    @Test
    fun `removing the player role ends a stream the server left open`() {
        handler.handleTextMessageForTest(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,""" +
                """"channels":2,"bit_depth":16}}}"""
        )

        val chunk = ByteArray(17).also { it[0] = 4 }
        handler.handleBinaryMessageForTest(chunk)
        assertEquals(1, handler.audioChunks.size)

        handler.handleTextMessageForTest(activate("\"controller@v1\",\"metadata@v1\""))

        assertEquals(1, handler.streamEnds)
        // The stream is over: a chunk still in flight is not played.
        handler.handleBinaryMessageForTest(chunk)
        assertEquals(1, handler.audioChunks.size)
    }

    @Test
    fun `removing the artwork role clears the image and discards the pending one`() {
        syncClock()
        handler.handleTextMessageForTest(
            """{"type":"stream/start","payload":{"artwork":{"channels":[""" +
                """{"source":"album","format":"jpeg","width":500,"height":500}]}}}"""
        )
        val image = byteArrayOf(1, 2, 3)
        val announce = ByteArray(14).also {
            it[0] = 8
            it[1] = 2
            val timestamp = future
            for (i in 0 until 8) it[2 + i] = (timestamp shr (8 * (7 - i))).toByte()
            it[13] = image.size.toByte()
        }
        handler.handleBinaryMessageForTest(announce)
        handler.handleBinaryMessageForTest(byteArrayOf(8, 0) + image)

        handler.handleTextMessageForTest(activate("\"player@v1\",\"controller@v1\",\"metadata@v1\""))
        advance(60)

        assertEquals(listOf(emptyList<Byte>()), handler.artworkImages.map { it.toList() })
    }

    @Test
    fun `roles that stay active keep their state`() {
        syncClock()
        metadata(past, "Now")
        metadata(future, "Next")
        serverState(controller)

        // The same roles again, and an activation that omits them.
        handler.handleTextMessageForTest(activate(allRoles))
        handler.handleTextMessageForTest("""{"type":"server/activate","payload":{"activities":[]}}""")
        advance(16)

        assertEquals(listOf<String?>("Now", "Next"), titles())
        assertEquals(1, handler.controllerStateUpdates.size)
        assertEquals(0, handler.streamEnds)
    }

    @Test
    fun `losing playback capability removes every role`() {
        // "Implicit removal when the connection is no longer playback-capable":
        // an unpaired session whose unpaired access was withdrawn keeps no
        // roles, even though the activation did not mention them.
        metadata(0, "Now")
        serverState(controller)
        handler.unpairedAccess = false

        handler.handleTextMessageForTest("""{"type":"server/activate","payload":{"activities":[]}}""")

        assertEquals(TrackMetadata(), handler.metadataUpdates.last())
        assertEquals(ControllerState(), handler.controllerStateUpdates.last())
    }
}
