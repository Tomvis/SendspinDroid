package com.sendspindroid.sendspin.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.mockk.Ordering
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for [MediaCodecDecoder].
 *
 * Uses a concrete test subclass with a mock MediaCodec to verify:
 * - C-05: Input retry under queue pressure (no silent frame drops)
 * - C-06: flush() does not call start() in synchronous mode
 * - C-07: Output drain handles INFO_OUTPUT_FORMAT_CHANGED mid-stream
 */
class MediaCodecDecoderTest {

    private lateinit var mockCodec: MediaCodec
    private lateinit var decoder: TestMediaCodecDecoder

    /**
     * Concrete subclass that exposes the protected mediaCodec field for testing
     * and provides a no-op configureFormat.
     */
    private class TestMediaCodecDecoder : MediaCodecDecoder("audio/opus") {
        fun injectCodec(codec: MediaCodec) {
            mediaCodec = codec
        }

        override fun configureFormat(
            format: MediaFormat,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?
        ) {
            // No-op for tests
        }
    }

    @Before
    fun setUp() {
        // Mock android.util.Log which is unavailable on JVM
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockCodec = mockk(relaxed = true)
        decoder = TestMediaCodecDecoder()
        decoder.injectCodec(mockCodec)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Helper to create a ByteBuffer wrapping test data ----

    private fun testBuffer(size: Int): ByteBuffer {
        val data = ByteArray(size) { it.toByte() }
        return ByteBuffer.wrap(data)
    }

    private fun setupSingleOutputBuffer(pcmSize: Int) {
        val outBuffer = testBuffer(pcmSize)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )
        every { mockCodec.getOutputBuffer(0) } returns outBuffer
    }

    // =========================================================================
    // C-05: Input retry under queue pressure
    // =========================================================================

    @Test
    fun decode_inputBufferAvailable_submitsFrameImmediately() {
        // Input available on first attempt
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // No output
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(100) { 0x42 }
        decoder.decode(input, 0L)

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
    }

