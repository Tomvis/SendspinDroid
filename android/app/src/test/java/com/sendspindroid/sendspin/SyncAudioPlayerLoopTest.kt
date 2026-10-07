package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.FakeAudioSink
import com.sendspindroid.sendspin.audio.SinkTimestamp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives SyncAudioPlayer's playback loop one iteration at a time against a
 * FakeAudioSink and a simulated track, the way the audio thread does, while
 * the calls the service makes (queueChunk, clearBuffer, enterIdle, pause,
 * resume) come from the test.
 */
class SyncAudioPlayerLoopTest {

    private val sampleRate = 48_000
    private val bytesPerFrame = 4
    private val chunkFrames = 960
    private val chunkUs = 20_000L
    private val tickNs = 10_000_000L

    // Monotonic clock driving the player. The time filter has measured a
    // zero offset, so server time equals it. (A real filter, not a mock: a
    // mock records every call, which the stress tests make by the million.)
    private val now = AtomicLong(1_000_000_000L)
    private val nowUs: Long get() = now.get() / 1000

    private val timeFilter = SendspinTimeFilter()

    private fun syncClock() {
        timeFilter.addMeasurement(0L, 1_000L, 1L)
        timeFilter.addMeasurement(0L, 1_000L, 2L)
        assertTrue(timeFilter.isReady)
        assertEquals(5_000_000L, timeFilter.serverToClient(5_000_000L))
        assertEquals(5_000_000L, timeFilter.clientToServer(5_000_000L))
    }

    init {
        syncClock()
    }

