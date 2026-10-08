package com.sendspindroid.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsStateTest {

    @Test
    fun `durations use the stream's sample rate`() {
        val state = StatsState(streamSampleRate = 44_100, queuedSamples = 44_100)

        assertEquals(1000L, state.queuedMs)
        assertEquals(10.0, state.framesToMs(441), 0.001)
    }

    @Test
    fun `durations assume 48 kHz before a stream has started`() {
        val state = StatsState(queuedSamples = 96_000)

        assertEquals(2000L, state.queuedMs)
        assertEquals(1.0, state.framesToMs(48), 0.001)
    }

    @Test
    fun `clock drift is shown as what it adds up to in an hour`() {
        // 10 parts per million of 3,600,000 ms
        assertEquals(36.0, StatsState(clockDriftPpm = 10.0).clockDriftMsPerHour, 0.001)
        assertEquals(-9.0, StatsState(clockDriftPpm = -2.5).clockDriftMsPerHour, 0.001)
    }

    @Test
    fun `a smoothed error under the resync limit is good, in either direction`() {
        assertEquals(ThresholdStatus.GOOD, getSyncErrorStatus(0))
        assertEquals(ThresholdStatus.GOOD, getSyncErrorStatus(-999))
        assertEquals(ThresholdStatus.WARNING, getSyncErrorStatus(1_000))
        assertEquals(ThresholdStatus.WARNING, getSyncErrorStatus(-4_999))
        assertEquals(ThresholdStatus.BAD, getSyncErrorStatus(5_000))
    }
}
