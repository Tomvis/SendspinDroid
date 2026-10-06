package com.sendspindroid.sendspin.protocol.message

import org.junit.Assert.*
import org.junit.Test

/**
 * Receiver side of the artwork transfers in `roles/artwork/v1.md`.
 *
 * The hex messages below were packed by aiosendspin 10.0.0
 * (`pack_artwork_announce`, `pack_artwork_parts`, `pack_artwork_cancel`), so
 * the layout is checked against the server that sends it. Each is a whole
 * message, type byte first.
 */
class ArtworkReceiverTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Hand a whole message over the way the codec does: type, then the rest. */
    private fun ArtworkReceiver.message(message: ByteArray): ArtworkReceiver.Result =
        accept(message[0].toInt() and 0xFF, message.copyOfRange(1, message.size))

    private fun ArtworkReceiver.message(messageHex: String) = message(hex(messageHex))

    private fun image(result: ArtworkReceiver.Result): ArtworkReceiver.Result.Image {
        assertTrue("expected an image, got $result", result is ArtworkReceiver.Result.Image)
        return result as ArtworkReceiver.Result.Image
    }

    private fun assertError(result: ArtworkReceiver.Result) =
        assertTrue("expected a protocol error, got $result", result is ArtworkReceiver.Result.ProtocolError)

    // --- Reference transfers ---

    @Test
    fun announceThenPartCompletesTheImage() {
        val r = ArtworkReceiver()
        // pack_artwork_announce(0, 0x0102030405060708, 10)
        assertEquals(ArtworkReceiver.Result.Discard(0), r.message("080201020304050607080000000a"))
        // pack_artwork_parts(0, bytes(range(1, 11)))
        val image = image(r.message("08000102030405060708090a"))
        assertEquals(0, image.channel)
        assertEquals(0x0102030405060708L, image.timestampMicros)
        assertArrayEquals(ByteArray(10) { (it + 1).toByte() }, image.data)
    }

    @Test
    fun channelComesFromTheMessageType() {
        val r = ArtworkReceiver()
        // pack_artwork_announce(2, 5, 3), pack_artwork_parts(2, b"\xaa\xbb\xcc")
        assertEquals(ArtworkReceiver.Result.Discard(2), r.message("0a02000000000000000500000003"))
        val image = image(r.message("0a00aabbcc"))
        assertEquals(2, image.channel)
        assertEquals(5L, image.timestampMicros)
        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC), image.data)
    }

    @Test
    fun announceOfAnEmptyImageCompletesAtOnce() {
        // pack_artwork_announce(0, -1, 0): a clear. The timestamp is a signed int64.
        val image = image(ArtworkReceiver().message("0802ffffffffffffffff00000000"))
        assertEquals(0, image.channel)
        assertEquals(-1L, image.timestampMicros)
        assertEquals(0, image.data.size)
    }

    @Test
    fun imageSplitAcrossPartsIsReassembledInOrder() {
        // A 150000-byte image, split as pack_artwork_parts splits it: 65517
        // bytes of data per part, so three parts of 65519, 65519 and 18968 bytes.
        val big = ByteArray(150_000) { (it % 251).toByte() }
        val r = ArtworkReceiver()
        // pack_artwork_announce(0, 1_000_000, 150000)
        assertEquals(ArtworkReceiver.Result.Discard(0), r.message("080200000000000f4240000249f0"))
        assertEquals(ArtworkReceiver.Result.None, r.message(bytes(8, 0) + big.copyOfRange(0, 65517)))
        assertEquals(ArtworkReceiver.Result.None, r.message(bytes(8, 0) + big.copyOfRange(65517, 131034)))
        val image = image(r.message(bytes(8, 0) + big.copyOfRange(131034, 150_000)))
        assertEquals(1_000_000L, image.timestampMicros)
        assertArrayEquals(big, image.data)
    }

    @Test
    fun aSecondTransferFollowsACompletedOne() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        image(r.message("08000102030405060708090a"))
        assertEquals(ArtworkReceiver.Result.Discard(2), r.message("0a02000000000000000500000003"))
        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC), image(r.message("0a00aabbcc")).data)
    }

    // --- Cancel ---

    @Test
    fun cancelDiscardsTheTransferInFlight() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        assertEquals(ArtworkReceiver.Result.None, r.message(bytes(8, 0, 1, 2, 3)))
        // pack_artwork_cancel(0)
        assertEquals(ArtworkReceiver.Result.Discard(0), r.message("0801"))
        // Nothing is in flight any more: a part is an error, an announce is not.
        assertError(r.message(bytes(8, 0, 4, 5, 6)))
        assertEquals(ArtworkReceiver.Result.Discard(0), r.message("080201020304050607080000000a"))
    }

    @Test
    fun cancelWithNothingInFlightStillDiscardsThePendingImage() {
        // A complete image waiting for its timestamp is pending too, and only
        // the caller holds it. pack_artwork_cancel(3)
        assertEquals(ArtworkReceiver.Result.Discard(3), ArtworkReceiver().message("0b01"))
    }

    @Test
    fun cancelOnAnotherChannelLeavesTheTransferInFlight() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        assertEquals(ArtworkReceiver.Result.Discard(3), r.message("0b01"))
        image(r.message("08000102030405060708090a"))
    }

    @Test
    fun resetForgetsTheTransferInFlight() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        r.reset()
        assertEquals(ArtworkReceiver.Result.Discard(0), r.message("080201020304050607080000000a"))
    }

    // --- Malformed messages: protocol errors ---

    @Test
    fun messageShorterThanTwoBytesIsAnError() {
        assertError(ArtworkReceiver().accept(8, ByteArray(0)))
    }

    @Test
    fun messageOverTheSizeCapIsAnError() {
        val r = ArtworkReceiver()
        r.message("080200000000000f4240000249f0")
        // 65519 bytes is the largest message allowed; one more is malformed.
        assertEquals(ArtworkReceiver.Result.None, r.message(ByteArray(65519).also { it[0] = 8 }))
        assertError(r.message(ByteArray(65520).also { it[0] = 8 }))
    }

    @Test
    fun announceThatIsNotFourteenBytesIsAnError() {
        assertError(ArtworkReceiver().message("080201020304050607080000000a00"))
        assertError(ArtworkReceiver().message("0802010203040506070800000a"))
    }

    @Test
    fun cancelLongerThanTwoBytesIsAnError() {
        assertError(ArtworkReceiver().message("080100"))
    }

    @Test
    fun reservedFlagBitIsAnError() {
        for (bit in 2..7) {
            assertError(ArtworkReceiver().message(bytes(8, 1 shl bit)))
            assertError(ArtworkReceiver().message(bytes(8, (1 shl bit) or 1)))
        }
    }

    @Test
    fun cancelAndAnnounceBothSetIsAnError() {
        assertError(ArtworkReceiver().message(bytes(8, 3)))
        assertError(ArtworkReceiver().message(hex("080301020304050607080000000a")))
    }

    // --- Malformed sequences: protocol errors ---

    @Test
    fun announceWhileATransferIsInFlightIsAnError() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        assertError(r.message("080201020304050607080000000a"))
    }

    @Test
    fun announceOnAnotherChannelWhileATransferIsInFlightIsAnError() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        assertError(r.message("0a02000000000000000500000003"))
        // An empty image is an announce like any other.
        assertError(r.message("0802ffffffffffffffff00000000"))
    }

    @Test
    fun partWithNoTransferInFlightIsAnError() {
        assertError(ArtworkReceiver().message("0a00aabbcc"))
    }

    @Test
    fun partOnAnotherChannelThanTheTransferIsAnError() {
        val r = ArtworkReceiver()
        r.message("080201020304050607080000000a")
        assertError(r.message("0a00aabbcc"))
    }

    @Test
    fun partExtendingPastTotalSizeIsAnError() {
        val r = ArtworkReceiver()
        r.message("0a02000000000000000500000003")
        assertEquals(ArtworkReceiver.Result.None, r.message(bytes(10, 0, 0xAA, 0xBB)))
        assertError(r.message(bytes(10, 0, 0xCC, 0xDD)))
    }

    // --- Not errors ---

    @Test
    fun partWithNoDataDuringATransferIsAccepted() {
        // `[type][flags]` with no flag set: two bytes, so not too short, and
        // it extends nothing past total_size.
        val r = ArtworkReceiver()
        r.message("0a02000000000000000500000003")
        assertEquals(ArtworkReceiver.Result.None, r.message(bytes(10, 0)))
        image(r.message("0a00aabbcc"))
    }

    @Test
    fun malformedReportsOnlyTheMessageRules() {
        val r = ArtworkReceiver()
        // Well formed on their own, whatever the sequence.
        assertNull(r.malformed(hex("0201020304050607080000000a")))
        assertNull(r.malformed(bytes(0, 0xAA)))
        assertNull(r.malformed(bytes(1)))
        assertNotNull(r.malformed(ByteArray(0)))
        assertNotNull(r.malformed(bytes(4)))
        assertNotNull(r.malformed(bytes(1, 0)))
    }

    @Test
    fun imageLargerThanTheCeilingIsAnError() {
        // total_size 0xFFFFFFFF: a 14-byte announce must not allocate 4 GB.
        assertError(ArtworkReceiver().message("08020000000000000000ffffffff"))
    }
}
