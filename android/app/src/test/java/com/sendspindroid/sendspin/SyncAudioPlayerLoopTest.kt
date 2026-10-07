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

    private fun newPlayer(
        sink: AudioSink,
        maxQueueSamples: Long = 0,
        timeFilter: SendspinTimeFilter = this.timeFilter,
    ): SyncAudioPlayer =
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
            setField(this, "lastUsableTimestampAtUs", nowUs)
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

    private fun setField(p: SyncAudioPlayer, name: String, value: Any?) {
        SyncAudioPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(p, value)
    }

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

    // Simulated track: takes 10 ms of what it holds per tick and reports it
    // through the playback head position. Its timestamps, when it gives any,
    // are 20 ms behind that, the output latency below the mixer.
    private val latencyFrames = 960L
    private var head = 0L
    private var flushesSeen = 0

    // As the last tick's loop iteration began: when a frame written then
    // would reach the DAC of the simulated track, and how many writes the
    // sink had seen.
    private var dacTimeOfNextWriteUs = 0L
    private var writesBeforeTick = 0

    /** Advance the clock 10 ms and run one loop iteration. */
    private fun tick(timestamps: Boolean = true) {
        now.addAndGet(tickNs)
        if (sink.flushCallCount.get() != flushesSeen) {
            flushesSeen = sink.flushCallCount.get()
            head = 0
        }
        head = minOf(framesInTrack(), head + sampleRate / 100)
        sink.scriptedPlaybackHeadPosition = head.toInt()
        val dacPosition = head - latencyFrames
        val timestamp = if (timestamps && dacPosition > 0) SinkTimestamp(dacPosition, now.get()) else null
        sink.scriptTimestamp(timestamp)
        // Without a timestamp the latency is unknown to the player, and to this.
        val position = if (timestamp != null) dacPosition else head
        dacTimeOfNextWriteUs = nowUs + (framesInTrack() - position) * 1_000_000L / sampleRate
        writesBeforeTick = sink.writes.size
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
    // Pause leaves the track to the audio thread
    // ========================================================================

    @Test
    fun `pause stops the track at once and leaves the flush to the loop`() {
        queueStream(nowUs + 400_000L, count = 400, tag = 1)
        tickUntilPlaying()
        repeat(20) { tick() }
        val flushes = sink.flushCallCount.get()
        val pauses = sink.pauseCallCount.get()
        val plays = sink.playCallCount.get()

        player.pause()

        assertEquals("the track is paused by the caller", pauses + 1, sink.pauseCallCount.get())
        assertEquals("but not flushed by it", flushes, sink.flushCallCount.get())
        val writes = sink.writes.size
        repeat(5) { tick() }
        assertEquals("nothing is written while paused", writes, sink.writes.size)

        player.resume()

        // Until the loop has flushed, the track holds audio from before the
        // pause; setting it playing here would let that be heard.
        assertEquals("resume leaves the restart to the loop", plays, sink.playCallCount.get())
        assertEquals(flushes, sink.flushCallCount.get())

        tick()

        assertEquals("the loop flushes", flushes + 1, sink.flushCallCount.get())
        assertEquals("and sets the track playing again", plays + 1, sink.playCallCount.get())
    }

    @Test
    fun `a clear while paused restarts the track from the loop`() {
        queueStream(nowUs + 400_000L, count = 400, tag = 1)
        tickUntilPlaying()
        repeat(20) { tick() }
        player.pause()
        val flushes = sink.flushCallCount.get()
        val plays = sink.playCallCount.get()

        player.clearBuffer()
        // The new stream's "playing" state can arrive after its clear.
        sink.scriptedPlayState = 2 // AudioTrack.PLAYSTATE_PAUSED
        player.resume()

        assertEquals(plays, sink.playCallCount.get())
        assertEquals(flushes, sink.flushCallCount.get())

        tick()

        assertEquals(flushes + 1, sink.flushCallCount.get())
        assertEquals(plays + 1, sink.playCallCount.get())
    }

    // ========================================================================
    // A chunk from before a clear
    // ========================================================================

    @Test
    fun `a chunk that is no longer current is not queued`() {
        player.queueChunk(nowUs + 400_000L, pcm(1)) { false }

        assertEquals(0L, samplesInQueue())
        assertEquals(0L, queuedSamples())
    }

    @Test
    fun `a chunk found current is queued before a clear that is already on its way`() {
        // The decode thread finds its chunk current; the stream is cleared
        // before it gets any further. The clear has to wait for the chunk
        // and remove it, not run first and leave it at the head of the queue.
        val checking = java.util.concurrent.CountDownLatch(1)
        val decodeThread = Thread {
            player.queueChunk(nowUs + 400_000L, pcm(1)) {
                checking.countDown()
                Thread.sleep(100)
                true
            }
        }
        decodeThread.start()
        assertTrue(checking.await(5, java.util.concurrent.TimeUnit.SECONDS))

        player.clearBuffer()
        decodeThread.join(5_000)

        assertEquals("a chunk from before the clear is still queued", 0L, samplesInQueue())
        assertEquals(0L, queuedSamples())
    }

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

        // The reset after a resume flushes 250 ms of written audio, so what
        // is queued is now early and the one-shot resync pads it with
        // silence. Let that pass.
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

    // ========================================================================
    // Start gating when timestamps are late, missing, or stop
    // ========================================================================

    /** Queue [count] contiguous chunks, chunk i tagged i + 1. */
    private fun queueNumberedStream(firstServerTimeUs: Long, count: Int) {
        for (i in 0 until count) player.queueChunk(firstServerTimeUs + i * chunkUs, pcm(i + 1))
    }

    /**
     * For the tick that started playback: when the first audio frame it wrote
     * will reach the DAC of the simulated track, minus that frame's server
     * timestamp, in microseconds. Positive is late.
     */
    private fun startErrorUs(firstServerTimeUs: Long): Long {
        val writes = sink.writes.drop(writesBeforeTick)
        val firstAudio = writes.indexOfFirst { tagOf(it.snapshotFirstBytes) != 0 }
        assertTrue("the starting tick wrote audio", firstAudio >= 0)
        val silenceFrames = writes.take(firstAudio).sumOf { it.size } / bytesPerFrame
        val audio = writes[firstAudio]
        val frameServerTimeUs = firstServerTimeUs + (tagOf(audio.snapshotFirstBytes) - 1) * chunkUs +
            (audio.offset / bytesPerFrame) * 1_000_000L / sampleRate
        return dacTimeOfNextWriteUs + silenceFrames * 1_000_000L / sampleRate - frameServerTimeUs
    }

    private val oneFrameUs = 1_000_000L / sampleRate + 1

    @Test
    fun `start waits for timestamps that arrive late and then aligns to them`() {
        val firstServerTimeUs = nowUs + 153_000L
        queueNumberedStream(firstServerTimeUs, count = 200)

        // No timestamp for 400 ms: the head chunk comes due and goes by, and
        // the track is only fed silence.
        repeat(40) {
            tick(timestamps = false)
            assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())
        }
        assertTrue("silence kept the track fed", framesInTrack() > 0)
        assertTrue("no audio yet", audioTagsWrittenSince(0).isEmpty())

        tickUntilPlaying(timestamps = true, maxTicks = 10)

        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("start is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
        assertTrue("the audio that was late by then is dropped", player.getStats().chunksDropped > 0)
    }

    @Test
    fun `without timestamps the start is bounded and allows for what is in the track`() {
        // Timestamps for 300 ms, so the keepalive has filled the track to its
        // usual 200 ms ahead of the DAC, and then never again.
        repeat(30) { tick(timestamps = true) }
        val pendingBefore = pendingFrames()
        assertTrue("$pendingBefore frames pending", pendingBefore >= 150 * sampleRate / 1000L)

        val lastTimestampAtUs = nowUs
        val firstServerTimeUs = nowUs + 153_000L
        queueNumberedStream(firstServerTimeUs, count = 200)

        var ticks = 0
        while (player.getPlaybackState() != PlaybackState.PLAYING && ticks < 200) {
            tick(timestamps = false)
            ticks++
            if (nowUs - lastTimestampAtUs <= 500_000L) {
                assertEquals("no start inside the wait", PlaybackState.WAITING_FOR_START, player.getPlaybackState())
            }
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        assertEquals("starts as soon as the wait is over", 510_000L, nowUs - lastTimestampAtUs)

        // The audio starts behind the silence already written, not on top of
        // it: its first frame reaches the DAC when its timestamp says.
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("start is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `an output that never yields a timestamp still plays`() {
        val firstServerTimeUs = nowUs + 153_000L
        queueNumberedStream(firstServerTimeUs, count = 200)

        tickUntilPlaying(timestamps = false, maxTicks = 60)

        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("start is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
        repeat(50) { tick(timestamps = false) }
        assertTrue("audio keeps being written", player.getStats().chunksPlayed > 50)
    }

    @Test
    fun `chunks that arrive before the clock is synchronised wait for it`() {
        val lateFilter = SendspinTimeFilter()
        val lateSink = FakeAudioSink()
        val p = newPlayer(lateSink, timeFilter = lateFilter)
        for (i in 0 until 200) p.queueChunk(nowUs + 150_000L + i * chunkUs, pcm(1))
        assertEquals(200L * chunkFrames, queuedSamples(p))

        // Well past the first chunk's time and past the wait for timestamps.
        repeat(100) {
            now.addAndGet(tickNs)
            step(p)
        }
        assertEquals(PlaybackState.WAITING_FOR_START, p.getPlaybackState())
        assertTrue("only silence so far", lateSink.writes.all { tagOf(it.snapshotFirstBytes) == 0 })

        lateFilter.addMeasurement(0L, 1_000L, 1L)
        lateFilter.addMeasurement(0L, 1_000L, 2L)
        now.addAndGet(tickNs)
        step(p)
        assertEquals(PlaybackState.PLAYING, p.getPlaybackState())
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
