package com.sendspindroid.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MinBufferEstimator] on synthetic chunk arrivals. Time is passed in, so the
 * five-minute window is exercised without waiting.
 *
 * The fake stream is 20 ms chunks sent in real time, 30 s ahead of playback.
 * Server and local clocks are the same clock here, so a chunk sent at `t` and
 * delayed by `d` arrives at `t + d`.
 */
class MinBufferEstimatorTest {

    private companion object {
        const val CHUNK_US = 20_000L
        const val SEND_AHEAD_US = 30_000_000L
    }

    private val estimator = MinBufferEstimator(floorMs = 350)

    /** One chunk sent at [sentMs], arriving [delayMs] later. */
    private fun chunk(sentMs: Long, delayMs: Long): Boolean {
        val sent = sentMs * 1000
        return estimator.addChunk(sent + SEND_AHEAD_US, sent, sent + delayMs * 1000)
    }

    /** Real-time chunks from [fromMs] until [toMs], all [delayMs] late. Returns the number of changes. */
    private fun play(fromMs: Long, toMs: Long, delayMs: Long): Int =
        (fromMs until toMs step 20).count { chunk(it, delayMs) }

    @Test
    fun `starts at the floor and stays there on a clean link`() {
        assertEquals(350, estimator.minBufferMs)
        assertEquals(0, play(10_000, 70_000, delayMs = 12))
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `adds the worst delay to the floor in whole steps`() {
        play(10_000, 70_000, delayMs = 12)
        assertTrue(chunk(70_000, delayMs = 130))
        assertEquals(450, estimator.minBufferMs)
    }

    @Test
    fun `a clock error that makes the delay negative counts as none`() {
        play(10_000, 11_000, delayMs = 12)
        assertFalse(chunk(11_000, delayMs = -4))
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `one late chunk raises the value at once and it holds for the window`() {
        play(10_000, 70_000, delayMs = 12)
        assertTrue(chunk(70_000, delayMs = 220))
        assertEquals(550, estimator.minBufferMs)

        // Four and a half minutes of clean arrivals do not lower it.
        assertEquals(0, play(70_020, 340_000, delayMs = 12))
        assertEquals(550, estimator.minBufferMs)
    }

    @Test
    fun `the value comes back down once the late chunk leaves the window`() {
        play(10_000, 70_000, delayMs = 12)
        chunk(70_000, delayMs = 220)

        assertEquals(1, play(70_020, 410_000, delayMs = 12))
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `steady jitter does not change the reported value`() {
        // Delays wandering between 5 and 45 ms never reach a step.
        val changes = (10_000L until 910_000L step 20).count { chunk(it, delayMs = 5 + (it / 20 * 7) % 41) }
        assertEquals(0, changes)
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `a worsening link is reported at most once a second`() {
        play(10_000, 20_000, delayMs = 12)
        // Delay climbing 10 ms per chunk for 600 ms, then holding at 300 ms.
        var changes = 0
        for (i in 0 until 30) if (chunk(20_000 + i * 20L, delayMs = 12 + i * 10L)) changes++
        changes += play(20_600, 23_000, delayMs = 300)
        assertEquals(2, changes)
        assertEquals(650, estimator.minBufferMs)
    }

    @Test
    fun `a gap longer than the window forgets the old delays`() {
        play(10_000, 70_000, delayMs = 180)
        assertEquals(500, estimator.minBufferMs)

        // Paused for an hour, then clean arrivals.
        play(3_670_000, 3_672_000, delayMs = 12)
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `the burst after a skip is not measured`() {
        play(10_000, 70_000, delayMs = 12)

        // The server sends 30 s of audio within 150 ms; we take 1.8 s to
        // read it, so the last of it is more than 1.6 s "late".
        val burstStart = 70_000_000L
        for (i in 0 until 1500) {
            val sent = burstStart + i * 100L
            val arrival = burstStart + 12_000 + i * 1_200L
            assertFalse(estimator.addChunk(burstStart + 500_000 + i * CHUNK_US, sent, arrival))
        }
        // Then real-time chunks again, the first few still queued behind the burst.
        val paced = burstStart + 150_000
        for (i in 0 until 85) {
            val sent = paced + i * CHUNK_US
            val arrival = burstStart + 1_812_000 + i * 1_200L
            assertFalse(estimator.addChunk(burstStart + 30_500_000 + i * CHUNK_US, sent, arrival))
        }
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `a stall mid-burst does not make a burst chunk a sample`() {
        play(10_000, 70_000, delayMs = 12)
        val burstStart = 70_000_000L
        var arrival = burstStart + 12_000
        for (i in 0 until 1500) {
            // We stop reading for 30 ms halfway through.
            arrival += if (i == 750) 30_000L else 1_200L
            estimator.addChunk(burstStart + 500_000 + i * CHUNK_US, burstStart + i * 100L, arrival)
        }
        assertEquals(350, estimator.minBufferMs)
    }

    @Test
    fun `chunks held up by the network and delivered together count once, at their worst`() {
        play(10_000, 70_000, delayMs = 12)
        // 300 ms stall: fifteen chunks sent in real time arrive at the same moment.
        for (i in 0 until 15) chunk(70_000 + i * 20L, delayMs = 300 - i * 20L)
        play(70_300, 72_000, delayMs = 12)
        assertEquals(650, estimator.minBufferMs)
    }
}
