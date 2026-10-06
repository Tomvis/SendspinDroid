package com.sendspindroid.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The artwork stream as `roles/artwork/v1.md` defines it: what becomes the
 * current image, when, and what clears or discards it.
 *
 * The byte layout of the transfers is covered by `ArtworkReceiverTest`; here
 * the messages go through the handler, from the wire frame to `onArtwork`.
 */
class ArtworkStreamTest {

    private lateinit var handler: TestProtocolHandler

    private val artworkStart = """{"type":"stream/start","payload":{"artwork":{"channels":[""" +
        """{"source":"album","format":"jpeg","width":500,"height":500}]}}}"""

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val otherJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9, 9)

    /** A timestamp the time filter places this far from now, once [syncClock] ran. */
    private fun serverTime(offsetSeconds: Long) = System.nanoTime() / 1000 + offsetSeconds * 1_000_000

    private val past get() = serverTime(-1)
    private val future get() = serverTime(60)

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        handler.setHandshakeCompleteForTest()
    }

    /** Give the time filter an estimate: server and client clocks equal. */
    private fun syncClock() {
        val filter = handler.exposedTimeFilter()
        val now = System.nanoTime() / 1000
        filter.addMeasurement(0, 1000, now - 1000)
        filter.addMeasurement(0, 1000, now)
        assertTrue(filter.isReady)
    }

    private fun announce(timestampMicros: Long, totalSize: Int, channel: Int = 0) {
        val message = ByteArray(14)
        message[0] = (8 + channel).toByte()
        message[1] = 2
        for (i in 0 until 8) message[2 + i] = (timestampMicros shr (8 * (7 - i))).toByte()
        for (i in 0 until 4) message[10 + i] = (totalSize shr (8 * (3 - i))).toByte()
        handler.handleBinaryMessageForTest(message)
    }

    private fun part(data: ByteArray, channel: Int = 0) =
        handler.handleBinaryMessageForTest(byteArrayOf((8 + channel).toByte(), 0) + data)

    private fun cancel(channel: Int = 0) =
        handler.handleBinaryMessageForTest(byteArrayOf((8 + channel).toByte(), 1))

    private fun sendImage(timestampMicros: Long, image: ByteArray, channel: Int = 0) {
        announce(timestampMicros, image.size, channel)
        if (image.isNotEmpty()) part(image, channel)
    }

    private fun advance(seconds: Long) {
        handler.testScheduler.advanceTimeBy(seconds * 1000)
        handler.testScheduler.runCurrent()
    }

    private fun assertImages(vararg expected: ByteArray) {
        assertEquals(
            expected.map { it.toList() },
            handler.artworkImages.map { it.toList() },
        )
    }

    // ========== becoming current ==========

    @Test
    fun `an image whose timestamp has passed becomes current when it completes`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        announce(past, jpeg.size)
        assertImages()
        part(jpeg.copyOfRange(0, 4))
        assertImages()
        part(jpeg.copyOfRange(4, jpeg.size))

        assertImages(jpeg)
        assertEquals(listOf(0), handler.artworkDeliveries)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    @Test
    fun `an image is never dropped for lateness`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(serverTime(-3600), jpeg)

        assertImages(jpeg)
    }

    @Test
    fun `a future image stays pending until its timestamp is reached`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(future, jpeg)
        assertImages()
        advance(59)
        assertImages()
        advance(2)

        assertImages(jpeg)
    }

    @Test
    fun `an image is shown at once while the time filter has no estimate`() {
        handler.handleTextMessageForTest(artworkStart)

        sendImage(Long.MAX_VALUE / 2, jpeg)

        assertImages(jpeg)
    }

    @Test
    fun `an image on a channel that was not declared is not shown`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(past, jpeg, channel = 1)

        assertImages()
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    // ========== pending image ==========

    @Test
    fun `an announce discards the pending image`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(future, jpeg)
        announce(serverTime(120), otherJpeg.size)
        // The first image's timestamp passes while the second is in flight.
        advance(90)
        assertImages()

        part(otherJpeg)
        advance(121)
        assertImages(otherJpeg)
    }

    @Test
    fun `a current image replaces a pending one`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(future, jpeg)
        sendImage(past, otherJpeg)
        advance(120)

        assertImages(otherJpeg)
    }

    @Test
    fun `cancel discards the pending image and leaves the current one`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(past, jpeg)
        sendImage(future, otherJpeg)
        cancel()
        advance(120)

        assertImages(jpeg)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    @Test
    fun `cancel discards a transfer in flight`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        announce(past, jpeg.size)
        part(jpeg.copyOfRange(0, 2))
        cancel()
        sendImage(past, otherJpeg)

        assertImages(otherJpeg)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    // ========== clearing ==========

    @Test
    fun `an announce with total_size 0 clears the image`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(past, jpeg)
        announce(past, 0)

        assertImages(jpeg, ByteArray(0))
    }

    @Test
    fun `a future clear is scheduled like any other image`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)

        sendImage(past, jpeg)
        announce(future, 0)
        assertImages(jpeg)
        advance(61)

        assertImages(jpeg, ByteArray(0))
    }

    // ========== stream/end ==========

    @Test
    fun `stream end for artwork clears the current image and discards the pending one`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, jpeg)
        sendImage(future, otherJpeg)

        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{"roles":["artwork"]}}""")
        advance(120)

        assertImages(jpeg, ByteArray(0))
    }

    @Test
    fun `stream end without roles ends the artwork stream too`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, jpeg)

        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{}}""")

        assertImages(jpeg, ByteArray(0))
    }

    @Test
    fun `stream end for the player leaves artwork alone`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, jpeg)
        sendImage(future, otherJpeg)

        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{"roles":["player"]}}""")
        advance(61)

        assertImages(jpeg, otherJpeg)
    }

    @Test
    fun `stream end with no artwork stream active clears nothing`() {
        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{}}""")

        assertImages()
    }

    @Test
    fun `artwork after stream end is ignored until the stream starts again`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{"roles":["artwork"]}}""")
        handler.artworkImages.clear()

        sendImage(past, jpeg)
        assertImages()
        assertEquals(emptyList<String>(), handler.protocolFailures)

        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, otherJpeg)
        assertImages(otherJpeg)
    }

    @Test
    fun `a transfer in flight does not survive stream end`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        announce(past, jpeg.size)
        handler.handleTextMessageForTest("""{"type":"stream/end","payload":{"roles":["artwork"]}}""")
        handler.handleTextMessageForTest(artworkStart)
        handler.artworkImages.clear()

        // A new announce, not an announce during a transfer.
        sendImage(past, otherJpeg)

        assertImages(otherJpeg)
        assertEquals(emptyList<String>(), handler.protocolFailures)
    }

    @Test
    fun `a pending image does not outlive its connection`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(future, jpeg)

        // The next connection's hello: the stream of the old one is gone.
        handler.handleTextMessageForTest("""{"type":"server/hello","payload":{"name":"Dev"}}""")
        advance(120)

        assertImages()
    }

    // ========== stream/start and stream/clear ==========

    @Test
    fun `stream start for artwork alone does not start a player stream`() {
        handler.handleTextMessageForTest(artworkStart)

        assertEquals(0, handler.streamStarts.size)
    }

    @Test
    fun `stream start carrying player and artwork starts both`() {
        syncClock()
        handler.handleTextMessageForTest(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,""" +
                """"channels":2,"bit_depth":16},"artwork":{"channels":[""" +
                """{"source":"album","format":"jpeg","width":500,"height":500}]}}}"""
        )
        sendImage(past, jpeg)

        assertEquals(1, handler.streamStarts.size)
        assertImages(jpeg)
    }

    @Test
    fun `stream start that changes the channel configuration discards the pending image`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, jpeg)
        sendImage(future, otherJpeg)

        handler.handleTextMessageForTest(artworkStart.replace("500", "300"))
        advance(120)

        assertImages(jpeg)
    }

    @Test
    fun `stream start repeating the configuration keeps the pending image`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(future, jpeg)

        handler.handleTextMessageForTest(artworkStart)
        advance(61)

        assertImages(jpeg)
    }

    @Test
    fun `stream clear does not touch artwork`() {
        syncClock()
        handler.handleTextMessageForTest(artworkStart)
        sendImage(past, jpeg)
        sendImage(future, otherJpeg)

        // "If omitted, clears all active player and visualizer streams."
        handler.handleTextMessageForTest("""{"type":"stream/clear","payload":{}}""")
        advance(61)

        assertImages(jpeg, otherJpeg)
    }

    // ========== protocol errors ==========

    @Test
    fun `a malformed artwork message is a protocol failure`() {
        handler.handleTextMessageForTest(artworkStart)

        handler.handleBinaryMessageForTest(byteArrayOf(8, 4))

        assertEquals(1, handler.protocolFailures.size)
        assertImages()
    }

    @Test
    fun `a malformed artwork message is a protocol failure outside a stream too`() {
        handler.handleBinaryMessageForTest(byteArrayOf(8, 3))

        assertEquals(1, handler.protocolFailures.size)
    }

    @Test
    fun `a part with no transfer in flight is a protocol failure within a stream`() {
        handler.handleTextMessageForTest(artworkStart)

        part(jpeg)

        assertEquals(1, handler.protocolFailures.size)
        assertImages()
    }

    @Test
    fun `an announce during a transfer is a protocol failure`() {
        handler.handleTextMessageForTest(artworkStart)

        announce(0, jpeg.size)
        announce(0, jpeg.size)

        assertEquals(1, handler.protocolFailures.size)
    }

    @Test
    fun `a part past total_size is a protocol failure`() {
        handler.handleTextMessageForTest(artworkStart)

        announce(0, 2)
        part(jpeg)

        assertEquals(1, handler.protocolFailures.size)
        assertImages()
    }

    // ========== low-memory mode ==========

    @Test
    fun `low memory mode does not advertise the artwork role`() {
        handler.lowMemoryMode = true
        handler.handleTextMessageForTest("""{"type":"server/hello","payload":{"name":"Dev"}}""")

        val hello = handler.sentMessages.single { it.contains("client/hello") }
        assertFalse(hello.contains("artwork"))
        assertTrue(hello.contains("\"player@v1\""))
    }

    @Test
    fun `the artwork role is advertised otherwise`() {
        handler.handleTextMessageForTest("""{"type":"server/hello","payload":{"name":"Dev"}}""")

        val hello = handler.sentMessages.single { it.contains("client/hello") }
        assertTrue(hello.contains("\"artwork@v1\""))
    }

    @Test
    fun `client state carries no artwork object unless the role is active`() {
        handler.lowMemoryMode = true
        handler.handleTextMessageForTest("""{"type":"server/hello","payload":{"name":"Dev"}}""")
        // What a server activates for a hello that listed no artwork role.
        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":[],""" +
                """"active_roles":["player@v1","controller@v1","metadata@v1"]}}"""
        )

        val state = handler.sentMessages.last { it.contains("client/state") }
        assertFalse(state.contains("artwork"))
    }
}
