package com.sendspindroid.playback

import android.util.Log
import com.sendspindroid.playback.FakeAudioDecoder.Companion.makeChunk
import com.sendspindroid.playback.FakeAudioDecoder.Companion.readLongBE
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Integration tests for [SendSpinEngine]'s decoder pipeline: onStreamStart ->
 * onAudioChunk* -> {onStreamClear | onStreamEnd}. These tests assert two
 * behavior-level invariants the pipeline MUST preserve through any refactor:
 *
 *  (A) Input contiguity -- the decoder sees chunks in strict sequential order,
 *      with zero drops. [FakeAudioDecoder] enforces this and throws on
 *      violation, modeling real MediaCodec (FLAC) sensitivity.
 *
 *  (B) Output completeness -- every chunk the pipeline accepts eventually
 *      yields a PCM output delivered to [SyncAudioPlayer.queueChunk].
 *
 * They also capture the exact failure mode of PR #142 (drop-oldest on channel
 * full), which would flunk test 1 with a non-contiguous-input throw.
 *
 * The real engine runs here, its main and decode dispatchers on one test
 * scheduler. The player is a mock that records queueChunk, and the
 * FakeAudioDecoder is injected via `mockkObject(AudioDecoderFactory)` so
 * `create()` returns it.
 */
class DecoderPipelineIntegrationTest {

    private lateinit var fakeDecoder: FakeAudioDecoder
    private lateinit var simulator: DecoderPipelineSimulator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        fakeDecoder = FakeAudioDecoder()

