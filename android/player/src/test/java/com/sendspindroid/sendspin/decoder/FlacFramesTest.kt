package com.sendspindroid.sendspin.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacFramesTest {

    private val sampleRate = 48_000

    /** The header fields of a frame, by their codes (RFC 9639, section 9.1). */
    private data class Header(
        val blockSizeCode: Int = 12,       // 4096
        val explicitBlockSize: Int = 0,    // for codes 6 and 7
        val rateCode: Int = 0x0A,          // 48 kHz
        val rateValue: Int = 0,            // for codes 12-14
        val channelCode: Int = 1,          // stereo, left/right
        val depthCode: Int = 4,            // 16-bit
        val variableBlocking: Boolean = false,
        val number: Long = 0,              // frame number, or sample number when variable
    )

    /** The UTF-8-like coded number: 1 to 7 bytes. */
    private fun coded(n: Long): List<Int> {
        if (n < 0x80) return listOf(n.toInt())
        val bytes = (2..7).first { k -> n < (1L shl (if (k == 7) 36 else 5 * k + 1)) }
        val lead = if (bytes == 7) 0xFE else ((0xFF shl (8 - bytes)) and 0xFF) or (n shr (6 * (bytes - 1))).toInt()
        return listOf(lead) + (bytes - 2 downTo 0).map { 0x80 or ((n shr (6 * it)) and 0x3F).toInt() }
    }

    /** One FLAC frame: [h] as its header, [body], CRC-16 footer. */
    private fun frame(h: Header, body: ByteArray): ByteArray {
        val header = mutableListOf(
            0xFF, if (h.variableBlocking) 0xF9 else 0xF8,
            (h.blockSizeCode shl 4) or h.rateCode, (h.channelCode shl 4) or (h.depthCode shl 1),
        )
        header += coded(h.number)
        when (h.blockSizeCode) {
            6 -> header += h.explicitBlockSize - 1
            7 -> header += listOf((h.explicitBlockSize - 1) shr 8, (h.explicitBlockSize - 1) and 0xFF)
        }
        when (h.rateCode) {
            12 -> header += h.rateValue
            13, 14 -> header += listOf(h.rateValue shr 8, h.rateValue and 0xFF)
        }
        val headerBytes = header.map { it.toByte() }.toByteArray()
        val withCrc8 = headerBytes + FlacFrames.crc8(headerBytes, 0, headerBytes.size).toByte()
        val frame = withCrc8 + body
        val crc = FlacFrames.crc16(frame, 0, frame.size)
        return frame + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun frame(blockSizeCode: Int, frameNumber: Int, body: ByteArray, explicitBlockSize: Int = 0): ByteArray =
        frame(Header(blockSizeCode = blockSizeCode, explicitBlockSize = explicitBlockSize, number = frameNumber.toLong()), body)

    private fun body(seed: Int, size: Int = 64) = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }

    @Test
    fun `the checksums are FLAC's`() {
        val check = "123456789".toByteArray()
        assertEquals("CRC-8, polynomial 0x07", 0xF4, FlacFrames.crc8(check, 0, check.size))
        assertEquals("CRC-16, polynomial 0x8005", 0xFEE8, FlacFrames.crc16(check, 0, check.size))
    }

    @Test
    fun `block sizes are read from the frame header`() {
        assertEquals(4096, FlacFrames.headerBlockSize(frame(12, 0, body(1)), 0))
        assertEquals(1152, FlacFrames.headerBlockSize(frame(3, 0, body(1)), 0))
        assertEquals(192, FlacFrames.headerBlockSize(frame(1, 0, body(1)), 0))
        assertEquals(100, FlacFrames.headerBlockSize(frame(6, 0, body(1), explicitBlockSize = 100), 0))
    }

    @Test
    fun `a header with the wrong CRC-8 is no header`() {
        val damaged = frame(12, 0, body(1))
        damaged[5] = (damaged[5] + 1).toByte()  // the CRC-8, after a one-byte frame number
        assertEquals(-1, FlacFrames.headerBlockSize(damaged, 0))
    }

    @Test
    fun `a chunk of one frame is decoded as it is`() {
        val chunk = frame(12, 0, body(1))

        val frames = FlacFrames.split(chunk, 1_000_000L, sampleRate)

        assertEquals(1, frames.size)
        assertSame(chunk, frames[0].data)
        assertEquals(1_000_000L, frames[0].timestampUs)
    }

    @Test
    fun `a chunk of several frames is split, each at its own time`() {
        val a = frame(12, 0, body(1))
        val b = frame(12, 1, body(2))
        val c = frame(3, 2, body(3, size = 40))

        val frames = FlacFrames.split(a + b + c, 1_000_000L, sampleRate)

        assertEquals(3, frames.size)
        assertArrayEquals(a, frames[0].data)
        assertArrayEquals(b, frames[1].data)
        assertArrayEquals(c, frames[2].data)
        assertEquals(1_000_000L, frames[0].timestampUs)
        assertEquals(1_000_000L + 4096 * 1_000_000L / sampleRate, frames[1].timestampUs)
        assertEquals(1_000_000L + 8192 * 1_000_000L / sampleRate, frames[2].timestampUs)
    }

    @Test
    fun `frames of every header layout are split`() {
        val layouts = listOf(
            Header(channelCode = 8), Header(channelCode = 9), Header(channelCode = 10), Header(channelCode = 0),
            Header(number = 128), Header(number = 70_000), Header(number = 1L shl 30),
            Header(variableBlocking = true, number = 1L shl 35),
            Header(rateCode = 12, rateValue = 12), Header(rateCode = 13, rateValue = 11_025),
            Header(rateCode = 14, rateValue = 3_780),
            Header(blockSizeCode = 6, explicitBlockSize = 100), Header(blockSizeCode = 7, explicitBlockSize = 1000),
            Header(depthCode = 7), Header(depthCode = 1), Header(depthCode = 6), Header(depthCode = 0),
        )
        for (h in layouts) {
            val frames = (0..2).map { frame(h.copy(number = h.number + it), body(it + 1)) }

            val split = FlacFrames.split(frames.reduce { a, b -> a + b }, 0L, sampleRate)

            assertEquals("$h", 3, split.size)
            for (i in 0..2) assertArrayEquals("$h frame $i", frames[i], split[i].data)
        }
    }

    @Test
    fun `each frame is timed after the samples of the frames before it, whatever their block sizes`() {
        val chunk = frame(3, 0, body(1)) + frame(12, 1, body(2)) + frame(7, 2, body(3), explicitBlockSize = 1000)

        val frames = FlacFrames.split(chunk, 1_000_000L, sampleRate)

        assertEquals(
            listOf(1_000_000L, 1_000_000L + 1152 * 1_000_000L / sampleRate, 1_000_000L + 5248 * 1_000_000L / sampleRate),
            frames.map { it.timestampUs },
        )
    }

    @Test
    fun `a chunk of headers that close no frame is scanned in linear time`() {
        // Every sixth byte a valid header, none but by chance closing a frame:
        // each candidate must not cost a pass over the frame so far (it took
        // 80 s for 256 KiB that way)
        val header = frame(12, 0, ByteArray(0)).copyOf(6)
        val chunk = ByteArray(256 * 1024) { header[it % header.size] }

        val began = System.nanoTime()
        FlacFrames.split(chunk, 0L, sampleRate)
        val tookMs = (System.nanoTime() - began) / 1_000_000

        assertTrue("took ${tookMs}ms", tookMs < 2_000)
    }

    @Test
    fun `data that looks like a frame header does not split a frame`() {
        // A valid header in the middle of a frame's audio: the bytes before
        // it do not end with a CRC-16 of a frame
        val lookalike = frame(12, 5, body(9)).copyOf(6)
        val a = frame(12, 0, body(1, size = 20) + lookalike + body(4, size = 20))
        val b = frame(12, 1, body(2))

        val frames = FlacFrames.split(a + b, 0L, sampleRate)

        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0].data)
        assertArrayEquals(b, frames[1].data)
    }

    @Test
    fun `a chunk that does not start with a frame header is decoded as it is`() {
        val chunk = body(1) + frame(12, 0, body(2))

        val frames = FlacFrames.split(chunk, 7L, sampleRate)

        assertEquals(1, frames.size)
        assertSame(chunk, frames[0].data)
    }
}
