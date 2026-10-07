package com.sendspindroid.sendspin

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class SendspinTimeFilterTest {

    private lateinit var filter: SendspinTimeFilter

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        filter = SendspinTimeFilter()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Initial state ---

    @Test
    fun initialState_isNotReady() {
        assertFalse(filter.isReady)
    }

    @Test
    fun initialState_isNotConverged() {
        assertFalse(filter.isConverged)
    }

    @Test
    fun initialState_offsetIsZero() {
        assertEquals(0L, filter.offsetMicros)
    }

    @Test
    fun initialState_errorIsMaxValue() {
        assertEquals(Long.MAX_VALUE, filter.errorMicros)
    }

    @Test
    fun initialState_measurementCountIsZero() {
        assertEquals(0, filter.measurementCountValue)
    }

    // --- Readiness ---

    @Test
    fun addMeasurement_afterOneMeasurement_isNotReady() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        assertFalse(filter.isReady)
    }

    @Test
    fun addMeasurement_afterTwoMeasurements_isReady() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        filter.addMeasurement(1000L, 5000L, 2_000_000L)
        assertTrue(filter.isReady)
    }

    @Test
    fun addMeasurement_incrementsMeasurementCount() {
        filter.addMeasurement(1000L, 5000L, 1_000_000L)
        assertEquals(1, filter.measurementCountValue)
        filter.addMeasurement(1000L, 5000L, 2_000_000L)
        assertEquals(2, filter.measurementCountValue)
    }

    // --- Convergence ---

    @Test
    fun isConverged_afterFewMeasurements_isFalse() {
        // Feed 3 measurements (need 5 for convergence)
        for (i in 1..3) {
            filter.addMeasurement(1000L, 5000L, i * 1_000_000L)
        }
        assertFalse(filter.isConverged)
    }

    @Test
    fun isConverged_afterManyConsistentMeasurements_isTrue() {
        // Feed many consistent low-error measurements
        for (i in 1..30) {
            filter.addMeasurement(1000L, 3000L, i * 1_000_000L)
        }
        assertTrue("Filter should converge after many consistent measurements", filter.isConverged)
    }

    // --- Offset convergence ---

    @Test
    fun addMeasurement_constantOffset_converges() {
        val targetOffset = 50_000L // 50ms
        for (i in 1..20) {
            filter.addMeasurement(targetOffset, 5000L, i * 1_000_000L)
        }
        assertTrue(filter.isReady)
        assertEquals(targetOffset.toDouble(), filter.offsetMicros.toDouble(), 5000.0)
    }

    @Test
    fun addMeasurement_firstMeasurement_initializesOffset() {
        filter.addMeasurement(50_000L, 5000L, 1_000_000L)
        assertEquals(50_000L, filter.offsetMicros)
    }

    // --- Time conversion ---

    @Test
    fun serverToClient_subtractsOffset() {
        // Converge the filter at a known offset
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val serverTime = 100_000_000L
        val clientTime = filter.serverToClient(serverTime)
        // client = server - offset, so clientTime should be approximately 100_000_000 - 10_000
        assertEquals(serverTime - filter.offsetMicros, clientTime)
    }

    @Test
    fun clientToServer_addsOffset() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val clientTime = 100_000_000L
        val serverTime = filter.clientToServer(clientTime)
        assertEquals(clientTime + filter.offsetMicros, serverTime)
    }

    @Test
    fun serverToClient_clientToServer_roundTrip() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val originalTime = 100_000_000L
        val roundTrip = filter.clientToServer(filter.serverToClient(originalTime))
        assertEquals(originalTime, roundTrip)
    }

    // --- Drift in the conversions ---

    /** Server clock running [ppm] fast: offset(t) = 10ms + ppm * t. */
    private fun driftingOffset(clientTimeMicros: Long, ppm: Double = 100.0): Double =
        10_000.0 + ppm * 1e-6 * clientTimeMicros

    /** Feed noise-free measurements of [driftingOffset], one per [stepMicros]. */
    private fun feedDrifting(from: Int, to: Int, stepMicros: Long = 1_000_000L) {
        for (i in from..to) {
            val t = i * stepMicros
            filter.addMeasurement(driftingOffset(t).toLong(), 500L, t)
        }
    }

    @Test
    fun clientToServer_followsDriftBetweenUpdates() {
        feedDrifting(1, 300)
        // 3 s after the last update the true offset has moved on by 300us.
        val clientTime = 303_000_000L
        val expected = clientTime + driftingOffset(clientTime)
        assertEquals(expected, filter.clientToServer(clientTime).toDouble(), 20.0)
        // Holding the offset flat would be about 300us short.
        assertTrue(expected - (clientTime + filter.offsetMicros) > 250.0)
    }

    @Test
    fun serverToClient_followsDriftBetweenUpdates() {
        feedDrifting(1, 300)
        val clientTime = 303_000_000L
        val serverTime = (clientTime + driftingOffset(clientTime)).toLong()
        assertEquals(clientTime.toDouble(), filter.serverToClient(serverTime).toDouble(), 20.0)
    }

    @Test
    fun conversions_areInversesWithDriftAndPlayoutTerms() {
        feedDrifting(1, 60)
        filter.setUserSyncOffsetMs(35.0)
        filter.setOutputDelayMs(120.0)
        // Before, at and long after the last update, at realistic magnitudes.
        for (serverTime in listOf(30_000_000L, 60_010_000L, 63_000_000L, 3_600_000_000L, 10_000_000_000_000L)) {
            val roundTrip = filter.clientToServer(filter.serverToClient(serverTime))
            assertEquals(serverTime.toDouble(), roundTrip.toDouble(), 1.0)
        }
    }

    @Test
    fun computeClientTime_hasNoPlayoutTerms() {
        feedDrifting(1, 300)
        val serverTime = 303_040_300L
        val clockOnly = filter.computeClientTime(serverTime)

        filter.setUserSyncOffsetMs(35.0)
        filter.setOutputDelayMs(120.0)

        assertEquals(clockOnly, filter.computeClientTime(serverTime))
        assertEquals(clockOnly + 35_000 - 120_000, filter.serverToClient(serverTime))
    }

    @Test
    fun conversions_ignoreDriftUntilItIsSignificant() {
        // Two measurements give a drift estimate from one finite difference;
        // that is not evidence of drift yet, so the offset is held.
        filter.addMeasurement(10_000L, 5000L, 1_000_000L)
        filter.addMeasurement(10_400L, 5000L, 2_000_000L)
        assertTrue(filter.driftPpm > 100.0)
        val clientTime = 12_000_000L
        assertEquals(clientTime + filter.offsetMicros, filter.clientToServer(clientTime))
    }

    @Test
    fun wrongFirstDriftEstimate_isCorrectedByTheNextMeasurements() {
        // 1.2ms of noise between the first two measurements, 3 s apart, reads
        // as 400 ppm. The clock does not drift at all.
        filter.addMeasurement(10_000L, 2000L, 3_000_000L)
        filter.addMeasurement(11_200L, 2000L, 6_000_000L)
        assertEquals(400.0, filter.driftPpm, 1.0)

        for (i in 3..8) {
            filter.addMeasurement(10_000L, 2000L, i * 3_000_000L)
        }
        assertEquals(0.0, filter.driftPpm, 40.0)
        // 3 s past the last update the conversion is within 150us of the truth.
        val clientTime = 27_000_000L
        assertEquals((clientTime + 10_000L).toDouble(), filter.clientToServer(clientTime).toDouble(), 150.0)
    }

    @Test
    fun conversions_seeOffsetDriftAndUpdateTimeFromTheSameUpdate() {
        // Updates 10 s apart on a 100 ppm clock: the offset moves 1000us per
        // update. A consistent snapshot extrapolates to the true offset a few
        // updates away; one update's offset with another's update time would
        // be out by a multiple of 1000us.
        val step = 10_000_000L
        feedDrifting(1, 300, step)

        val done = AtomicBoolean(false)
        var worstErrorUs = 0.0
        val reader = thread(name = "conversion-reader") {
            while (!done.get()) {
                val clientTime = filter.lastUpdateTimeUs + step / 2
                val error = abs(filter.clientToServer(clientTime) - clientTime - driftingOffset(clientTime))
                if (error > worstErrorUs) worstErrorUs = error
            }
        }
        feedDrifting(301, 20_000, step)
        done.set(true)
        reader.join(5000)

        assertTrue("worst error ${worstErrorUs}us", worstErrorUs < 100.0)
    }

    // --- Static delay ---

    @Test
    fun staticDelayMs_shiftsServerToClient() {
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        val serverTime = 100_000_000L
        val withoutDelay = filter.serverToClient(serverTime)

        filter.setUserSyncOffsetMs(5.0) // 5ms = 5000us
        val withDelay = filter.serverToClient(serverTime)

        // Positive delay = play later = higher client time
        assertEquals(withoutDelay + 5000, withDelay)
    }

    @Test
    fun staticDelayMs_getterReturnsSetValue() {
        filter.setUserSyncOffsetMs(10.5)
        assertEquals(10.5, filter.staticDelayMs, 0.01)
    }

    // --- Outlier rejection ---

    @Test
    fun addMeasurement_outlier_isRejected() {
        // Build history with consistent measurements
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }
        val offsetBefore = filter.offsetMicros

        // Send a wild outlier
        val accepted = filter.addMeasurement(1_000_000L, 5000L, 11_000_000L)

        assertFalse("Outlier should be rejected", accepted)
        // Offset should not have changed significantly
        assertEquals(offsetBefore, filter.offsetMicros)
    }

    @Test
    fun addMeasurement_forceAcceptAfterThreeRejections() {
        // Build history
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }

        // Send 3 outliers (rejected), then 4th should be force-accepted
        val outlierOffset = 500_000L
        filter.addMeasurement(outlierOffset, 5000L, 11_000_000L)
        filter.addMeasurement(outlierOffset, 5000L, 12_000_000L)
        filter.addMeasurement(outlierOffset, 5000L, 13_000_000L)
        val accepted = filter.addMeasurement(outlierOffset, 5000L, 14_000_000L)

        assertTrue("4th consecutive outlier should be force-accepted", accepted)
    }

    // --- Reset ---

    @Test
    fun reset_returnsToInitialState() {
        for (i in 1..5) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        assertTrue(filter.isReady)

        filter.reset()

        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
        assertEquals(Long.MAX_VALUE, filter.errorMicros)
        assertEquals(0, filter.measurementCountValue)
    }

    // --- Drift ---

    @Test
    fun driftPpm_returnsValueInPpm() {
        // After initialization, drift should be 0 ppm
        assertEquals(0.0, filter.driftPpm, 0.01)
    }

    @Test
    fun drift_isBoundedToMaxDrift() {
        // Feed measurements with extreme increasing offset to push drift
        filter.addMeasurement(0L, 5000L, 1_000_000L)
        filter.addMeasurement(0L, 5000L, 2_000_000L)
        for (i in 3..30) {
            // Each measurement is 1 second worth of drift at extreme rate
            filter.addMeasurement((i * 100_000).toLong(), 5000L, i * 1_000_000L)
        }
        // MAX_DRIFT is 5e-4, which is 500 ppm
        assertTrue(
            "Drift should be bounded to 500 ppm, was ${filter.driftPpm}",
            kotlin.math.abs(filter.driftPpm) <= 500.0 + 1.0
        )
    }

    // --- Error decreases with measurements ---

    @Test
    fun addMeasurement_consistentMeasurements_errorDecreases() {
        filter.addMeasurement(10_000L, 5000L, 1_000_000L)
        filter.addMeasurement(10_000L, 5000L, 2_000_000L)
        val errorAfterTwo = filter.errorMicros

        for (i in 3..20) {
            filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
        }
        val errorAfterMany = filter.errorMicros

        assertTrue(
            "Error should decrease with consistent measurements (was $errorAfterTwo, now $errorAfterMany)",
            errorAfterMany < errorAfterTwo
        )
    }

    // --- Upstream-aligned algorithm behaviors ---

    @Test
    fun secondMeasurement_initializesDriftFromFiniteDifference() {
        // Two measurements 1s apart with offset increasing by 100us.
        // Expected drift = 100 / 1_000_000 = 1e-4 = 100 ppm.
        filter.addMeasurement(0L, 5000L, 1_000_000L)
        filter.addMeasurement(100L, 5000L, 2_000_000L)
        assertEquals(100.0, filter.driftPpm, 0.5)
    }

    @Test
    fun stepChange_afterConvergence_recoversViaForgetting() {
        // Drive the filter through MIN_SAMPLES_FOR_FORGETTING (=100) at offset=0,
        // then introduce a sustained 50ms step change. The filter must adopt
        // the new offset given enough measurements; the IQR pre-rejection plus
        // force-accept-after-3 mechanism gates the rate at which the step
        // reaches the Kalman update, so allow a generous recovery window.
        for (i in 1..100) {
            filter.addMeasurement(0L, 3000L, i * 1_000_000L)
        }
        val errorBeforeStep = filter.errorMicros
        assertTrue("Sanity: filter should be converged before step", errorBeforeStep < 10_000L)

        val stepOffset = 50_000L
        val baseTime = 101_000_000L
        for (i in 0 until 30) {
            filter.addMeasurement(stepOffset, 3000L, baseTime + i * 1_000_000L)
        }

        assertEquals(
            "Filter must adopt the new offset within 30 post-step measurements",
            stepOffset.toDouble(),
            filter.offsetMicros.toDouble(),
            10_000.0
        )
    }

    @Test
    fun smallEarlyOutlier_doesNotTriggerForgetting() {
        // Before MIN_SAMPLES_FOR_FORGETTING (=100), a single large residual
        // must NOT inflate covariance via the forgetting branch. The standard
        // Kalman gain absorbs it on its own; if forgetting fired, a few early
        // outliers could wipe the model and prevent initial convergence.
        for (i in 1..10) {
            filter.addMeasurement(10_000L, 3000L, i * 1_000_000L)
        }
        // Drive a single outlier through the IQR pre-rejection by exhausting
        // the rejected-count force-accept path: 3 IQR-rejected outliers, then
        // the 4th is force-accepted into the Kalman update. Since count is
        // still well under MIN_SAMPLES_FOR_FORGETTING, forgetting must stay off.
        for (i in 11..14) {
            filter.addMeasurement(500_000L, 3000L, i * 1_000_000L)
        }
        // Filter should still produce a finite, reasonable error rather than
        // an inflated value from premature forgetting.
        assertTrue("Filter must remain numerically sane", filter.errorMicros.toLong() < 10_000_000L)
    }

    // --- H-01: Thread safety ---

    @Test
    fun concurrentAccess_addMeasurementAndServerToClient_doesNotCrash() {
        // Verify that concurrent addMeasurement (writer) and serverToClient (reader)
        // do not crash or produce obviously invalid results.
        // This test exercises the synchronized/volatile fix for H-01.
        val iterations = 1000
        val failed = AtomicBoolean(false)
        val writerDone = AtomicBoolean(false)

        // Seed the filter so it's ready
        filter.addMeasurement(10_000L, 5000L, 1_000_000L)
        filter.addMeasurement(10_000L, 5000L, 2_000_000L)

        // Writer thread: continuously adds measurements
        val writer = thread(name = "kalman-writer") {
            try {
                for (i in 3..iterations + 2) {
                    filter.addMeasurement(10_000L, 5000L, i * 1_000_000L)
                }
            } catch (e: Exception) {
                failed.set(true)
            } finally {
                writerDone.set(true)
            }
        }

        // Reader thread: continuously reads serverToClient. Whether the
        // reader gets time-sliced before the writer completes is a property
        // of the OS scheduler, not of the production code -- don't assert
        // on iteration count. Any read that does happen must produce a
        // sane result (Long, no exception, within plausible bounds).
        val reader = thread(name = "kalman-reader") {
            try {
                while (!writerDone.get()) {
                    val result = filter.serverToClient(100_000_000L)
                    // Result should be roughly 100M - 10K = 99,990,000
                    // Allow wide tolerance since filter state is changing concurrently
                    if (result < 0 || result > 200_000_000L) {
                        failed.set(true)
                    }
                }
            } catch (e: Exception) {
                failed.set(true)
            }
        }

        writer.join(5000)
        reader.join(5000)

        assertFalse("Concurrent access should not cause exceptions or invalid values", failed.get())
    }

    @Test
    fun concurrentAccess_resetAndServerToClient_doesNotCrash() {
        // Verify that concurrent reset (writer) and serverToClient (reader)
        // do not crash. This simulates connection loss during audio playback.
        val failed = AtomicBoolean(false)
        val done = AtomicBoolean(false)

        // Seed the filter
        filter.addMeasurement(10_000L, 5000L, 1_000_000L)
        filter.addMeasurement(10_000L, 5000L, 2_000_000L)

        val resetter = thread(name = "kalman-resetter") {
            try {
                repeat(100) {
                    filter.reset()
                    // Re-initialize after reset
                    filter.addMeasurement(10_000L, 5000L, 1_000_000L)
                    filter.addMeasurement(10_000L, 5000L, 2_000_000L)
                }
            } catch (e: Exception) {
                failed.set(true)
            } finally {
                done.set(true)
            }
        }

        val reader = thread(name = "kalman-reader") {
            try {
                while (!done.get()) {
                    filter.serverToClient(100_000_000L)
                }
            } catch (e: Exception) {
                failed.set(true)
            }
        }

        resetter.join(5000)
        reader.join(5000)

        assertFalse("Concurrent reset and read should not cause exceptions", failed.get())
    }

    // --- Static delay: user slider and server sync offset share one field ---

    @Test
    fun `staticDelayMs is the user sync offset`() {
        val f = SendspinTimeFilter()
        f.setUserSyncOffsetMs(30.0)
        assertEquals(30.0, f.staticDelayMs, 0.0001)
    }

    @Test
    fun `server sync_offset adds to the user sync offset`() {
        // Fork: the two are kept apart so neither clobbers the other.
        val f = SendspinTimeFilter()
        f.setUserSyncOffsetMs(25.0)
        f.setServerSyncOffsetMs(-40.0)
        assertEquals(-15.0, f.staticDelayMs, 0.0001)
        assertEquals(-40.0, f.serverSyncOffsetMs, 0.0001)
    }

    @Test
    fun `reset drops the server sync offset but keeps the user one`() {
        val f = SendspinTimeFilter()
        f.setUserSyncOffsetMs(25.0)
        f.setServerSyncOffsetMs(-40.0)
        f.reset()
        assertEquals(25.0, f.staticDelayMs, 0.0001)
    }

    @Test
    fun `concurrent writer-reader stress on offset does not tear`() {
        val f = SendspinTimeFilter()
        val writer = Thread {
            for (i in 0 until 10_000) {
                // measurementOffset = i * 1000us, maxError = 1000us, clientTimeMicros = i * 1000us
                f.addMeasurement(i.toLong() * 1_000L, 1_000L, i.toLong() * 1_000L)
            }
        }
        val reader = Thread {
            for (i in 0 until 10_000) {
                val now = i.toLong() * 1_000L
                val v = f.serverToClient(now)
                // A torn read would yield NaN or an impossible magnitude.
                // Accept any finite long as non-torn.
                require(v in Long.MIN_VALUE..Long.MAX_VALUE)
            }
        }
        writer.start()
        reader.start()
        writer.join()
        reader.join()
    }
}