    @Test
    fun decode_inputBufferUnavailableThenAvailable_retriesAndSubmits() {
        // First attempt: no buffer. Second attempt: buffer available.
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Drain during retry returns nothing
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(50)
        decoder.decode(input, 0L)

        // Input was submitted on the second attempt
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 50, 0, 0) }
        // dequeueInputBuffer called twice (initial fail + retry)
        verify(exactly = 2) { mockCodec.dequeueInputBuffer(any()) }
    }

    @Test
    fun decode_inputBufferNeverAvailable_logsErrorAfterMaxRetries() {
        // All attempts fail
        every { mockCodec.dequeueInputBuffer(any()) } returns -1
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val input = ByteArray(200)
        decoder.decode(input, 0L)

        // Should have tried MAX_INPUT_RETRIES + 1 = 4 times
        verify(exactly = 4) { mockCodec.dequeueInputBuffer(any()) }
        // Frame was not submitted
        verify(exactly = 0) { mockCodec.queueInputBuffer(any(), any(), any(), any(), any()) }
        // Error was logged (not just a warning)
        verify { Log.e(any(), match { it.contains("Failed to submit input") }) }
    }

    @Test
    fun decode_inputRetry_drainsOutputBetweenAttempts() {
        // First two attempts fail, third succeeds
        every { mockCodec.dequeueInputBuffer(any()) } returnsMany listOf(-1, -1, 0)
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output drain yields a buffer on first drain call
        val outBuffer = testBuffer(960)
        var drainCallCount = 0
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            drainCallCount++
            if (drainCallCount == 1) {
                // Populate BufferInfo so drainOutput collects the data
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 960
                0 // buffer index
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        every { mockCodec.getOutputBuffer(0) } returns outBuffer

        val input = ByteArray(100)
        val result = decoder.decode(input, 0L)

        // Input was eventually submitted
        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 0, 0) }
        // Output was collected during the drain-between-retries
        assertTrue("Should have collected PCM output during retry drain", result.isNotEmpty())
    }

    // =========================================================================
    // C-06: flush() does not call start() in synchronous mode
    // =========================================================================

    @Test
    fun flush_doesNotCallStart() {
        decoder.flush()

        verify(exactly = 1) { mockCodec.flush() }
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_thenDecode_worksWithoutStart() {
        // After flush, decode should work by going directly to dequeueInputBuffer
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.flush()
        decoder.decode(ByteArray(50), 0L)

        verify(ordering = Ordering.ORDERED) {
            mockCodec.flush()
            mockCodec.dequeueInputBuffer(any())
        }
        // start() is never called
        verify(exactly = 0) { mockCodec.start() }
    }

    @Test
    fun flush_exceptionIsCaughtAndLogged() {
        every { mockCodec.flush() } throws IllegalStateException("test error")

        // Should not throw
        decoder.flush()

        verify { Log.e(any(), match { it.contains("Error flushing") }, any()) }
    }

    // =========================================================================
    // C-07: Output drain handles FORMAT_CHANGED mid-stream
    // =========================================================================

    @Test
    fun decode_formatChangeMidDrain_continuesDrainingAfterFormatChange() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Output sequence: buffer0 -> FORMAT_CHANGED -> buffer1 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        val buf1 = testBuffer(480)
        val outputSequence = listOf(
            0,
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            1,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            // Final drain after input submit also gets TRY_AGAIN
            MediaCodec.INFO_TRY_AGAIN_LATER
        ).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            val idx = outputSequence.next()
            // Populate BufferInfo when returning a valid buffer index
            if (idx >= 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
            }
            idx
        }
        every { mockCodec.getOutputBuffer(0) } returns buf0
        every { mockCodec.getOutputBuffer(1) } returns buf1

        val mockFormat = mockk<MediaFormat>()
        every { mockCodec.outputFormat } returns mockFormat

        val input = ByteArray(100)
        val result = decoder.decode(input, 0L)

        // Both output buffers should have been collected (480 + 480 = 960 bytes)
        assertEquals(960, result.sumOf { it.pcm.size })

        // Both buffers were released
        verify { mockCodec.releaseOutputBuffer(0, false) }
        verify { mockCodec.releaseOutputBuffer(1, false) }
    }

    @Test
    fun decode_formatChangeOnly_updatesOutputFormat() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        every { mockCodec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        )

        val mockFormat = mockk<MediaFormat>()
        every { mockCodec.outputFormat } returns mockFormat

        decoder.decode(ByteArray(100), 0L)

        verify { Log.d(any(), match { it.contains("Output format changed") }) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun decode_outputBuffersChanged_continuesDraining() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)

        // Sequence: BUFFERS_CHANGED -> buffer0 -> TRY_AGAIN
        val buf0 = testBuffer(480)
        val outputSequence = listOf(
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
            0,
            MediaCodec.INFO_TRY_AGAIN_LATER,
            MediaCodec.INFO_TRY_AGAIN_LATER
        ).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            val idx = outputSequence.next()
            if (idx >= 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
            }
            idx
        }
        every { mockCodec.getOutputBuffer(0) } returns buf0

        val result = decoder.decode(ByteArray(100), 0L)

        // The buffer after BUFFERS_CHANGED was collected
        assertEquals(480, result.sumOf { it.pcm.size })
        verify { mockCodec.releaseOutputBuffer(0, false) }
    }

    // =========================================================================
    // General decode behavior
    // =========================================================================

    @Test
    fun decode_notConfigured_throwsIllegalState() {
        val unconfigured = TestMediaCodecDecoder()
        // mediaCodec is null (not injected)

        try {
            unconfigured.decode(ByteArray(100), 0L)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not configured"))
        }
    }

    @Test
    fun decode_emptyOutput_returnsNothing() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        val result = decoder.decode(ByteArray(100), 0L)
        assertEquals(0, result.size)
    }

    // =========================================================================
    // Output is stamped by the decoder, not by the chunk being submitted
    // =========================================================================

    @Test
    fun decode_passesTheChunkTimestampToTheCodec() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER

        decoder.decode(ByteArray(100), 1_234_567L)

        verify(exactly = 1) { mockCodec.queueInputBuffer(0, 0, 100, 1_234_567L, 0) }
    }

    @Test
    fun decode_lateOutputKeepsTheTimestampOfTheChunkItCameFrom() {
        // What a real decoder does when its first output is not ready in time:
        // nothing from the first call, then both chunks from the second.
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.getOutputBuffer(any()) } answers { testBuffer(480) }

        every { mockCodec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        assertTrue(decoder.decode(ByteArray(100), 1_000_000L).isEmpty())

        val ready = listOf(0 to 1_000_000L, 1 to 1_096_000L).iterator()
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            if (ready.hasNext()) {
                val (index, pts) = ready.next()
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
                info.presentationTimeUs = pts
                index
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        val out = decoder.decode(ByteArray(100), 1_096_000L)

        assertEquals(listOf(1_000_000L, 1_096_000L), out.map { it.timestampUs })
    }

    @Test
    fun decode_stopsWaitingOnceThereIsOutput() {
        every { mockCodec.dequeueInputBuffer(any()) } returns 0
        every { mockCodec.getInputBuffer(0) } returns ByteBuffer.allocate(1024)
        every { mockCodec.getOutputBuffer(0) } answers { testBuffer(480) }
        val timeouts = mutableListOf<Long>()
        var calls = 0
        every { mockCodec.dequeueOutputBuffer(any(), any()) } answers {
            timeouts += secondArg<Long>()
            if (calls++ == 0) {
                val info = firstArg<MediaCodec.BufferInfo>()
                info.offset = 0
                info.size = 480
                0
            } else {
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }

        decoder.decode(ByteArray(100), 0L)

        // Waits for the first buffer, then only polls.
        assertTrue("first wait was ${timeouts[0]}", timeouts[0] > 0L)
        assertEquals(0L, timeouts[1])
    }
}
