package com.sendspindroid.sendspin.decoder

import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus audio decoder using Android MediaCodec.
 *
 * Opus is a lossy compression codec optimized for both speech and music.
 * It's stateful - the decoder maintains internal state between frames for
 * better compression, so frames must be decoded in order.
 *
 * Sendspin Opus has no container and no codec_header: "exactly one Opus
 * packet per chunk ... the decoder is configured from the negotiated
 * sample_rate and channels". An OpusHead is synthesized because MediaCodec
 * requires one as CSD-0.
 */
class OpusDecoder : MediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS) {

    companion object {
        private const val TAG = "OpusDecoder"

        // Pre-skip and seek pre-roll are Ogg/WebM container concepts: samples
        // the decoder discards at the start of a file or after a seek. A
        // Sendspin chunk's samples all belong at that chunk's timestamp, so
        // nothing may be discarded.
        private const val PRE_SKIP_SAMPLES = 0
        private const val CODEC_DELAY_NS: Long = 0
        private const val SEEK_PRE_ROLL_NS: Long = 0

        // Size of a minimal OpusHead structure (RFC 7845) without channel mapping table
        private const val OPUS_HEAD_SIZE = 19
    }

    override fun configureFormat(
        format: MediaFormat,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray?
    ) {
        // Opus requires the OpusHead structure as CSD-0
        // OpusHead format (RFC 7845):
        // - Magic signature "OpusHead" (8 bytes)
        // - Version (1 byte)
        // - Channel count (1 byte)
        // - Pre-skip (2 bytes, little-endian)
        // - Sample rate (4 bytes, little-endian)
        // - Output gain (2 bytes, little-endian)
        // - Channel mapping family (1 byte)
        // - [Optional channel mapping table]
        if (codecHeader != null && codecHeader.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(codecHeader))
            Log.d(TAG, "Set Opus header from codec_header (${codecHeader.size} bytes)")
        } else {
            // Create a minimal OpusHead if not provided
            val opusHead = createDefaultOpusHead(channels, sampleRate)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
            Log.d(TAG, "Using default Opus header")
        }

        // CSD-1: Codec delay (pre-skip) in nanoseconds. Overrides the pre-skip
        // in the OpusHead.
        // Note: Android MediaCodec requires native byte order for CSD-1/CSD-2
        // (unsigned 64-bit native-order integer per the MediaCodec docs),
        // NOT the little-endian order used by the Opus RFC 7845 OpusHead structure.
        val preSkipBuffer = ByteBuffer.allocate(8)
            .order(ByteOrder.nativeOrder())
            .putLong(CODEC_DELAY_NS)
        preSkipBuffer.flip()
        format.setByteBuffer("csd-1", preSkipBuffer)

        // CSD-2: Seek pre-roll in nanoseconds, discarded after a flush.
        // Same native byte order requirement as CSD-1 (see note above).
        val seekPreRollBuffer = ByteBuffer.allocate(8)
            .order(ByteOrder.nativeOrder())
            .putLong(SEEK_PRE_ROLL_NS)
        seekPreRollBuffer.flip()
        format.setByteBuffer("csd-2", seekPreRollBuffer)

        Log.d(TAG, "Opus format configured: ${sampleRate}Hz, ${channels}ch")
    }

    /**
     * Create a minimal OpusHead structure for the given parameters.
     */
    private fun createDefaultOpusHead(channels: Int, sampleRate: Int): ByteArray {
        val buffer = ByteBuffer.allocate(OPUS_HEAD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Magic signature
        buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))

        // Version (1)
        buffer.put(1.toByte())

        // Channel count
        buffer.put(channels.toByte())

        // Pre-skip (little-endian, 16-bit)
        buffer.putShort(PRE_SKIP_SAMPLES.toShort())

        // Input sample rate (little-endian, 32-bit)
        // Note: Opus always uses 48kHz internally, but this field indicates original rate
        buffer.putInt(sampleRate)

        // Output gain (0 = no gain adjustment)
        buffer.putShort(0)

        // Channel mapping family (0 = mono/stereo with standard mapping)
        buffer.put(0.toByte())

        return buffer.array()
    }
}
