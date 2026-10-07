package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.AudioSink
import com.sendspindroid.sendspin.audio.FakeAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Fork: the dead-AudioTrack recovery (sendspinlite port) on top of upstream's
 * playbackLoopStep / resetRequested model. Drives the loop step directly, as
 * SyncAudioPlayerLoopTest does.
 */
class SyncAudioPlayerSinkRecoveryTest {

    /** A FakeAudioSink whose writes fail while [dead] is set. */
    private class DeadableSink(val fake: FakeAudioSink = FakeAudioSink()) : AudioSink by fake {
        @Volatile var dead = false
        override fun write(buffer: ByteArray, offset: Int, size: Int): Int =
            if (dead) -6 else fake.write(buffer, offset, size)  // AudioTrack.ERROR_DEAD_OBJECT
    }

    private val now = AtomicLong(10_000_000_000L)
    private val sinks = mutableListOf<DeadableSink>()
    private var exhausted = 0

    private fun newPlayer(factory: () -> AudioSink): SyncAudioPlayer =
        SyncAudioPlayer(
            timeFilter = SendspinTimeFilter(),
            sampleRate = 48_000,
            channels = 2,
            bitDepth = 16,
            nowNs = { now.get() },
            sinkFactory = { _, _, _, _ -> factory() },
        ).apply {
            initialize()
            field<AtomicBoolean>(this, "isPlaying").set(true)
            setStateCallback(object : SyncAudioPlayerCallback {
                override fun onPlaybackStateChanged(state: PlaybackState) {}
                override fun onBufferExhausted() { exhausted++ }
            })
        }

    private val stepMethod = SyncAudioPlayer::class.java.getDeclaredMethod("playbackLoopStep")
        .apply { isAccessible = true }

    private fun step(p: SyncAudioPlayer) { stepMethod.invoke(p) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(p: SyncAudioPlayer, name: String): T =
        SyncAudioPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(p) as T

    @Test
    fun `a dead track is replaced and the loop resets onto the new one`() {
        val player = newPlayer { DeadableSink().also { sinks += it } }
        sinks[0].dead = true

        repeat(60) { step(player) }

        assertEquals("one replacement sink", 2, sinks.size)
        assertEquals(1, sinks[0].fake.releaseCallCount.get())
        assertSame(sinks[1], field<AudioSink?>(player, "audioSink"))
        assertEquals(0, field<Int>(player, "consecutiveWriteFailures"))

        // The reset the recovery requested runs on the next step and plays
        // silence into the new track.
        repeat(3) { step(player) }
        assertTrue(sinks[1].fake.flushCallCount.get() >= 1)
        assertTrue(sinks[1].fake.totalBytesWritten.get() > 0)
    }

    @Test
    fun `a persistent fault gives up after the recovery budget`() {
        val player = newPlayer { DeadableSink().also { it.dead = true; sinks += it } }

        // Each attempt is spaced past the 1 s backoff: 10 recreates, then the
        // 11th round finds the budget spent. (PlaybackService releases the
        // player on that first callback.)
        repeat(11) {
            repeat(60) { step(player) }
            now.addAndGet(1_100_000_000L)
        }

        assertEquals("initial sink + 10 recreates", 11, sinks.size)
        assertEquals(1, exhausted)
    }
}
