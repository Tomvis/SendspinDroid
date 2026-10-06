package com.sendspindroid.sendspin.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sendspin Opus is container-less: "exactly one Opus packet per chunk, with no
 * container. codec_header is absent". Every decoded sample belongs at its
 * chunk's timestamp, so the decoder must not be told to discard any.
 */
class OpusDecoderTest {

    private val csd = mutableMapOf<String, ByteBuffer>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        val format = mockk<MediaFormat>(relaxed = true)
        every { format.setByteBuffer(any(), any()) } answers {
            csd[firstArg()] = secondArg()
        }
        mockkStatic(MediaFormat::class)
        every { MediaFormat.createAudioFormat(any(), any(), any()) } returns format
        mockkStatic(MediaCodec::class)
        every { MediaCodec.createDecoderByType(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `configures no pre-skip and no seek pre-roll`() {
        OpusDecoder().configure(sampleRate = 48000, channels = 2, bitDepth = 16, codecHeader = null)

        // OpusHead pre-skip: 16-bit little-endian at offset 10 (RFC 7845).
        val opusHead = csd.getValue("csd-0").duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, opusHead.getShort(10).toInt())
        // csd-1 (codec delay) and csd-2 (seek pre-roll): native-order 64-bit ns.
        assertEquals(0L, csd.getValue("csd-1").duplicate().order(ByteOrder.nativeOrder()).getLong(0))
        assertEquals(0L, csd.getValue("csd-2").duplicate().order(ByteOrder.nativeOrder()).getLong(0))
    }
}
