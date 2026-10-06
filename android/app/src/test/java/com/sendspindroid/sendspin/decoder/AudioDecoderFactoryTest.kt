package com.sendspindroid.sendspin.decoder

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AudioDecoderFactory.
 *
 * A codec the factory cannot decode must fail, not come back as a PCM
 * pass-through: compressed bytes written to the output as PCM are noise.
 */
class AudioDecoderFactoryTest {

    @Test
    fun `unknown codec has no decoder`() {
        assertThrows(IllegalArgumentException::class.java) { AudioDecoderFactory.create("aac") }
    }

    @Test
    fun `pcm codec returns PcmDecoder`() {
        val decoder = AudioDecoderFactory.create("pcm")
        assertTrue(
            "Codec 'pcm' should return PcmDecoder, got ${decoder::class.simpleName}",
            decoder is PcmDecoder
        )
    }

    @Test
    fun `codec names are case insensitive`() {
        assertTrue(AudioDecoderFactory.create("PCM") is PcmDecoder)
        assertThrows(IllegalArgumentException::class.java) { AudioDecoderFactory.create("AAC") }
    }

    @Test
    fun `empty codec has no decoder`() {
        assertThrows(IllegalArgumentException::class.java) { AudioDecoderFactory.create("") }
    }
}
