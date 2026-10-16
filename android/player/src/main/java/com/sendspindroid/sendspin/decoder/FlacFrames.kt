package com.sendspindroid.sendspin.decoder

/**
 * A FLAC chunk carries "one or more complete FLAC frames" (roles/player/v1.md,
 * "Codec framing"), and the platform decoder takes one frame per input buffer.
 * This finds the frames in a chunk.
 *
 * A frame starts with a header (a sync code, and a CRC-8 over the header) and
 * ends with a CRC-16 over the whole frame. A frame boundary is taken only
 * where both hold: a header starts there and the bytes before it end with the
 * CRC-16 of the frame they close, so audio data that happens to look like a
 * sync code does not split a frame. That CRC is kept running from the start of
 * the frame: one followed by its own CRC-16 leaves 0, so the scan is linear
 * whatever the chunk holds. Frame layout per RFC 9639, section 9.
 */
object FlacFrames {

    /** One frame of a chunk, and the timestamp of its first sample. */
    class Frame(val data: ByteArray, val timestampUs: Long)

    /**
     * The frames of [chunk], the first at [timestampUs] and each later one
     * after the samples of those before it. A chunk that does not start with
     * a frame header is returned whole.
     */
    fun split(chunk: ByteArray, timestampUs: Long, sampleRate: Int): List<Frame> {
        var blockSize = headerBlockSize(chunk, 0)
        if (blockSize < 0) return listOf(Frame(chunk, timestampUs))

        val frames = ArrayList<Frame>(1)
        var start = 0
        var samples = 0L
        var crc = 0  // CRC-16 of [start, p)
        for (p in 1 until chunk.size - 1) {
            crc = crc16Step(crc, chunk[p - 1])
            if (crc != 0 || p - start < MIN_FRAME_BYTES || !isSyncCode(chunk, p)) continue
            val next = headerBlockSize(chunk, p)
            if (next < 0) continue
            frames += Frame(chunk.copyOfRange(start, p), timestampUs + samples * 1_000_000L / sampleRate)
            samples += blockSize
            start = p
            crc = 0
            blockSize = next
        }
        if (frames.isEmpty()) return listOf(Frame(chunk, timestampUs))
        frames += Frame(chunk.copyOfRange(start, chunk.size), timestampUs + samples * 1_000_000L / sampleRate)
        return frames
    }

    // The header (at least 6 bytes) and the CRC-16 footer
    private const val MIN_FRAME_BYTES = 8

    private fun isSyncCode(b: ByteArray, p: Int): Boolean =
        b[p].toInt() and 0xFF == 0xFF && (b[p + 1].toInt() and 0xFE) == 0xF8

    /** The block size of a valid frame header at [p], in samples, or -1. */
    internal fun headerBlockSize(b: ByteArray, p: Int): Int {
        if (p + 6 > b.size || !isSyncCode(b, p)) return -1
        val blockSizeCode = (b[p + 2].toInt() shr 4) and 0x0F
        val sampleRateCode = b[p + 2].toInt() and 0x0F
        val channels = (b[p + 3].toInt() shr 4) and 0x0F
        val sampleSizeCode = (b[p + 3].toInt() shr 1) and 0x07
        if (blockSizeCode == 0 || sampleRateCode == 0x0F || channels > 10 ||
            sampleSizeCode == 3 || b[p + 3].toInt() and 1 != 0
        ) return -1

        // The coded frame or sample number: 1 to 7 bytes, UTF-8 style
        var q = p + 4
        val lead = b[q].toInt() and 0xFF
        val numberBytes = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            lead and 0xFC == 0xF8 -> 5
            lead and 0xFE == 0xFC -> 6
            lead == 0xFE -> 7
            else -> return -1
        }
        if (q + numberBytes > b.size) return -1
        for (i in 1 until numberBytes) if (b[q + i].toInt() and 0xC0 != 0x80) return -1
        q += numberBytes

        val blockSizeBytes = when (blockSizeCode) { 6 -> 1; 7 -> 2; else -> 0 }
        val sampleRateBytes = when (sampleRateCode) { 12 -> 1; 13, 14 -> 2; else -> 0 }
        if (q + blockSizeBytes + sampleRateBytes + 1 > b.size) return -1
        val blockSize = when (blockSizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> (b[q].toInt() and 0xFF) + 1
            7 -> (((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)) + 1
            else -> 256 shl (blockSizeCode - 8)
        }
        q += blockSizeBytes + sampleRateBytes

        return if (crc8(b, p, q) == b[q].toInt() and 0xFF) blockSize else -1
    }

    // CRC-8, polynomial x^8 + x^2 + x + 1, initial value 0
    internal fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    // CRC-16, polynomial x^16 + x^15 + x^2 + 1, initial value 0
    internal fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) crc = crc16Step(crc, b[i])
        return crc
    }

    private fun crc16Step(crc: Int, byte: Byte): Int =
        ((crc shl 8) and 0xFFFF) xor CRC16_TABLE[((crc shr 8) xor (byte.toInt() and 0xFF)) and 0xFF]

    private val CRC16_TABLE = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        crc
    }
}
