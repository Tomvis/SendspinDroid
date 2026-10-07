package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Binary messages as they come out of the wire codec: the type byte, then the
 * body.
 *
 * The messages below were packed by aiosendspin 10.0.0
 * (`pack_player_audio_header`, `pack_artwork_announce`, `pack_artwork_parts`,
 * `pack_artwork_cancel`), so the 13-byte audio header is checked against the
 * server that sends it and not against our own idea of it.
 */
class BinaryMessageParserTest {

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** Parse a whole reference message the way the codec hands it over. */
    private fun parse(messageHex: String): BinaryMessageParser.BinaryMessage? {
        val message = hex(messageHex)
        return BinaryMessageParser.parse(
            message[0].toInt() and 0xFF,
            message.copyOfRange(1, message.size),
        )
    }

    /** Two 16-bit stereo PCM frames; the payload of every audio vector. */
    private val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private fun audio(messageHex: String): BinaryMessageParser.BinaryMessage.Audio {
        val message = parse(messageHex)
        assertTrue("expected Audio, got $message", message is BinaryMessageParser.BinaryMessage.Audio)
        return message as BinaryMessageParser.BinaryMessage.Audio
    }

    // --- Audio chunks (type 4): [timestamp int64][send_ahead uint32][frame] ---

    @Test
    fun audioChunkYieldsTimestampSendAheadAndTheFrameAfterThe13ByteHeader() {
        // The first chunk the dev server streamed during verification.
        val chunk = audio("040000004abffdf318000762a00102030405060708")
        assertEquals(321_048_671_000L, chunk.timestampMicros)
        assertEquals(484_000L, chunk.sendAheadMicros)
        // The frame starts after byte 12. Read with the old 9-byte header it
        // would start four bytes early, with send_ahead as its first samples.
        assertArrayEquals(pcm, chunk.payload)
    }

    @Test
    fun headerFieldsAreBigEndian() {
        val chunk = audio("0401020304050607080a0b0c0d0102030405060708")
        assertEquals(0x0102030405060708L, chunk.timestampMicros)
        assertEquals(0x0A0B0C0DL, chunk.sendAheadMicros)
        assertArrayEquals(pcm, chunk.payload)
    }

    @Test
    fun sendAheadIsUnsignedAndSaturatesAtTheUint32Maximum() {
        // "The field saturates rather than wrapping": 0 when sent at or after
        // the timestamp, 4294967295 when the lead does not fit. Neither may
        // come out negative.
        val saturated = audio("047fffffffffffffffffffffff0102030405060708")
        assertEquals(Long.MAX_VALUE, saturated.timestampMicros)
        assertEquals(4_294_967_295L, saturated.sendAheadMicros)

        val zero = audio("040000000000000000000000000102030405060708")
        assertEquals(0L, zero.timestampMicros)
        assertEquals(0L, zero.sendAheadMicros)
    }

    @Test
    fun timestampIsASignedInt64() {
        val chunk = audio("04ffffffffffffffff000000010102030405060708")
        assertEquals(-1L, chunk.timestampMicros)
        assertEquals(1L, chunk.sendAheadMicros)
    }

    @Test
    fun aChunkThatIsExactlyTheHeaderHasAnEmptyFrame() {
        val chunk = audio("040000004abffdf318000762a0")
        assertEquals(0, chunk.payload.size)
    }

    @Test
    fun aChunkShorterThanTheHeaderIsDropped() {
        // 12 bytes: one short of type + timestamp + send_ahead.
        assertNull(parse("040000004abffdf318000762"))
        // What a pre-rc1 server would send for an empty frame: 9 bytes.
        assertNull(parse("040000004abffdf318"))
        assertNull(BinaryMessageParser.parse(4, ByteArray(0)))
    }

    @Test
    fun largeFramesAreNotTruncated() {
        val frame = ByteArray(4096) { (it % 256).toByte() }
        val chunk = BinaryMessageParser.parse(4, ByteArray(12) + frame)
            as BinaryMessageParser.BinaryMessage.Audio
        assertArrayEquals(frame, chunk.payload)
    }

    // --- Everything else is ignored ---

    @Test
    fun artworkMessagesAreIgnoredRatherThanMisreadAsTimestampedPayloads() {
        // roles/artwork/v1.md: announce [type][flags][timestamp][total_size],
        // part [type][flags][data], cancel [type][flags]. Not implemented yet,
        // so each is dropped; the pre-rc1 layout would have read the flags byte
        // and seven bytes of timestamp as a timestamp.
        assertNull("announce", parse("08020000004abfe33f2800000005"))
        assertNull("clear (announce, total_size 0)", parse("09020000004abfe33f2800000000"))
        assertNull("part", parse("0800ffd8ffe000"))
        assertNull("cancel", parse("0b01"))
    }

    @Test
    fun idsThisClientDoesNotImplementAreIgnored() {
        // "They MUST also ignore binary messages whose ID they do not
        // implement" - reserved (2-3), the rest of the player block (5-7),
        // source, visualizer, future roles and application-specific IDs.
        for (type in listOf(2, 3, 5, 6, 7, 12, 16, 23, 24, 191, 192, 255)) {
            assertNull("type $type", BinaryMessageParser.parse(type, ByteArray(32)))
            assertNull("type $type, empty", BinaryMessageParser.parse(type, ByteArray(0)))
        }
    }
}
