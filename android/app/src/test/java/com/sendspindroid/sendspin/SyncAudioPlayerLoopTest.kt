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
import org.junit.Assert.assertFalse
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

    // Simulated track: the mixer takes 10 ms of what it holds per tick and
    // reports it through the playback head position. Below the mixer is a
    // pipeline of latencyTicks ticks: the DAC presents what the head took
    // that long ago, so a track that ran dry keeps presenting until its
    // pipeline drains. Its timestamps, when it gives any, report that.
    private var latencyTicks = 2
    private val latencyUs: Long get() = latencyTicks * tickNs / 1000
    private var head = 0L
    private val headHistory = ArrayDeque<Long>()  // head after each tick, newest last
    private var flushesSeen = 0

    // A track whose presented position stops keeps returning the timestamp
    // it last gave, as AudioTrack does when it stalls
    private var freezeStalledTimestamps = false
    private var lastTimestamp: SinkTimestamp? = null

    // As the last tick's loop iteration began: when a frame written then
    // would reach the DAC of the simulated track, and how many writes the
    // sink had seen.
    private var dacTimeOfNextWriteUs = 0L
    private var writesBeforeTick = 0
    private var framesBeforeStep = 0L

    // Report positions in their low 32 bits only, as AudioTrack does
    private var wrapPositions = false

    /**
     * Advance the clock 10 ms and run one loop iteration, or with [burst]
     * every iteration until the loop waits, as the audio thread does: those
     * all read the same timestamp. [timestampShiftNs] moves this tick's
     * timestamp time, to model one bad reading.
     */
    private fun tick(timestamps: Boolean = true, timestampShiftNs: Long = 0L, burst: Boolean = false) {
        now.addAndGet(tickNs)
        if (sink.flushCallCount.get() != flushesSeen) {
            flushesSeen = sink.flushCallCount.get()
            head = 0
            headHistory.clear()
            lastTimestamp = null
        }
        head = minOf(framesInTrack(), head + sampleRate / 100)
        sink.scriptedPlaybackHeadPosition = head.toInt()
        headHistory.addLast(head)
        while (headHistory.size > latencyTicks + 1) headHistory.removeFirst()
        val dacPosition = if (headHistory.size > latencyTicks) headHistory.first() else 0L
        val reportedPosition = if (wrapPositions) dacPosition and 0xFFFFFFFFL else dacPosition
        val stalled = lastTimestamp?.let { it.framePosition == reportedPosition } ?: false
        val timestamp = when {
            !timestamps || dacPosition <= 0 -> null
            freezeStalledTimestamps && stalled -> lastTimestamp
            else -> SinkTimestamp(reportedPosition, now.get() + timestampShiftNs)
        }
        if (timestamp != null) lastTimestamp = timestamp
        sink.scriptTimestamp(timestamp)
        // When a frame written now reaches the DAC: once the mixer has taken
        // what is ahead of it, plus the pipeline. Without a timestamp the
        // latency is unknown to the player, and to this.
        dacTimeOfNextWriteUs = nowUs + (framesInTrack() - head) * 1_000_000L / sampleRate +
            if (timestamp != null) latencyUs else 0L
        writesBeforeTick = sink.writes.size
        framesBeforeStep = framesInTrack()
        if (!burst) {
            step()
            return
        }
        repeat(20) { if (stepMethod.invoke(player) as Long > 0L) return }
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
        assertEquals("only fresh silence in the track",
            sink.writes.drop(writesBeforeTick).sumOf { it.size } / bytesPerFrame.toLong(), framesInTrack())
        assertTrue(audioTagsWrittenSince(writesBeforeTick).isEmpty())
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

    // ========================================================================
    // Recovery: large errors, a dry output, gaps, wrapping positions
    // ========================================================================

    /** Tick until the player realigns, which takes two readings of the error. */
    private fun tickUntilRealigning(maxTicks: Int = 20) {
        repeat(maxTicks) {
            if (player.getPlaybackState() == PlaybackState.REANCHORING) return
            tick()
        }
        assertEquals(PlaybackState.REANCHORING, player.getPlaybackState())
    }

    private fun steadyPlay(firstServerTimeUs: Long, count: Int) {
        queueNumberedStream(firstServerTimeUs, count)
        tickUntilPlaying()
        repeat(100) { tick() }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `a large early step realigns the queue instead of discarding it`() {
        val firstServerTimeUs = nowUs + 400_000L
        steadyPlay(firstServerTimeUs, count = 300)
        val dropped = player.getStats().chunksDropped
        val flushes = sink.flushCallCount.get()

        // Everything is now due 800 ms later
        timeFilter.setUserSyncOffsetMs(800.0)
        tickUntilRealigning()
        assertTrue("the queue is kept", queuedSamples() > 0)
        val writesAtStep = sink.writes.size
        val lastTag = audioTagsWrittenSince(0).last()

        tickUntilPlaying(maxTicks = 200)

        assertEquals(1L, player.getStats().reanchorCount)
        assertEquals("nothing queued was dropped", dropped, player.getStats().chunksDropped)
        assertEquals("what the track holds plays out", flushes, sink.flushCallCount.get())
        assertEquals("playback goes on from the queue head", lastTag + 1, audioTagsWrittenSince(writesAtStep).first())
        val errorUs = startErrorUs(firstServerTimeUs) - 800_000L
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `a large late step drops only the audio that went late`() {
        val firstServerTimeUs = nowUs + 400_000L
        steadyPlay(firstServerTimeUs, count = 300)
        val dropped = player.getStats().chunksDropped
        val flushes = sink.flushCallCount.get()

        // Everything is now due 800 ms earlier
        timeFilter.setUserSyncOffsetMs(-800.0)
        tickUntilRealigning()
        tickUntilPlaying(maxTicks = 20)

        assertEquals(1L, player.getStats().reanchorCount)
        assertEquals("the track's late audio is flushed", flushes + 1, sink.flushCallCount.get())
        // 38 chunks: about 26 for the step net of the audio flushed from the
        // track, 2 for the ticks the restart takes, and 10 for the 200 ms of
        // silence the keepalive writes after a flush
        val droppedNow = player.getStats().chunksDropped - dropped
        assertTrue("$droppedNow chunks dropped for 800 ms", droppedNow in 37L..39L)
        assertTrue("the rest of the queue is kept", queuedSamples() > 100L * chunkFrames)
        val errorUs = startErrorUs(firstServerTimeUs) + 800_000L
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `one wild timestamp does not realign even when several chunks read it`() {
        steadyPlay(nowUs + 400_000L, count = 300)
        val dropped = player.getStats().chunksDropped

        // The track plays 60 ms more than usual, so the next iterations write
        // several chunks back to back, all measured against one timestamp
        // that is 600 ms off.
        val shifted = headHistory.map { minOf(framesInTrack(), it + 2_880L) }
        headHistory.clear()
        headHistory.addAll(shifted)
        head = headHistory.last()
        val written = player.getStats().chunksPlayed
        tick(timestampShiftNs = 600_000_000L, burst = true)
        assertTrue("several chunks read the bad timestamp", player.getStats().chunksPlayed - written >= 2)
        assertTrue("the bad reading was taken", player.getSyncErrorUs() > 500_000L)
        repeat(50) { tick() }

        assertEquals(0L, player.getStats().reanchorCount)
        assertEquals(dropped, player.getStats().chunksDropped)
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `a second large step soon after a realignment that worked is corrected at once`() {
        val firstServerTimeUs = nowUs + 400_000L
        steadyPlay(firstServerTimeUs, count = 400)
        val stepAtUs = nowUs

        timeFilter.setUserSyncOffsetMs(800.0)
        tickUntilRealigning()
        tickUntilPlaying(maxTicks = 200)
        repeat(20) { tick() }

        // Well inside the 5 s cooldown
        timeFilter.setUserSyncOffsetMs(0.0)
        tickUntilRealigning()
        tickUntilPlaying(maxTicks = 20)

        assertTrue("both within ${(nowUs - stepAtUs) / 1000} ms", nowUs - stepAtUs < 5_000_000L)
        assertEquals(2L, player.getStats().reanchorCount)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `resume after a pause realigns the queue through start gating`() {
        val firstServerTimeUs = nowUs + 400_000L
        queueNumberedStream(firstServerTimeUs, count = 400)
        tickUntilPlaying()
        repeat(20) { tick() }
        val dropped = player.getStats().chunksDropped

        player.pause()
        now.addAndGet(1_000_000_000L)  // 1 s: the queue head is now about 750 ms late
        player.resume()
        assertEquals(PlaybackState.REANCHORING, player.getPlaybackState())

        tickUntilPlaying(maxTicks = 20)

        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
        assertTrue("audio that went late during the pause is dropped", player.getStats().chunksDropped > dropped)
    }

    @Test
    fun `no sync measurement while the time filter is not ready`() {
        // Server time runs 10 s ahead of local time
        timeFilter.reset()
        timeFilter.addMeasurement(10_000_000L, 1_000L, 3L)
        timeFilter.addMeasurement(10_000_000L, 1_000L, 4L)
        steadyPlay(nowUs + 10_400_000L, count = 300)

        // A reset filter maps server time to local time one to one: 10 s off
        timeFilter.reset()
        repeat(30) { tick() }

        assertEquals("no realignment from an unready filter", 0L, player.getStats().reanchorCount)
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `an empty queue with audio still in the track is not an underrun`() {
        // A live stream: chunks reach the queue only 280 ms before they are
        // due, less than the 300 ms the loop keeps written ahead, so the
        // queue is often empty while the track still holds 250-300 ms.
        var nextUs = nowUs + 400_000L
        var tag = 1
        fun feed() {
            while (nextUs <= nowUs + 280_000L) {
                player.queueChunk(nextUs, pcm(tag++))
                nextUs += chunkUs
            }
        }
        repeat(300) {
            if (player.getPlaybackState() != PlaybackState.PLAYING) {
                feed()
                tick()
            }
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        var emptyTicks = 0
        repeat(500) {
            feed()
            tick()
            if (queuedSamples() == 0L) emptyTicks++
        }

        assertTrue("the queue did run empty ($emptyTicks ticks)", emptyTicks > 50)
        assertEquals(0L, player.getStats().bufferUnderrunCount)
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        assertEquals("corrections are not held back", -1L, player.getGracePeriodRemainingUs())
    }

    @Test
    fun `after the output runs dry late audio is dropped and the rest starts in sync`() {
        queueStream(nowUs + 400_000L, count = 50, tag = 99)
        tickUntilPlaying()
        // Let the queue and then the track run dry
        repeat(150) { tick() }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertEquals(1L, player.getStats().bufferUnderrunCount)
        val dropped = player.getStats().chunksDropped

        // Audio resumes, the first 100 ms of it already late
        val firstServerTimeUs = nowUs - 100_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 20)

        assertTrue("late audio is dropped", player.getStats().chunksDropped > dropped)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
        assertEquals(1L, player.getStats().bufferUnderrunCount)
    }

    @Test
    fun `audio arriving just in time after the output ran dry is not dropped`() {
        queueStream(nowUs + 400_000L, count = 50, tag = 99)
        tickUntilPlaying()
        repeat(150) { tick() }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        val dropped = player.getStats().chunksDropped

        // Due in 120 ms: on time behind a small cushion of silence, late
        // behind the usual 200 ms of keepalive.
        val firstServerTimeUs = nowUs + 120_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 20)

        assertEquals("nothing on time is dropped", dropped, player.getStats().chunksDropped)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `on a high-latency output the track stays fed after it runs dry and restarts on time`() {
        latencyTicks = 15  // 150 ms below the mixer, as on Bluetooth
        freezeStalledTimestamps = true
        queueStream(nowUs + 600_000L, count = 50, tag = 99)
        tickUntilPlaying()
        repeat(150) { tick() }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        repeat(30) {
            tick()
            assertTrue("the track ran dry at the mixer", head < framesBeforeStep)
            assertTrue("no cushion ahead of the head", (framesBeforeStep - head) * 1_000_000L / sampleRate >= 30_000L)
        }
        val dropped = player.getStats().chunksDropped

        val firstServerTimeUs = nowUs + 300_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 40)

        assertEquals("nothing on time is dropped", dropped, player.getStats().chunksDropped)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `on a high-latency output a late but in-time chunk is not an underrun`() {
        // The track holds only what pacing leaves above the 150 ms below the
        // mixer; chunks arrive 180 ms before they are due, after the loop
        // would have written them, so the queue is often empty with the
        // track running low but not dry.
        latencyTicks = 15
        var nextUs = nowUs + 800_000L
        var leadUs = 800_000L  // a normal startup lead first
        var tag = 1
        fun feed() {
            while (nextUs <= nowUs + leadUs) {
                player.queueChunk(nextUs, pcm(tag++))
                nextUs += chunkUs
            }
        }
        repeat(300) {
            if (player.getPlaybackState() != PlaybackState.PLAYING) {
                feed()
                tick()
            }
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        leadUs = 180_000L
        repeat(100) { feed(); tick() }  // what was queued ahead plays out
        repeat(300) {
            feed()
            tick()
            assertTrue("the track ran dry at the mixer", head < framesBeforeStep)
        }

        assertEquals(0L, player.getStats().bufferUnderrunCount)
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `an output that never yields a timestamp restarts at once after running dry`() {
        queueStream(nowUs + 300_000L, count = 20, tag = 99)
        tickUntilPlaying(timestamps = false, maxTicks = 100)
        repeat(100) { if (player.getPlaybackState() == PlaybackState.PLAYING) tick(timestamps = false) }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        // Well inside the 500 ms that a restarted wait for a timestamp would hold
        repeat(5) { tick(timestamps = false) }
        val dropped = player.getStats().chunksDropped

        val firstServerTimeUs = nowUs + 120_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(timestamps = false, maxTicks = 5)

        assertEquals("nothing on time is dropped", dropped, player.getStats().chunksDropped)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    /**
     * Play a short stream until the output runs dry. It starts 5 ms off the
     * 10 ms grid the simulated mixer reads on, so the dry check finds 5 ms
     * still ahead of the head. With [burst] the loop runs on at once after
     * the check, as on the audio thread, before the mixer reads again;
     * without, the mixer reads first, takes those 5 ms and leaves a gap.
     */
    private fun playUntilDry(burst: Boolean) {
        queueStream(nowUs + 605_000L, count = 20, tag = 99)
        tickUntilPlaying()
        repeat(200) { if (player.getPlaybackState() == PlaybackState.PLAYING) tick(burst = burst) }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertEquals(1L, player.getStats().bufferUnderrunCount)
    }

    /** When a frame written now reaches the DAC of the simulated track. */
    private fun writeCursorDacTimeUs(): Long =
        nowUs + (framesInTrack() - head) * 1_000_000L / sampleRate + latencyUs

    private fun audioRightAfterDryStartsAtOnce() {
        playUntilDry(burst = true)
        val dropped = player.getStats().chunksDropped

        // Due just behind what the track holds
        val firstServerTimeUs = writeCursorDacTimeUs() + 5_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 1)

        assertEquals("nothing on time is dropped", dropped, player.getStats().chunksDropped)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `audio arriving right after the output ran dry starts at once against the running track`() =
        audioRightAfterDryStartsAtOnce()

    @Test
    fun `on a high-latency output audio arriving right after it ran dry starts at once`() {
        latencyTicks = 15
        audioRightAfterDryStartsAtOnce()
    }

    @Test
    fun `a track the mixer has already emptied when the loop finds it dry waits for fresh timestamps`() {
        freezeStalledTimestamps = true
        playUntilDry(burst = false)

        // The last timestamp is fresh, but the 5 ms gap behind it is not in it
        val firstServerTimeUs = writeCursorDacTimeUs() + 5_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 10)

        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `an output whose timestamps stop before it runs dry waits for them again`() {
        queueNumberedStream(nowUs + 400_000L, count = 70)
        tickUntilPlaying()
        repeat(200) { if (queuedSamples() > 0L) tick() }
        assertEquals(0L, queuedSamples())
        val lastTimestampUs = nowUs
        repeat(100) { if (player.getPlaybackState() == PlaybackState.PLAYING) tick(timestamps = false) }
        assertEquals(PlaybackState.INITIALIZING, player.getPlaybackState())
        assertTrue("ran dry within the wait for a timestamp", nowUs - lastTimestampUs < 400_000L)

        // Not started from the playback head while the restarted wait lasts
        val firstServerTimeUs = nowUs + 150_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        repeat(30) {
            tick(timestamps = false)
            assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())
        }

        tickUntilPlaying(maxTicks = 10)
        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("restart is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `on an output with more latency below the mixer than the keepalive target the start is on time`() {
        latencyTicks = 25  // 250 ms
        freezeStalledTimestamps = true
        repeat(60) { tick() }
        repeat(60) {
            tick()
            assertTrue("the track ran dry at the mixer", head < framesBeforeStep)
        }

        val firstServerTimeUs = nowUs + 600_000L
        queueNumberedStream(firstServerTimeUs, count = 50)
        tickUntilPlaying(maxTicks = 60)
        // Behind the silence padded to the start, the track holds enough for
        // pacing to let the first audio wait a tick
        repeat(5) { if (audioTagsWrittenSince(writesBeforeTick).isEmpty()) tick() }

        val errorUs = startErrorUs(firstServerTimeUs)
        assertTrue("start is off by ${errorUs}us", kotlin.math.abs(errorUs) <= oneFrameUs)
    }

    @Test
    fun `on an output with more latency below the mixer than the pacing target the track stays fed`() {
        latencyTicks = 35  // 350 ms
        freezeStalledTimestamps = true
        queueNumberedStream(nowUs + 800_000L, count = 200)
        tickUntilPlaying(maxTicks = 100)
        repeat(150) {
            tick()
            assertTrue("the track ran dry at the mixer", head < framesBeforeStep)
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        assertEquals(0L, player.getStats().bufferUnderrunCount)
    }

    @Test
    fun `a player whose remaining audio all went late has played out`() {
        queueNumberedStream(nowUs + 400_000L, count = 60)
        tickUntilPlaying()
        repeat(500) { if (player.getBufferedDurationMs() > 400) tick() }
        // output_delay_ms 0 -> 1000: everything still queued is now late
        timeFilter.setOutputDelayMs(1000.0)
        repeat(200) { tick() }

        assertEquals(0L, queuedSamples())
        assertTrue("nothing is left to play: ${player.getPlaybackState()}", player.hasPlayedOut())
    }

    @Test
    fun `a realignment that has not settled holds the next back for the cooldown`() {
        steadyPlay(nowUs + 400_000L, count = 1000)
        timeFilter.setUserSyncOffsetMs(-800.0)
        tickUntilRealigning()
        val realignedAtUs = nowUs
        tickUntilPlaying(maxTicks = 20)
        // Off again before any reading back in range
        timeFilter.setUserSyncOffsetMs(0.0)

        val played = player.getStats().chunksPlayed
        while (nowUs - realignedAtUs < 4_900_000L) {
            tick()
            assertEquals(1L, player.getStats().reanchorCount)
        }
        assertTrue("playback went on meanwhile", player.getStats().chunksPlayed > played)
        repeat(30) { tick() }
        assertEquals("realigned once the cooldown was over", 2L, player.getStats().reanchorCount)
    }

    @Test
    fun `a gap never evicts queued audio from a capped queue`() {
        val p = newPlayer(FakeAudioSink(), maxQueueSamples = chunkFrames * 10L)
        val t = nowUs + 500_000L
        repeat(9) { p.queueChunk(t + it * chunkUs, pcm(it + 1)) }

        // 100 ms later than the end of what is queued
        p.queueChunk(t + 9 * chunkUs + 100_000L, pcm(10))

        assertEquals("all ten chunks are still queued", 10L * chunkFrames, samplesInQueue(p))
        assertEquals("the audio due next is still the head", 1, tagOf(
            field<java.util.Queue<Any>>(p, "chunkQueue").first().let {
                Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
                    .getDeclaredMethod("getPcmData").apply { isAccessible = true }.invoke(it) as ByteArray
            }
        ))
    }

    @Test
    fun `a gap the output has already passed is not filled with late silence`() {
        val startUs = nowUs + 400_000L
        queueStream(startUs, count = 50, tag = 1)
        tickUntilPlaying()
        repeat(20) { tick() }
        // The clock runs past the end of what was queued before the loop
        // notices; then audio arrives after a jump in the timeline.
        now.addAndGet(2_000_000_000L)
        val gaps = player.getStats().gapsFilled

        val postGapUs = startUs + 50 * chunkUs + 3_000_000L
        player.queueChunk(postGapUs, pcm(2))

        assertEquals("no silence queued for a gap already passed", gaps, player.getStats().gapsFilled)
        assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())

        // The stale audio in front of the gap is dropped, and the chunk after
        // it is waited for, not padded up to in one long write.
        repeat(300) {
            if (player.getPlaybackState() == PlaybackState.PLAYING) return@repeat
            tick()
            val silenceFrames = sink.writes.drop(writesBeforeTick)
                .filter { tagOf(it.snapshotFirstBytes) == 0 }.sumOf { it.size } / bytesPerFrame
            assertTrue("$silenceFrames frames of silence in one tick", silenceFrames <= 30L * sampleRate / 1000)
        }
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
    }

    @Test
    fun `a gap ahead of the output is filled in pieces up to the cap`() {
        val t = nowUs + 400_000L
        player.queueChunk(t, pcm(1))
        player.queueChunk(t + chunkUs + 60_000_000L, pcm(2))

        assertEquals(5_000L, player.getStats().gapSilenceMs)
        assertEquals(2L * chunkFrames + 5L * sampleRate, samplesInQueue())
        val sizes = field<java.util.Queue<Any>>(player, "chunkQueue").map {
            Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
                .getDeclaredMethod("getSampleCount").apply { isAccessible = true }.invoke(it) as Int
        }
        assertTrue("pieces of at most 100 ms: $sizes", sizes.all { it <= sampleRate / 10 })
    }

    @Test
    fun `a short stream starts once it is due`() {
        // 100 ms of audio in all, less than any minimum buffer
        queueNumberedStream(nowUs + 300_000L, count = 5)

        tickUntilPlaying(maxTicks = 60)
        repeat(30) { tick() }

        assertEquals(listOf(1, 2, 3, 4, 5), audioTagsWrittenSince(0).distinct())
    }

    @Test
    fun `frame positions that wrap at 32 bits keep the output paced and measured`() {
        steadyPlay(nowUs + 400_000L, count = 600)
        // Move the track to 5 ticks before its position wraps
        val offset = (1L shl 32) - framesInTrack() - 5L * sampleRate / 100
        field<AtomicLong>(player, "totalFramesWritten").addAndGet(offset)
        head += offset
        val shifted = headHistory.map { it + offset }
        headHistory.clear()
        headHistory.addAll(shifted)
        lastTimestamp = null
        wrapPositions = true
        val played = player.getStats().chunksPlayed

        repeat(200) {
            tick()
            assertTrue("${pendingFrames()} frames pending", pendingFrames() <= (300 + 20) * sampleRate / 1000L)
        }

        val playedNow = player.getStats().chunksPlayed - played
        assertTrue("$playedNow chunks played in 2 s", playedNow in 95L..105L)
        assertTrue("sync error ${player.getSyncErrorUs()}us", kotlin.math.abs(player.getSyncErrorUs()) < 1_000L)
        assertEquals(0L, player.getStats().reanchorCount)
    }

    @Test
    fun `a capped queue reports when it has no room so the decoder can wait instead of evicting`() {
        val p = newPlayer(FakeAudioSink(), maxQueueSamples = chunkFrames * 3L)
        repeat(3) { p.queueChunk(nowUs + 500_000L + it * 20_000L, pcm(it + 1)) }

        assertFalse(p.hasRoomFor(chunkFrames.toLong()))

        val uncapped = newPlayer(FakeAudioSink())
        repeat(3) { uncapped.queueChunk(nowUs + 500_000L + it * 20_000L, pcm(it + 1)) }
        assertTrue(uncapped.hasRoomFor(chunkFrames.toLong()))
    }
}
