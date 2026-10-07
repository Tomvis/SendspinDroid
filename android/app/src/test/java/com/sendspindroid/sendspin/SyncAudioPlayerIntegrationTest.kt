package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.FakeAudioSink
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-style tests for SyncAudioPlayer's start gating, sync mute,
 * watchdog and correction loop, driven through a FakeAudioSink.
 *
 * `SyncAudioPlayer.initialize()` calls `AudioTrack.getMinBufferSize` (an
 * Android-only API unavailable in JVM unit tests), so these tests inject the
 * sink and state via reflection instead of going through the init path.
 */
class SyncAudioPlayerIntegrationTest {

    // Controllable monotonic clock driving the player.
    private var now: Long = 0L
    private val nowNs: () -> Long = { now }

    // Standard audio format: 48kHz, 2ch, 16-bit.
    private val sampleRate = 48_000
    private val channels = 2
    private val bitDepth = 16

    /** Get a private field value via reflection. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(player: SyncAudioPlayer, name: String): T {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(player) as T
    }

    /** Set a private field value via reflection. */
    private fun setField(player: SyncAudioPlayer, name: String, value: Any?) {
        val field = SyncAudioPlayer::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(player, value)
    }

    /** Invoke the private handleStartGatingDacAware(AudioSink) method. */
    private fun invokeHandleStartGatingDacAware(
        player: SyncAudioPlayer,
        sink: AudioSink,
    ): Boolean {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "handleStartGatingDacAware",
            AudioSink::class.java,
        )
        method.isAccessible = true
        return method.invoke(player, sink) as Boolean
    }

    /**
     * Build an AudioChunk with the supplied PCM data via reflection (the
     * data class is private to SyncAudioPlayer).
     */
    private fun makeAudioChunkReflective(pcmData: ByteArray, frames: Int): Any {
        val clazz = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
        val ctor = clazz.declaredConstructors.first()
        ctor.isAccessible = true
        return ctor.newInstance(0L, pcmData, frames)
    }

    private fun invokePlayChunkWithCorrection(player: SyncAudioPlayer, chunk: Any) {
        val clazz = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
        val method = SyncAudioPlayer::class.java.getDeclaredMethod(
            "playChunkWithCorrection", clazz,
        )
        method.isAccessible = true
        method.invoke(player, chunk)
    }

    @Test
    fun `sync mute silences by gain and keeps writing through playChunkWithCorrection`() {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
        setField(player, "audioSink", fakeSink)

        val frames = 240
        val pcm = ByteArray(frames * 4) { 0x42 }
        val chunk = makeAudioChunkReflective(pcm, frames)

        player.setMuted(SyncAudioPlayer.MuteReason.SYNC, true)
        invokePlayChunkWithCorrection(player, chunk)

        assertEquals("sync mute is immediate output gain", 0f, fakeSink.volume)
        val record = fakeSink.writes.firstOrNull()
            ?: error("a muted player must keep writing so it stays in sync")
        assertTrue(
            "muted writes keep their PCM; the gain silences them",
            record.snapshotFirstBytes.all { it == 0x42.toByte() },
        )
    }

    @Test
    fun `unmuted player leaves PCM bytes untouched in playChunkWithCorrection`() {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        val player = SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
        setField(player, "audioSink", fakeSink)

        val frames = 240
        val pcm = ByteArray(frames * 4) { 0x42 }
        val chunk = makeAudioChunkReflective(pcm, frames)

        invokePlayChunkWithCorrection(player, chunk)

        val record = fakeSink.writes.firstOrNull()
            ?: error("expected one write to fake sink")
        assertTrue(
            "unmuted writes must pass PCM through unchanged",
            record.snapshotFirstBytes.all { it == 0x42.toByte() },
        )
    }

    /**
     * Build a SyncAudioPlayer wired to the shared `nowNs` clock and a
     * relaxed time-filter mock. Used by the watchdog tests.
     */
    private fun newPlayerForWatchdog(): SyncAudioPlayer {
        val timeFilter = mockk<SendspinTimeFilter>(relaxed = true)
        every { timeFilter.isReady } returns true
        every { timeFilter.serverToClient(any()) } answers { firstArg() }
        every { timeFilter.clientToServer(any()) } answers { firstArg() }
        every { timeFilter.offsetMicros } returns 0L
        every { timeFilter.measurementCountValue } returns 10

        val fakeSink = FakeAudioSink()
        return SyncAudioPlayer(
            timeFilter = timeFilter,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            nowNs = nowNs,
            sinkFactory = { _, _, _, _ -> fakeSink },
        )
    }

    /** Invoke the private checkStuckState(nowUs) method using the mocked clock. */
    private fun invokeCheckStuckState(player: SyncAudioPlayer) {
        val method = SyncAudioPlayer::class.java.getDeclaredMethod("checkStuckState", Long::class.java)
        method.isAccessible = true
        method.invoke(player, now / 1000)
    }

    @Test
    fun `watchdog warns when non-PLAYING state persists with chunks arriving`() {
        now = 0L
        val player = newPlayerForWatchdog()

        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)
        // Simulate chunks arriving: set totalQueuedSamples > 0.
        val totalQueuedSamples = getField<AtomicLong>(player, "totalQueuedSamples")
        totalQueuedSamples.set(sampleRate.toLong() * 5)  // 5 seconds of audio

        // First tick: establishes baseline, no warning yet.
        invokeCheckStuckState(player)
        val warn1: Long = getField(player, "lastStuckWarningAtUs")
        assertEquals("no warning on first observation", 0L, warn1)

        // Advance clock past the 5 s stuck threshold.
        now = 6_000_000_000L
        invokeCheckStuckState(player)
        val warn2: Long = getField(player, "lastStuckWarningAtUs")
        assertNotEquals("warning should have fired after 5s stuck", 0L, warn2)
    }

    /**
     * Put a player into the "waiting for alignment" state. Returns the
     * FakeAudioSink so the caller can script timestamps.
     *
     * To reach the startErr > tolerance branch of handleStartGatingDacAware
     * we need:
     *   - audioSink non-null and dacTimestampsStable
     *   - pendingToDacUs > 0 (non-zero fakeSink.getTimestamp().framePosition
     *     and totalFramesWritten > framePosition)
     *   - chunkQueue has a head chunk whose serverTimeMicros is far in the
     *     future relative to nowMicros, so startErrUs computes positive
     *     and exceeds START_PAD_MAX_US (20 ms)
     */
    private fun setupAlignmentWaitState(player: SyncAudioPlayer): FakeAudioSink {
        // SyncAudioPlayer.initialize() is
        // unavailable in JVM tests (AudioTrack.getMinBufferSize is Android-only),
        // so we inject the audioSink via reflection instead of going through
        // the sinkFactory init path.
        val fakeSink = FakeAudioSink()
        setField(player, "audioSink", fakeSink)
        setField(player, "dacTimestampsStable", true)
        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)

        // DAC timestamp: framePosition=1 means DAC has produced 1 frame.
        // totalFramesWritten > 1 so pendingFrames > 0 and pendingToDacUs > 0.
        fakeSink.scriptTimestamp(framePosition = 1L, nanoTime = 0L)
        val totalFramesWritten = getField<AtomicLong>(player, "totalFramesWritten")
        totalFramesWritten.set(sampleRate.toLong())  // 1 s worth so pendingToDacUs > 0

        // Queue a single AudioChunk whose serverTime is 5 s ahead of nowMicros,
        // so startErrUs will be ~5 s (way above the 20 ms start window).
        val audioChunkClass = SyncAudioPlayer::class.java.declaredClasses
            .find { it.simpleName == "AudioChunk" }!!
        val ctor = audioChunkClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            ByteArray::class.java, Int::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        val futureServerTime = (now / 1000) + 5_000_000L
        val chunk = ctor.newInstance(futureServerTime, ByteArray(0), 0)
        @Suppress("UNCHECKED_CAST")
        val chunkQueue = getField<java.util.Queue<Any>>(player, "chunkQueue")
        chunkQueue.add(chunk)

        return fakeSink
    }

    @Test
    fun `alignment wait log emits entry on first call and not on immediate follow-up`() {
        now = 0L
        val player = newPlayerForWatchdog()
        val sink = setupAlignmentWaitState(player)

        // Initial state: alignment tracking fields are 0.
        assertEquals(0L, getField<Long>(player, "alignmentWaitStartedAtUs"))
        assertEquals(0L, getField<Long>(player, "alignmentWaitLastLoggedUs"))

        // First call: entry log fires; fields take the current clock value.
        now = 100_000_000L  // 100 ms
        invokeHandleStartGatingDacAware(player, sink)
        val startedAfterFirst = getField<Long>(player, "alignmentWaitStartedAtUs")
        val loggedAfterFirst = getField<Long>(player, "alignmentWaitLastLoggedUs")
        assertNotEquals("entry log must set alignmentWaitStartedAtUs", 0L, startedAfterFirst)
        assertEquals("on entry the two fields match", startedAfterFirst, loggedAfterFirst)

        // Immediate follow-up (no clock advance): no new log; fields unchanged.
        invokeHandleStartGatingDacAware(player, sink)
        invokeHandleStartGatingDacAware(player, sink)
        invokeHandleStartGatingDacAware(player, sink)
        assertEquals(
            "follow-up calls within 1 s must not change alignmentWaitStartedAtUs",
            startedAfterFirst, getField<Long>(player, "alignmentWaitStartedAtUs"),
        )
        assertEquals(
            "follow-up calls within 1 s must not change alignmentWaitLastLoggedUs",
            loggedAfterFirst, getField<Long>(player, "alignmentWaitLastLoggedUs"),
        )
    }

    @Test
    fun `alignment wait progress log fires after 1 second while still waiting`() {
        now = 0L
        val player = newPlayerForWatchdog()
        val sink = setupAlignmentWaitState(player)

        // Enter alignment wait at t=0.
        now = 100_000_000L  // 100 ms clock
        invokeHandleStartGatingDacAware(player, sink)
        val startedAt = getField<Long>(player, "alignmentWaitStartedAtUs")
        val loggedAtEntry = getField<Long>(player, "alignmentWaitLastLoggedUs")

        // Advance clock by 1.1 s so the progress-log gate (1 s interval) fires.
        // The chunk is still in the future relative to the new clock; the
        // DAC timestamp has to move with the clock to stay usable.
        now = 1_200_000_000L  // 1.2 s
        sink.scriptTimestamp(framePosition = 1L, nanoTime = now)
        invokeHandleStartGatingDacAware(player, sink)

        val loggedAfterProgress = getField<Long>(player, "alignmentWaitLastLoggedUs")
        assertNotEquals(
            "progress log must advance alignmentWaitLastLoggedUs after 1 s",
            loggedAtEntry, loggedAfterProgress,
        )
        assertEquals(
            "alignmentWaitStartedAtUs must not change during progress",
            startedAt, getField<Long>(player, "alignmentWaitStartedAtUs"),
        )
    }

    @Test
    fun `watchdog does not warn when non-PLAYING state has no buffered chunks`() {
        now = 0L
        val player = newPlayerForWatchdog()

        setField(player, "playbackState", PlaybackState.WAITING_FOR_START)
        // No buffered audio -- user paused / genuinely idle, not a deadlock.
        val totalQueuedSamples = getField<AtomicLong>(player, "totalQueuedSamples")
        totalQueuedSamples.set(0L)

        // First tick: establishes baseline.
        invokeCheckStuckState(player)

        // Advance past the 5 s threshold; watchdog must STILL stay silent.
        now = 6_000_000_000L
        invokeCheckStuckState(player)
        val warn: Long = getField(player, "lastStuckWarningAtUs")
        assertEquals("no warning when buffer is empty", 0L, warn)
    }

    // ========================================================================
    // Sync correction: start alignment, soft correction, one-shot resync
    // (roles/player/v1.md, "Playback Synchronization")
    // ========================================================================

    private val bytesPerFrame = channels * (bitDepth / 8)

    /** A player wired to a FakeAudioSink, as if initialize() had run. */
    private fun newSyncPlayer(state: PlaybackState): Pair<SyncAudioPlayer, FakeAudioSink> {
        val player = newPlayerForWatchdog()
        val sink = FakeAudioSink()
        setField(player, "audioSink", sink)
        setField(player, "dacTimestampsStable", true)
        setField(player, "playbackState", state)
        return player to sink
    }

    private fun makeChunk(serverTimeUs: Long, frames: Int): Any {
        val clazz = Class.forName("com.sendspindroid.sendspin.SyncAudioPlayer\$AudioChunk")
        val ctor = clazz.declaredConstructors.first()
        ctor.isAccessible = true
        return ctor.newInstance(serverTimeUs, ByteArray(frames * bytesPerFrame) { 0x42 }, frames)
    }

    private fun queueChunks(player: SyncAudioPlayer, firstServerTimeUs: Long, count: Int, frames: Int = 960) {
        val chunkQueue = getField<java.util.Queue<Any>>(player, "chunkQueue")
        val totalQueuedSamples = getField<AtomicLong>(player, "totalQueuedSamples")
        for (i in 0 until count) {
            chunkQueue.add(makeChunk(firstServerTimeUs + i * frames * 1_000_000L / sampleRate, frames))
            totalQueuedSamples.addAndGet(frames.toLong())
        }
    }

    /**
     * Script a fresh DAC timestamp with [pendingFrames] between the write
     * cursor and the DAC. Returns the local time (us) at which the next frame
     * written will reach the DAC; with the identity time filter that is also
     * its server time.
     */
    private fun scriptDac(player: SyncAudioPlayer, sink: FakeAudioSink, pendingFrames: Long = 12_000L): Long {
        val written = getField<AtomicLong>(player, "totalFramesWritten")
        if (written.get() < pendingFrames + 1) written.set(pendingFrames + 1)
        sink.scriptTimestamp(framePosition = written.get() - pendingFrames, nanoTime = now)
        return now / 1000 + pendingFrames * 1_000_000L / sampleRate
    }

    /** Measure and play one chunk whose sync error is [errorUs] (positive = late). */
    private fun playChunkWithError(player: SyncAudioPlayer, sink: FakeAudioSink, errorUs: Long, frames: Int = 960) {
        now += frames * 1_000_000_000L / sampleRate
        val dacTimeUs = scriptDac(player, sink)
        invokePlayChunkWithCorrection(player, makeChunk(dacTimeUs - errorUs, frames))
    }

    private fun framesWritten(sink: FakeAudioSink): Long = sink.totalBytesWritten.get() / bytesPerFrame

    @Test
    fun `start gating waits while the head chunk is not due`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.WAITING_FOR_START)
        val dacTimeUs = scriptDac(player, sink)
        queueChunks(player, dacTimeUs + 500_000L, count = 10)

        assertTrue("must keep waiting", invokeHandleStartGatingDacAware(player, sink))
        assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())
        assertEquals(0L, framesWritten(sink))
    }

    @Test
    fun `start gating pads silence so the head chunk starts on time`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.WAITING_FOR_START)
        val dacTimeUs = scriptDac(player, sink)
        queueChunks(player, dacTimeUs + 5_000L, count = 10)  // due in 5 ms

        assertEquals(false, invokeHandleStartGatingDacAware(player, sink))
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())
        assertEquals("5 ms of silence ahead of the head chunk", 240L, framesWritten(sink))
        assertEquals(0L, getField<Long>(player, "snapDropFrames"))
    }

    @Test
    fun `start gating drops the audio that is already late`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.WAITING_FOR_START)
        val dacTimeUs = scriptDac(player, sink)
        queueChunks(player, dacTimeUs - 30_000L, count = 10)  // 30 ms late

        assertEquals(false, invokeHandleStartGatingDacAware(player, sink))
        assertEquals(PlaybackState.PLAYING, player.getPlaybackState())

        // The first chunk (20 ms) is late in full; 10 ms of the second is.
        val chunkQueue = getField<java.util.Queue<Any>>(player, "chunkQueue")
        assertEquals(9, chunkQueue.size)
        assertEquals(480L, getField<Long>(player, "snapDropFrames"))

        invokePlayChunkWithCorrection(player, chunkQueue.peek()!!)
        assertEquals("only the on-time half of the chunk is written", 480L, framesWritten(sink))
        assertEquals(480 * bytesPerFrame, sink.writes.single().offset)
    }

    @Test
    fun `start gating does not align against a stalled track`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.WAITING_FOR_START)
        val dacTimeUs = scriptDac(player, sink)
        queueChunks(player, dacTimeUs, count = 10)  // due now, going by the timestamp

        // The timestamp is 500 ms old: the track has stalled, so it says
        // nothing about when the next frame will play. Kalman gating takes
        // over, and its scheduled start has not been reached.
        now += 500_000_000L
        setField(player, "scheduledStartLoopTimeUs", now / 1000 + 1_000_000L)

        assertTrue("must keep waiting", invokeHandleStartGatingDacAware(player, sink))
        assertEquals(PlaybackState.WAITING_FOR_START, player.getPlaybackState())
    }

    @Test
    fun `silence keepalive tops the track back up`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.WAITING_FOR_START)
        val method = SyncAudioPlayer::class.java.getDeclaredMethod("writeSilenceKeepAlive")
        method.isAccessible = true

        scriptDac(player, sink, pendingFrames = 12_000L)  // 250 ms pending: enough
        method.invoke(player)
        assertEquals(0L, framesWritten(sink))

        scriptDac(player, sink, pendingFrames = 2_400L)  // 50 ms pending
        method.invoke(player)
        assertEquals("150 ms deficit plus one 10 ms block", 7_200L + 480L, framesWritten(sink))
    }

    @Test
    fun `chunk inside the dead band is written unchanged`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        repeat(10) { playChunkWithError(player, sink, errorUs = 50L) }

        assertEquals(10 * 960L, framesWritten(sink))
        assertEquals(0L, player.getStats().framesDropped)
        assertEquals(0L, player.getStats().framesInserted)
    }

    @Test
    fun `late chunk drops one frame per 20ms`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        playChunkWithError(player, sink, errorUs = 300L)

        val write = sink.writes.single()
        assertEquals(0, write.offset)
        assertEquals("last frame left out", 959 * bytesPerFrame, write.size)
        assertEquals(1L, player.getStats().framesDropped)
    }

    @Test
    fun `early chunk repeats its last frame once per 20ms`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        playChunkWithError(player, sink, errorUs = -300L)

        val writes = sink.writes
        assertEquals(2, writes.size)
        assertEquals(960 * bytesPerFrame, writes[0].size)
        assertEquals("last frame repeated", 959 * bytesPerFrame, writes[1].offset)
        assertEquals(bytesPerFrame, writes[1].size)
        assertEquals(1L, player.getStats().framesInserted)
    }

    @Test
    fun `soft correction stays within the speed limit whatever the chunk size`() {
        // Spec: effective speed within +/-0.5% over any 150 ms. A persistent
        // error must still yield at most one frame per 20 ms (0.104%).
        for (frames in listOf(96, 720, 960, 4_800)) {
            now = 1_000_000_000L
            val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)
            val chunks = 48_000 / frames * 3  // 3 seconds

            var worstWindowDrops = 0L
            val dropsAt = ArrayList<Long>()
            repeat(chunks) {
                playChunkWithError(player, sink, errorUs = 800L, frames = frames)
                dropsAt.add(player.getStats().framesDropped)
                // 150 ms window, rounded up to whole chunks
                val windowChunks = (7_200 + frames - 1) / frames
                val before = if (dropsAt.size > windowChunks) dropsAt[dropsAt.size - 1 - windowChunks] else 0L
                worstWindowDrops = maxOf(worstWindowDrops, dropsAt.last() - before)
            }

            val slots = chunks.toLong() * frames / 960
            assertEquals("one frame per 20 ms with $frames-frame chunks", slots, player.getStats().framesDropped)
            assertEquals(chunks.toLong() * frames - slots, framesWritten(sink))
            assertTrue(
                "$worstWindowDrops frames dropped in 150 ms with $frames-frame chunks",
                worstWindowDrops <= 0.005 * 7_200
            )
            assertEquals("no one-shot resync", 0L, player.getStats().syncCorrections)
        }
    }

    @Test
    fun `error past the floor is resynced in one shot by dropping the late prefix`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        // The first reading has no earlier estimate to agree with: soft
        // correction only (one frame).
        playChunkWithError(player, sink, errorUs = 5_000L)
        assertEquals(0L, player.getStats().syncCorrections)
        assertEquals(959L, framesWritten(sink))

        playChunkWithError(player, sink, errorUs = 5_000L)
        assertEquals("5 ms prefix dropped", 959L + 720L, framesWritten(sink))
        assertEquals(240 * bytesPerFrame, sink.writes.last().offset)
        assertEquals(1L, player.getStats().syncCorrections)

        // The estimate starts over; no second resync while it settles.
        repeat(10) { playChunkWithError(player, sink, errorUs = 5_000L) }
        assertEquals(1L, player.getStats().syncCorrections)
        assertEquals(959L + 720L + 10 * 960L, framesWritten(sink))
    }

    @Test
    fun `error past the floor is resynced in one shot by inserting silence when early`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        playChunkWithError(player, sink, errorUs = -5_000L)
        playChunkWithError(player, sink, errorUs = -5_000L)

        assertEquals("5 ms of silence ahead of the second chunk", 961L + 240L + 960L, framesWritten(sink))
        assertTrue(sink.writes[sink.writes.size - 2].snapshotFirstBytes.all { it == 0.toByte() })
        assertEquals(1L, player.getStats().syncCorrections)
    }

    @Test
    fun `single outlier reading does not trigger a one-shot resync`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)

        repeat(50) { playChunkWithError(player, sink, errorUs = 0L) }
        playChunkWithError(player, sink, errorUs = 5_000L)
        repeat(50) { playChunkWithError(player, sink, errorUs = 0L) }

        assertEquals(0L, player.getStats().syncCorrections)
    }

    @Test
    fun `no corrections of either kind during the startup grace period`() {
        now = 1_000_000_000L
        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)
        setField(player, "playingStateEnteredAtUs", now / 1000)

        repeat(10) { playChunkWithError(player, sink, errorUs = 5_000L) }  // 200 ms

        assertEquals(10 * 960L, framesWritten(sink))
        assertEquals(0L, player.getStats().syncCorrections)
    }

    /**
     * Closed loop against a simulated DAC whose clock runs [dacPpm] off the
     * local clock, read through timestamps with +/-650us of jitter (as
     * measured on a tablet). Returns the worst true error after settling.
     */
    private fun simulateDrift(dacPpm: Double, startErrorUs: Long): Triple<Long, SyncAudioPlayer.SyncStats, Long> {
        val dacRate = sampleRate * (1 + dacPpm * 1e-6)  // frames per second of local time
        val jitter = java.util.Random(42)
        var nowUs = 1_000_000L
        now = nowUs * 1000

        val (player, sink) = newSyncPlayer(PlaybackState.PLAYING)
        setField(player, "playingStateEnteredAtUs", nowUs)  // as after a real start
        val written = getField<AtomicLong>(player, "totalFramesWritten")
        written.set((nowUs * dacRate / 1e6).toLong() + 12_000L)  // 250 ms pending
        val firstServerTimeUs = (written.get() * 1e6 / dacRate).toLong() - startErrorUs

        var worstUs = 0L
        val chunks = 60 * 50  // 60 seconds
        for (k in 0 until chunks) {
            nowUs += 20_000L
            now = nowUs * 1000
            val tsUs = nowUs - 5_000L
            sink.scriptTimestamp(
                framePosition = (tsUs * dacRate / 1e6).toLong(),
                nanoTime = (tsUs + jitter.nextInt(1_301) - 650) * 1000,
            )
            val serverTimeUs = firstServerTimeUs + k * 20_000L
            val trueErrorUs = (written.get() * 1e6 / dacRate).toLong() - serverTimeUs
            if (k > 100) worstUs = maxOf(worstUs, kotlin.math.abs(trueErrorUs))
            invokePlayChunkWithCorrection(player, makeChunk(serverTimeUs, 960))
        }
        return Triple(worstUs, player.getStats(), framesWritten(sink))
    }

    @Test
    fun `holds sync within the accuracy target against clock drift and timestamp jitter`() {
        for (ppm in listOf(100.0, -100.0, 20.0)) {
            val (worstUs, stats, written) = simulateDrift(dacPpm = ppm, startErrorUs = 0L)

            assertTrue("worst error ${worstUs}us at ${ppm}ppm", worstUs < 500L)
            assertEquals("no one-shot resync at ${ppm}ppm", 0L, stats.syncCorrections)
            // 60 s at |ppm| needs about 2.9 * |ppm| frames; far below 1 per 20 ms.
            val corrections = stats.framesDropped + stats.framesInserted
            assertTrue("$corrections corrections at ${ppm}ppm", corrections < 6 * kotlin.math.abs(ppm))
            assertEquals(60 * 48_000L - stats.framesDropped + stats.framesInserted, written)
        }
    }

    @Test
    fun `slews out a start offset inside the floor without a resync`() {
        val (worstUs, stats, _) = simulateDrift(dacPpm = 0.0, startErrorUs = 700L)

        assertTrue("worst error ${worstUs}us after settling", worstUs < 500L)
        assertEquals(0L, stats.syncCorrections)
        assertTrue("dropped ${stats.framesDropped}", stats.framesDropped in 20..60)
    }
}
