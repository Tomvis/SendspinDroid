package com.sendspindroid.sendspin.decoder

/**
 * Interface for audio decoders that convert compressed audio to PCM.
 *
 * Implementations handle specific codecs (PCM pass-through, FLAC, OPUS)
 * and are created via [AudioDecoderFactory].
 */
class DecodedAudio(val timestampUs: Long, val pcm: ByteArray)

interface AudioDecoder {

    /**
     * Configure the decoder with stream parameters.
     *
     * @param sampleRate Audio sample rate in Hz (e.g., 48000)
     * @param channels Number of audio channels (1 = mono, 2 = stereo)
     * @param bitDepth Bits per sample (typically 16)
     * @param codecHeader Optional codec-specific header data (e.g., FLAC STREAMINFO, Opus header)
     */
    fun configure(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: ByteArray? = null
    )

    /**
     * Decode a compressed audio chunk to PCM.
     *
     * A decoder may hold a chunk back and return it from a later call, so the
     * result is whatever PCM is ready, each piece with the timestamp of the
     * chunk it came from - which is not always the chunk passed in. It can be
     * empty, or hold more than one piece.
     *
     * @param compressedData The compressed audio data from the server
     * @param timestampUs Server time at which this chunk's first sample plays
     * @return Decoded PCM (signed, little-endian, interleaved) with its timestamps
     * @throws IllegalStateException if decoder is not configured
     */
    fun decode(compressedData: ByteArray, timestampUs: Long): List<DecodedAudio>

    /**
     * Flush the decoder state.
     *
     * Call this on stream/clear or when seeking to reset internal buffers.
     * After flush, the decoder remains configured and ready to decode.
     */
    fun flush()

    /**
     * Release decoder resources.
     *
     * After release, the decoder cannot be used. Create a new instance if needed.
     */
    fun release()

    /**
     * Check if the decoder is properly configured and ready to decode.
     */
    val isConfigured: Boolean
}