        // Inject the FakeAudioDecoder via AudioDecoderFactory. The simulator
        // calls AudioDecoderFactory.create(codec) during onStreamStart, mirroring
        // PlaybackService. This is the same seam used by DecoderSetupTest and
        // StreamStartDecoderPipelineTest.
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.create(any<String>()) } returns fakeDecoder

        simulator = DecoderPipelineSimulator()
    }

    @After
    fun tearDown() {
        simulator.shutdown()
        unmockkAll()
    }

    // =========================================================================
    // Test 1: 200-chunk burst at stream start preserves codec contiguity.
    // This is the regression test that would have failed against PR #142
    // (drop-oldest policy on channel full would skip sequence numbers and the
    // FakeAudioDecoder would throw, flipping nonContiguousInputDetected).
    // =========================================================================

    @Test
    fun `burst of 200 chunks at stream start preserves codec contiguity`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Tight burst, simulating the 2-second pre-buffered chunk burst that
        // killed PR #142. Sequence numbers 0..199 in order.
        for (i in 0 until 200) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }

        // Run the main and decode dispatchers until the channel is empty.
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all 200 chunks must have been decoded",
            200,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "decoder should have observed sequences 0..199 in order",
            (0L until 200L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received 200 PCM outputs",
            200,
            simulator.queuedChunks.size
        )
        // Also assert the queued PCM payloads carry the original sequence
        // numbers in order. This catches reorderings that would still pass
        // the decoder contiguity check (e.g., a future refactor that
        // parallelizes decode).
        val queuedSeqs = simulator.queuedChunks.map { readLongBE(it.pcm, 0) }
        assertEquals(
            "queued PCM sequences must be 0..199 in order",
            (0L until 200L).toList(),
            queuedSeqs
        )
    }

    // =========================================================================
    // Test 2: onStreamStart -> chunks -> onStreamEnd does NOT release the
    // decoder. SendSpinEngine.onStreamEnd only enters idle mode on the
    // SyncAudioPlayer; decoder release happens later, either on the next
    // onStreamStart (which releases the prior decoder before creating a new
    // one) or on release().
    // =========================================================================

    @Test
    fun `stream start then chunks then stream end does not release the decoder`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 10) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        simulator.onStreamEnd()
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all 10 chunks must have been decoded before onStreamEnd",
            10,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "onStreamEnd must NOT release the decoder (see SendSpinEngine.onStreamEnd)",
            0,
            fakeDecoder.releaseCalls.get()
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 3: onStreamClear flushes the decoder without losing chunks submitted
    // before the flush. FakeAudioDecoder's flush resets the expected-next-seq
    // to 0, matching the realistic protocol where stream/clear discards
    // buffered audio and fresh audio begins from a new anchor.
    // =========================================================================

    @Test
    fun `stream clear flushes decoder without losing chunks submitted before the flush`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        simulator.onStreamClear()
        simulator.waitForIdle()

        // Fresh run of sequences 0..4. Flush reset expectedNextSeq to 0 so
        // the fake accepts these without flipping into ERROR state.
        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = (100 + i) * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input across flush",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "flush should have been called exactly once",
            1,
            fakeDecoder.flushCalls.get()
        )
        assertEquals(
            "all 10 chunks (5 before flush + 5 after) must have been decoded",
            10,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "observed sequences should be 0..4 then 0..4 (flush resets seq)",
            (0L until 5L).toList() + (0L until 5L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 4: a second onStreamStart releases the prior decoder and recreates
    // cleanly. This is the real lifecycle that PR #142 was worried about and
    // is what actually exercises the decoder-recreate path (onStreamEnd does
    // NOT release -- see test 2 -- so the only point where the previous
    // decoder is torn down is the next onStreamStart). The stream ends
    // between the two: a stream/start that repeats the active stream's
    // format is a no-op (see StreamStartAction).
    //
    // Note on AudioDecoderFactory mockk behavior: setUp() uses
    //   every { AudioDecoderFactory.create(any<String>()) } returns fakeDecoder
    // which returns the SAME FakeAudioDecoder instance on every call. So the
    // single fake sees two configure() calls and one release() call across
    // the two stream starts. FakeAudioDecoder.configure() resets
    // expectedNextSeq/errored/observed/configured but does NOT reset the
    // accumulating call counters (configureCalls, releaseCalls, decodeCalls),
    // which is why decodeCalls ends at 10 while observedSequences only
    // contains the 5 sequences seen after the second configure.
    // =========================================================================

    @Test
    fun `second stream start releases the prior decoder and recreates cleanly`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // Second stream start: release the prior decoder, then
        // create+configure a new one. Because the mockk factory returns the
        // same fake instance, configure() on that fake resets expectedNextSeq
        // to 0 so sequences restart from 0.
        simulator.onStreamEnd()
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        for (i in 0 until 5) {
            simulator.onAudioChunk(
                serverTimeMicros = (100 + i) * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        assertFalse(
            "decoder must not have seen non-contiguous input across recreate",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "configure should have been called twice (once per stream start)",
            2,
            fakeDecoder.configureCalls.get()
        )
        assertEquals(
            "prior decoder must be released exactly once on the second stream start",
            1,
            fakeDecoder.releaseCalls.get()
        )
        assertEquals(
            "all 10 chunks (5 before recreate + 5 after) must have been decoded",
            10,
            fakeDecoder.decodeCalls.get()
        )
        // FakeAudioDecoder.configure() clears its observed queue, so only the
        // post-recreate sequences remain visible via observedSequences.
        assertEquals(
            "observedSequences should reflect only the post-recreate run (0..4)",
            (0L until 5L).toList(),
            fakeDecoder.observedSequences
        )
        assertEquals(
            "SyncAudioPlayer should have received all 10 PCM outputs",
            10,
            simulator.queuedChunks.size
        )
    }

    // =========================================================================
    // Test 5: stress test -- a burst larger than the channel capacity (1000
    // slots) preserves contiguity and delivers every chunk. The last ~200
    // sends suspend until the worker makes room.
    // =========================================================================

    @Test
    fun `burst of 1200 chunks submitted in rapid succession preserves contiguity`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Submit 1200 chunks, past the channel's 1000 slots.
        val chunkCount = 1200
        for (i in 0 until chunkCount) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // No drops, no corruption, all in order.
        assertFalse(
            "decoder must not see non-contiguous input",
            fakeDecoder.nonContiguousInputDetected.get()
        )
        assertEquals(
            "all chunks must have been decoded",
            chunkCount,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "all PCM outputs delivered to queueChunk",
            chunkCount,
            simulator.queuedChunks.size
        )
        // Verify sequence ordering in the queueChunk output stream.
        simulator.queuedChunks.toList().forEachIndexed { idx, chunk ->
            val recoveredSeq = readLongBE(chunk.pcm, 0)
            assertEquals(
                "chunk at index $idx must carry seq $idx",
                idx.toLong(),
                recoveredSeq
            )
        }
    }

    // =========================================================================
    // Test 6: release() releases the decoder exactly once. The chunks are
    // drained first: release() blocks this thread, so the test scheduler
    // cannot run the worker during its wait, and the release here comes
    // from the cancelled worker's finally.
    // =========================================================================

    @Test
    fun `onDestroy releases decoder after draining pending chunks`() {
        simulator.onStreamStart(
            codec = "flac",
            sampleRate = 48000,
            channels = 2,
            bitDepth = 16,
            codecHeader = ByteArray(42)
        )

        // Submit a modest batch.
        for (i in 0 until 50) {
            simulator.onAudioChunk(
                serverTimeMicros = i * 10_000L,
                audioData = makeChunk(i.toLong())
            )
        }
        simulator.waitForIdle()

        // Before teardown: 50 chunks decoded, no release yet.
        assertEquals(
            "50 chunks should have been decoded",
            50,
            fakeDecoder.decodeCalls.get()
        )
        assertEquals(
            "decoder should not be released mid-session",
            0,
            fakeDecoder.releaseCalls.get()
        )

        // Teardown: a final Release, and the worker left to process it.
        simulator.onDestroy()

        assertEquals(
            "decoder must be released exactly once on teardown",
            1,
            fakeDecoder.releaseCalls.get()
        )
        assertFalse(
            "no contiguity violations during teardown",
            fakeDecoder.nonContiguousInputDetected.get()
        )
    }

    // =========================================================================
    // Test helper: captured queueChunk call.
    // =========================================================================

    private data class QueuedChunk(val serverTimeMicros: Long, val pcm: ByteArray)

    /**
     * A [SendSpinEngine] on a test scheduler, with a mock player whose
     * queueChunk calls are captured in [queuedChunks].
     */
    private inner class DecoderPipelineSimulator {
        val queuedChunks: ConcurrentLinkedQueue<QueuedChunk> = ConcurrentLinkedQueue()

        private val dispatcher = StandardTestDispatcher()

        private val player = mockk<SyncAudioPlayer>(relaxed = true).also { p ->
            every { p.bytesPerFrame } returns 4
            every { p.hasRoomFor(any()) } returns true
            every { p.queueChunk(any(), any(), any()) } answers {
                queuedChunks.add(QueuedChunk(firstArg(), secondArg()))
            }
        }

        private val engine = SendSpinEngine(
            listener = object : SendSpinEngine.Listener {
                override fun onStreamStarted() {}
                override fun onPlayerChanged(player: SyncAudioPlayer?) {}
                override fun onPlayerStateChanged(state: com.sendspindroid.sendspin.PlaybackState) {}
            },
            lowMemoryMode = { false },
            playerMuted = { false },
            mainDispatcher = dispatcher,
            decodeDispatcher = dispatcher,
            newPlayer = { _, _, _, _, _ -> player },
        ).also { it.client = mockk<SendSpin>(relaxed = true) }

        fun onStreamStart(
            codec: String,
            sampleRate: Int,
            channels: Int,
            bitDepth: Int,
            codecHeader: ByteArray?
        ) = engine.onStreamStart(codec, sampleRate, channels, bitDepth, codecHeader)

        fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) =
            engine.onAudioChunk(serverTimeMicros, audioData)

        fun onStreamClear() = engine.onStreamClear()

        fun onStreamEnd() = engine.onStreamEnd()

        fun onDestroy() {
            engine.release()
            dispatcher.scheduler.advanceUntilIdle()
        }

        /** Run every task the engine has queued, decoding included. */
        fun waitForIdle() {
            dispatcher.scheduler.advanceUntilIdle()
        }

        fun shutdown() {}
    }
}