    private fun newPlayer(sink: AudioSink, maxQueueSamples: Long = 0): SyncAudioPlayer =
        SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = 2,
            bitDepth = 16,
            maxQueueSamples = maxQueueSamples,
            nowNs = { now.get() },
            sinkFactory = { _, _, _, _ -> sink },
        ).apply {
            initialize()
            // start() needs a Looper; the test runs the loop itself.
            field<AtomicBoolean>(this, "isPlaying").set(true)
        }

    private val sink = FakeAudioSink()
    private val player = newPlayer(sink)

    private val stepMethod = SyncAudioPlayer::class.java.getDeclaredMethod("playbackLoopStep")
        .apply { isAccessible = true }

    private fun step(p: SyncAudioPlayer = player) {
        stepMethod.invoke(p)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(p: SyncAudioPlayer, name: String): T =
        SyncAudioPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(p) as T

    private fun queuedSamples(p: SyncAudioPlayer = player): Long = field<AtomicLong>(p, "totalQueuedSamples").get()

    /** Sum of the sample counts of the chunks actually in the queue. */
    private fun samplesInQueue(p: SyncAudioPlayer = player): Long {
        val getter = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
            .getDeclaredMethod("getSampleCount").apply { isAccessible = true }
        return field<java.util.Queue<Any>>(p, "chunkQueue").sumOf { (getter.invoke(it) as Int).toLong() }
    }

    /** Frames the player has written since the track was last flushed. */
    private fun framesInTrack(): Long = field<AtomicLong>(player, "totalFramesWritten").get()

    /** PCM for one chunk with [tag] in every frame, so any write of it can be told apart. */
    private fun pcm(tag: Int, frames: Int = chunkFrames): ByteArray {
        val data = ByteArray(frames * bytesPerFrame)
        for (i in 0 until frames) {
            data[i * 4] = tag.toByte()
            data[i * 4 + 1] = (tag shr 8).toByte()
            data[i * 4 + 2] = (tag shr 16).toByte()
            data[i * 4 + 3] = (tag shr 24).toByte()
        }
        return data
    }

    /** The tag of the PCM a write started with; 0 is silence. */
    private fun tagOf(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)

    /** Queue [count] contiguous chunks starting at [firstServerTimeUs], all tagged [tag]. */
    private fun queueStream(firstServerTimeUs: Long, count: Int, tag: Int) {
        for (i in 0 until count) player.queueChunk(firstServerTimeUs + i * chunkUs, pcm(tag))
    }

    // Simulated track: plays 10 ms of what it holds per tick and reports it
    // through the playback head position and, when asked to, a timestamp.
    private var head = 0L
    private var flushesSeen = 0

    /** Advance the clock 10 ms and run one loop iteration. */
    private fun tick(timestamps: Boolean = true) {
        now.addAndGet(tickNs)
        if (sink.flushCallCount.get() != flushesSeen) {
            flushesSeen = sink.flushCallCount.get()
            head = 0
        }
        head = minOf(framesInTrack(), head + sampleRate / 100)
        sink.scriptedPlaybackHeadPosition = head.toInt()
        sink.scriptTimestamp(if (timestamps && head > 0) SinkTimestamp(head, now.get()) else null)
        step()
    }

    private fun tickUntilPlaying(timestamps: Boolean = true, maxTicks: Int = 300) {
        repeat(maxTicks) {
            if (player.getPlaybackState() == PlaybackState.PLAYING) return
            tick(timestamps)
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    private fun audioTagsWrittenSince(writeIndex: Int): List<Int> =
        sink.writes.drop(writeIndex).map { tagOf(it.snapshotFirstBytes) }.filter { it != 0 }

    // ========================================================================
    // Stream clear against the audio thread
    // ========================================================================

    @Test
    fun `clear is carried out by the loop and nothing queued before it is played`() {
        queueStream(nowUs + 400_000L, count = 100, tag = 1)
        tickUntilPlaying()
        repeat(20) { tick() }
        assertTrue("old stream is playing", audioTagsWrittenSince(0).isNotEmpty())

        val flushesBefore = sink.flushCallCount.get()
        val writesBefore = sink.writes.size
        player.clearBuffer()

        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertEquals(0L, queuedSamples())
        assertEquals("the flush is left to the loop", flushesBefore, sink.flushCallCount.get())

        // The new stream arrives before the loop has run again.
        queueStream(nowUs + 400_000L, count = 100, tag = 2)
        tick()
        assertEquals("the loop flushed the track", flushesBefore + 1, sink.flushCallCount.get())
        assertEquals("only fresh silence in the track", sampleRate / 100L, framesInTrack())
        assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())

        tickUntilPlaying()
        repeat(20) { tick() }
        val tags = audioTagsWrittenSince(writesBefore)
        assertTrue("new stream is playing", tags.isNotEmpty())
        assertTrue("old stream audio written after the clear: $tags", tags.all { it == 2 })
        assertEquals(samplesInQueue(), queuedSamples())
    }

    @Test
    fun `enterIdle stops playback until new audio arrives`() {
        queueStream(nowUs + 400_000L, count = 100, tag = 1)
        tickUntilPlaying()
        repeat(5) { tick() }

        val writesBefore = sink.writes.size
        player.enterIdle()
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertEquals(0L, queuedSamples())

        repeat(50) { tick() }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertTrue("only silence while idle", audioTagsWrittenSince(writesBefore).isEmpty())
        assertEquals("an emptied queue is not an underrun", 0L, player.getStats().bufferUnderrunCount)
    }

    // ========================================================================
    // Write pacing
    // ========================================================================

    /** Frames written to the simulated track and not yet played. */
    private fun pendingFrames(): Long = framesInTrack() - head

    @Test
    fun `writes stay paced after a resume from a short pause`() {
        queueStream(nowUs + 400_000L, count = 400, tag = 1)
        tickUntilPlaying()
        repeat(20) { tick() }

        // Short enough that the queued audio is still worth playing.
        player.pause()
        now.addAndGet(100_000_000L)
        player.resume()

        // pause() flushed 250 ms of written audio, so what is queued is now
        // early and the one-shot resync pads it with silence. Let that pass.
        repeat(100) { tick() }

        // Target depth 250 ms, tolerance 50 ms, and the chunk that crosses it.
        val limit = (300 + 20) * sampleRate / 1000L
        repeat(200) {
            tick()
            assertTrue("${pendingFrames()} frames pending in the track", pendingFrames() <= limit)
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        assertTrue("audio kept playing", pendingFrames() > 0)
    }

    /** FakeAudioSink that also hands each loop iteration the tags it wrote. */
    private class TagSink(val fake: FakeAudioSink = FakeAudioSink()) : AudioSink by fake {
        val tags = ArrayList<Int>()
        override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
            if (size >= 4) {
                val tag = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16) or ((buffer[offset + 3].toInt() and 0xFF) shl 24)
                if (tag != 0) tags.add(tag)
            }
            fake.totalBytesWritten.addAndGet(size.toLong())
            return size
        }
    }

    /**
     * Three threads, as in the service: one queues chunks, one clears, one
     * runs the loop. [clearsLocked] makes each clear and each queueChunk
     * atomic for the test, so every chunk is known to be from before or
     * after a given clear.
     */
    private fun stress(clearsLocked: Boolean) {
        val tagSink = TagSink()
        // A 300 ms cap, so the producer also evicts from the head of the queue.
        val p = newPlayer(tagSink, maxQueueSamples = sampleRate * 3L / 10)
        val written = field<AtomicLong>(p, "totalFramesWritten")
        val failure = AtomicReference<String?>(null)
        fun fail(message: String) = failure.compareAndSet(null, message)

        val testLock = Any()
        val steps = AtomicLong(0)
        val epoch = AtomicInteger(1)      // tag of the chunks being queued now
        val done = AtomicBoolean(false)
        val holding = AtomicBoolean(false)
        val totalSteps = 200_000L

        // The server: keeps the stream 300 to 500 ms ahead of the clock.
        val streamedToUs = AtomicLong(nowUs + 100_000L)
        val producer = thread(name = "producer") {
            while (!done.get()) {
                val cursorUs = streamedToUs.get()
                if (cursorUs > nowUs + 500_000L) {
                    Thread.yield()
                    continue
                }
                if (clearsLocked) {
                    synchronized(testLock) { p.queueChunk(cursorUs, pcm(epoch.get(), 96)) }
                } else {
                    p.queueChunk(cursorUs, pcm(epoch.get(), 96))
                }
                streamedToUs.set(cursorUs + 2_000L)
            }
        }

        val clearer = thread(name = "clearer") {
            val random = java.util.Random(7)
            var n = 0
            while (!done.get()) {
                val waitUntil = steps.get() + 20 + random.nextInt(80)
                while (steps.get() < waitUntil && !done.get()) Thread.yield()
                val clear = { if (n++ % 2 == 0) p.clearBuffer() else p.enterIdle() }
                if (!clearsLocked) {
                    clear()
                    continue
                }
                synchronized(testLock) {
                    clear()
                    epoch.incrementAndGet()
                    // No new audio can arrive while the lock is held.
                    holding.set(true)
                    val holdUntil = steps.get() + 3
                    while (steps.get() < holdUntil && !done.get()) {
                        if (p.getPlaybackState() == PlaybackState.PLAYING) fail("PLAYING after a clear, without new audio")
                        if (queuedSamples(p) != 0L) fail("queued count ${queuedSamples(p)} after a clear")
                        Thread.yield()
                    }
                    holding.set(false)
                }
            }
        }

        // The loop, on this thread.
        while (steps.get() < totalSteps && failure.get() == null) {
            now.addAndGet(tickNs)
            while (streamedToUs.get() < nowUs + 300_000L && !holding.get()) Thread.yield()
            // A live track with 50 ms pending, so starts take the DAC-aware path.
            val w = written.get()
            tagSink.fake.scriptTimestamp(if (w > 0) SinkTimestamp(maxOf(1L, w - 2_400L), now.get()) else null)
            val clearedBefore = epoch.get()
            tagSink.tags.clear()
            step(p)
            if (clearsLocked) {
                tagSink.tags.firstOrNull { it < clearedBefore }?.let {
                    fail("chunk from before clear ${clearedBefore - 1} (epoch $it) played after it")
                }
            }
            val queued = queuedSamples(p)
            if (queued < 0) fail("queued sample count went negative: $queued")
            steps.incrementAndGet()
        }
        done.set(true)
        producer.join()
        clearer.join()

        assertNull(failure.get(), failure.get())
        assertEquals("queued sample count matches the queue", samplesInQueue(p), queuedSamples(p))
        assertTrue("the loop played audio", p.getStats().chunksPlayed > 0)
    }

    @Test
    fun `queued sample count stays true to the queue under concurrent clears`() = stress(clearsLocked = false)

    @Test
    fun `no chunk from before a clear is played after it and playback waits for new audio`() =
        stress(clearsLocked = true)
}
