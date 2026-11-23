package com.sendspindroid.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Fork (HW-301)
class SyncTraceTest {

    private val lines = mutableListOf<String>()
    private val rate = 48_000

    private fun SyncTrace.reading(atUs: Long, pos: Long, errorUs: Long = 0L) =
        record(atUs, pos, tsNanoTime = atUs * 1000 - 800_000L, written = pos + 9_600L,
            errorUs = errorUs, smoothedUs = errorUs / 2, soft = 0, dropped = 0L, inserted = 0L)

    @Test
    fun `dump writes a header and the readings oldest first`() {
        val trace = SyncTrace(capacity = 4)
        for (i in 0 until 3) trace.reading(1_000_000L + i * 20_000L, 48_000L + i * 960L, errorUs = i * 100L)
        trace.markOneShot(-250)

        trace.dump("flip pair", rate) { lines += it }

        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Sync trace [flip pair]: 3 readings"))
        assertTrue(lines[1], lines[1].contains("t=-40.0ms") && lines[1].contains("err=+0"))
        assertTrue(lines[3], lines[3].contains("t=0.0ms") && lines[3].contains("err=+200") &&
            lines[3].contains("os=-250") && lines[3].contains("pend=9600") && lines[3].contains("age=800"))
    }

    @Test
    fun `the ring keeps only the newest readings`() {
        val trace = SyncTrace(capacity = 3)
        for (i in 0 until 5) trace.reading(i * 20_000L, i * 960L, errorUs = i.toLong())

        trace.dump("x", rate) { lines += it }

        assertTrue(lines[0], lines[0].contains("3 readings"))
        assertEquals(listOf("err=+2", "err=+3", "err=+4"), lines.drop(1).map { Regex("err=[+-]\\d+").find(it)!!.value })
    }

    @Test
    fun `jit is how far the timestamp position strays from its own time`() {
        val trace = SyncTrace(capacity = 8)
        trace.reading(0L, 48_000L)
        trace.reading(20_000L, 48_960L)            // 960 frames in 20 ms: on time
        trace.reading(40_000L, 48_960L + 1_200L)   // 1200 frames in 20 ms: 5 ms ahead

        trace.dump("x", rate) { lines += it }

        assertTrue(lines[2], lines[2].contains("jit=+0"))
        assertTrue(lines[3], lines[3].contains("jit=+5000"))
    }
}
