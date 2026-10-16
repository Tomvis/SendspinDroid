package com.sendspindroid.sendspin

import com.sendspindroid.shared.log.Log
import com.sendspindroid.shared.platform.Platform
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Two-state Kalman filter that estimates the server-clock offset and the
 * drift between the two clocks from NTP-style 4-timestamp measurements.
 *
 * ## Conversions
 *
 * [serverToClient] and [clientToServer] apply the offset and, once the
 * drift estimate is statistically significant, the drift accumulated since
 * the last measurement:
 *
 *     server = client + offset + drift * (client - lastUpdate)
 *
 * This is the conversion of the upstream `Sendspin/time-filter` reference
 * and of aiosendspin's `compute_server_time` / `compute_client_time`. The
 * mapping follows the estimated clock rate between measurements, so the
 * playback reference does not hold still and then step at each update.
 *
 * The conversions run on the audio thread while measurements arrive on the
 * network thread. Everything a reader needs is published as one immutable
 * [Snapshot], so offset, drift and update time always belong together.
 *
 * This is the drift between the device's monotonic clock and the server
 * clock. The DAC has its own clock; `SyncAudioPlayer` measures what is
 * left against AudioTrack timestamps and corrects it by sample insert/drop.
 *
 * ## Reference
 *
 * A port of upstream `Sendspin/time-filter` (Apache-2.0) at 531e270,
 * `cpp/sendspin_time_filter.cpp`, expression for expression ("Clients MUST
 * use the time-filter algorithm"); SendspinTimeFilterGoldenTest holds it to
 * vectors from a transcription of the C++. What is added here does not
 * change its output: the readiness and convergence flags, and the playout
 * terms of [serverToClient] / [clientToServer].
 */
class SendspinTimeFilter {

    companion object {
        // Tunables: upstream's Config defaults.

        // Process-noise diffusion coefficients. Upstream defaults: zero offset
        // random walk (offset evolves only through drift*dt), and a tiny drift
        // random walk consistent with stable crystal oscillators.
        // Q grows by (coefficient)^2 * dt per microsecond of elapsed time.
        private const val PROCESS_STD_DEV = 0.0
        private const val DRIFT_PROCESS_STD_DEV = 1e-11
        private const val PROCESS_VARIANCE = PROCESS_STD_DEV * PROCESS_STD_DEV
        private const val DRIFT_PROCESS_VARIANCE = DRIFT_PROCESS_STD_DEV * DRIFT_PROCESS_STD_DEV

        // Measurement-variance pre-scaling. Per upstream PR #6, max_error
        // (= rtt/2) is a worst-case asymmetric-delay bound rather than a 1-sigma
        // estimate, so squaring it directly inflates R ~4x. Pre-scaling by 0.5
        // brings R = (max_error * 0.5)^2 = max_error^2 / 4.
        private const val MAX_ERROR_SCALE = 0.5

        // Adaptive forgetting. Fires when |residual| > FORGETTING_THRESHOLD *
        // max_error (a multiple of the measurement bound, not a fraction of
        // sigma). On fire, all four covariance entries are scaled by
        // FORGETTING_VARIANCE_FACTOR = FORGETTING_FACTOR^2 to accelerate
        // re-convergence. Forgetting is gated by MIN_SAMPLES_FOR_FORGETTING so
        // a few early outliers cannot prevent initial convergence.
        private const val FORGETTING_THRESHOLD = 3.0
        private const val FORGETTING_FACTOR = 2.0
        private const val FORGETTING_VARIANCE_FACTOR = FORGETTING_FACTOR * FORGETTING_FACTOR
        private const val MIN_SAMPLES_FOR_FORGETTING = 100

        // Drift-significance gate. The conversions only apply drift when
        // drift^2 > DRIFT_SIGNIFICANCE_THRESHOLD^2 * drift_covariance, i.e.,
        // when the estimate is at least k sigma from zero.
        private const val DRIFT_SIGNIFICANCE_THRESHOLD = 2.0
        private const val DRIFT_SIGNIFICANCE_THRESHOLD_SQUARED =
            DRIFT_SIGNIFICANCE_THRESHOLD * DRIFT_SIGNIFICANCE_THRESHOLD

        // Readiness and convergence reporting.
        private const val MIN_MEASUREMENTS = 2
        private const val MIN_MEASUREMENTS_FOR_CONVERGENCE = 5
        private const val MAX_ERROR_FOR_CONVERGENCE_US = 10_000L

        private const val TAG = "SendspinTimeFilter"
    }

    // Guards the filter state below (addMeasurement, reset).
    // Nothing outside the lock reads that state; other threads read [snapshot].
    private val lock = Any()

    // State vector: [offset, drift]
    private var offset: Double = 0.0
    private var drift: Double = 0.0

    // Covariance matrix (2x2, symmetric)
    private var offsetCovariance: Double = Double.POSITIVE_INFINITY
    private var offsetDriftCovariance: Double = 0.0
    private var driftCovariance: Double = 0.0

    // Client time of the last accepted measurement
    private var lastUpdateTime: Long = 0
    // Accepted measurements. Upstream's count stops at the forgetting gate;
    // this one goes on, for readiness and logs, and is compared the same way.
    private var measurementCount: Int = 0

    private var useDrift: Boolean = false

    /**
     * The filter state as other threads see it. Immutable, and replaced as a
     * whole by [publish] after every change, so a reader that takes the
     * reference once works with values from the same update.
     */
    private class Snapshot(
        val offset: Double = 0.0,
        val drift: Double = 0.0,
        val useDrift: Boolean = false,
        val lastUpdateTime: Long = 0,
        val offsetVariance: Double = Double.POSITIVE_INFINITY,
        val measurementCount: Int = 0
    ) {
        /** The drift the conversions apply: zero until the estimate is significant. */
        val effectiveDrift: Double get() = if (useDrift) drift else 0.0
    }

    @Volatile private var snapshot = Snapshot()

    /** Publish the current filter state. Call with [lock] held, after every change. */
    private fun publish() {
        snapshot = Snapshot(offset, drift, useDrift, lastUpdateTime, offsetCovariance, measurementCount)
    }

    // Static delay: the sync offset, a server's if it pushed one and the
    // user's otherwise (syncOffsetMicros). Output latency up to the DAC is
    // not part of it - the player schedules against AudioTrack timestamps,
    // which already include it. @Volatile: both fields are read by the audio
    // thread (serverToClient) and written from others.

    // The user's offset: the settings slider's value, set when the user moves
    // it and restored when a connection is ready, both on the main thread.
    @Volatile private var userSyncOffsetMicros: Long = 0

    // A server's push (client/sync_offset) stands in for the user's value
    // until the user sets one again or the next connection starts
    // (clearServerSyncOffset): it is that server's correction, not this
    // device's. reset() keeps it, as it also runs mid-connection.
    @Volatile private var serverSyncOffsetMicros: Long? = null

    private val syncOffsetMicros: Long
        get() = serverSyncOffsetMicros ?: userSyncOffsetMicros

    // Convergence tracking
    private var convergenceTimeMs: Long = 0L       // Time to reach isConverged
    private var firstMeasurementTimeMs: Long = 0L  // Timestamp of first measurement
    private var hasLoggedConvergence: Boolean = false

    /**
     * Whether enough measurements have been collected for reliable time conversion.
     * This is the minimum threshold - playback can start, but may need corrections.
     */
    val isReady: Boolean
        get() = snapshot.let { it.measurementCount >= MIN_MEASUREMENTS && it.offsetVariance.isFinite() }

    /**
     * Whether the filter has converged to a high-quality sync. Stricter
     * than [isReady]: requires `MIN_MEASUREMENTS_FOR_CONVERGENCE` accepted
     * measurements and an estimated offset standard deviation below
     * `MAX_ERROR_FOR_CONVERGENCE_US`. When true, sync corrections should
     * be minimal.
     */
    val isConverged: Boolean
        get() = snapshot.measurementCount >= MIN_MEASUREMENTS_FOR_CONVERGENCE &&
            errorMicros < MAX_ERROR_FOR_CONVERGENCE_US

    /**
     * Current estimated offset in microseconds.
     */
    val offsetMicros: Long
        get() = snapshot.offset.toLong()

    /**
     * Estimated error (standard deviation) in microseconds.
     */
    val errorMicros: Long
        get() = snapshot.offsetVariance.let { if (it.isFinite() && it >= 0) roundHalfAway(sqrt(it)) else Long.MAX_VALUE }

    /**
     * Number of measurements collected so far.
     */
    val measurementCountValue: Int
        get() = snapshot.measurementCount

    /**
     * Current drift in parts per million (ppm).
     * Positive = server clock running faster than client.
     */
    val driftPpm: Double
        get() = snapshot.drift * 1_000_000.0

    /**
     * Time of last measurement update in microseconds (client time).
     */
    val lastUpdateTimeUs: Long
        get() = snapshot.lastUpdateTime

    /**
     * Static delay in milliseconds: the user's or server's sync-offset
     * correction.
     *
     * Positive = delay playback (plays later), Negative = advance (plays earlier).
     */
    val staticDelayMs: Double
        get() = syncOffsetMicros / 1000.0

    /**
     * The spec's `output_delay_ms`: delay BEYOND the audio port, such as an
     * external amplifier or powered speaker.
     *
     * Deliberately separate from [staticDelayMs], the signed sync offset.
     * roles/player/v1.md is explicit that output delay "does not cover
     * processing delays before the port (DAC latency, audio buffers), which
     * the client compensates itself" - the player does that by scheduling
     * against AudioTrack timestamps.
     *
     * Non-negative by spec ("Negative values are not supported") and clamped
     * to 0-5000, unlike the signed [staticDelayMs].
     */
    @Volatile
    private var outputDelayMicros: Long = 0

    /** Output delay in milliseconds, 0-5000. */
    val outputDelayMs: Double
        get() = outputDelayMicros / 1000.0

    /**
     * Set the beyond-the-port output delay, clamped to the spec's 0-5000 ms.
     *
     * Sign: audio that takes [ms] longer to reach the listener must LEAVE the
     * port that much earlier, so this SUBTRACTS from the client-side play
     * time. That is the opposite direction to [staticDelayMs], which adds.
     */
    fun setOutputDelayMs(ms: Double) {
        outputDelayMicros = (ms.coerceIn(0.0, 5000.0) * 1000).toLong()
    }

    /**
     * Write the user's manual sync-offset correction (milliseconds). Called
     * when the user changes it in the settings, and to restore the saved
     * setting when a connection is ready.
     *
     * @param overrideServer false when this restores the saved setting
     *   rather than the user changing it: a server's offset then stays in
     *   force.
     */
    fun setUserSyncOffsetMs(ms: Double, overrideServer: Boolean = true) {
        userSyncOffsetMicros = (ms * 1000).toLong()
        if (overrideServer) serverSyncOffsetMicros = null
    }

    /**
     * Write a server-pushed sync-offset (from `client/sync_offset`). It
     * replaces the user's value, as the latest correction to when this
     * device should play, until the user sets one or the next connection
     * starts ([clearServerSyncOffset]).
     */
    fun setServerSyncOffsetMs(ms: Double) {
        serverSyncOffsetMicros = (ms * 1000).toLong()
    }

    /**
     * Drop a server's sync offset: the user's applies again. Called when a
     * connection starts, not from [reset], which also runs mid-connection.
     */
    fun clearServerSyncOffset() {
        serverSyncOffsetMicros = null
    }

    /**
     * Time to reach convergence in milliseconds.
     * 0 if not yet converged.
     */
    val convergenceTimeMillis: Long
        get() = convergenceTimeMs

    /**
     * Reset the filter to initial state.
     * Thread-safe: synchronized to prevent concurrent mutation.
     */
    fun reset() = synchronized(lock) {
        offset = 0.0
        drift = 0.0
        offsetCovariance = Double.POSITIVE_INFINITY
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        lastUpdateTime = 0
        measurementCount = 0
        useDrift = false
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        publish()
    }

    /**
     * Add a new time measurement to the filter: upstream's `update`.
     *
     * Thread-safe: synchronized to prevent concurrent mutation of filter state.
     *
     * @param measurementOffset The measured offset in microseconds
     * @param maxError Half the round-trip delay in microseconds
     * @param clientTimeMicros The client timestamp when measurement was taken
     * @return false if the measurement was skipped, as upstream skips it: not
     *   later than the last one, or with a [maxError] that is not positive
     */
    fun addMeasurement(
        measurementOffset: Long,
        maxError: Long,
        clientTimeMicros: Long
    ): Boolean = synchronized(lock) {
        if (clientTimeMicros <= lastUpdateTime) {
            // Skip non-monotonic timestamps (duplicates or out-of-order packets)
            return false
        }
        if (maxError <= 0) {
            // A zero max_error would collapse the measurement variance (and the
            // forgetting cutoff) to zero, and a negative one is not a valid half-RTT
            return false
        }

        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        val dtSquared = dt * dt
        lastUpdateTime = clientTimeMicros

        val updateStdDev = maxError * MAX_ERROR_SCALE
        val measurementVariance = updateStdDev * updateStdDev

        if (measurementCount == 0) {
            firstMeasurementTimeMs = Platform.currentTimeMillis()
        }

        when (measurementCount) {
            0 -> {
                // First measurement establishes the offset baseline
                offset = measurementOffset.toDouble()
                offsetCovariance = measurementVariance
                drift = 0.0
            }
            1 -> {
                // Second measurement: initial drift from finite differences
                drift = (measurementOffset - offset) / dt
                offset = measurementOffset.toDouble()
                driftCovariance = (offsetCovariance + measurementVariance) / dtSquared
                offsetCovariance = measurementVariance
            }
            else -> kalmanUpdate(measurementOffset, maxError, dt, dtSquared, measurementVariance)
        }
        measurementCount++
        publish()
        checkConvergence()
        return true
    }

    /**
     * Check for convergence and log milestone.
     */
    private fun checkConvergence() {
        if (!hasLoggedConvergence && isConverged) {
            hasLoggedConvergence = true
            convergenceTimeMs = Platform.currentTimeMillis() - firstMeasurementTimeMs
            Log.i(TAG, "Kalman locked: time=${convergenceTimeMs}ms, " +
                    "offset=${offset.toLong()}us (+/-$errorMicros), drift=${String.format("%.2f", driftPpm)}ppm")
        }
    }

    private fun kalmanUpdate(
        measurement: Long,
        maxError: Long,
        dt: Double,
        dtSquared: Double,
        measurementVariance: Double,
    ) {
        // Predict: x = F * x, P = F * P * F^T + Q with F = [[1, dt], [0, 1]]
        // and Q = diag(PROCESS_VARIANCE, DRIFT_PROCESS_VARIANCE) * dt
        val offsetPredicted = offset + drift * dt

        val driftProcessVariance = dt * DRIFT_PROCESS_VARIANCE
        var newDriftCovariance = driftCovariance + driftProcessVariance

        var newOffsetDriftCovariance = offsetDriftCovariance + driftCovariance * dt

        val offsetProcessVariance = dt * PROCESS_VARIANCE
        var newOffsetCovariance = offsetCovariance + 2 * offsetDriftCovariance * dt +
            driftCovariance * dtSquared + offsetProcessVariance

        // Adaptive forgetting: a residual beyond FORGETTING_THRESHOLD * max_error
        // is a step (route flip, server clock jump): inflate the predicted
        // covariance so the gain adopts it quickly. Only once
        // MIN_SAMPLES_FOR_FORGETTING measurements have built a history.
        val residual = measurement - offsetPredicted
        val maxResidualCutoff = maxError * FORGETTING_THRESHOLD

        if (measurementCount >= MIN_SAMPLES_FOR_FORGETTING && abs(residual) > maxResidualCutoff) {
            newDriftCovariance *= FORGETTING_VARIANCE_FACTOR
            newOffsetDriftCovariance *= FORGETTING_VARIANCE_FACTOR
            newOffsetCovariance *= FORGETTING_VARIANCE_FACTOR
        }

        // Update: K = P * H^T * S^-1, x = x + K * y, P = (I - K * H) * P
        // with H = [1, 0] and S = P[0,0] + R
        val uncertainty = 1.0 / (newOffsetCovariance + measurementVariance)

        val offsetGain = newOffsetCovariance * uncertainty
        val driftGain = newOffsetDriftCovariance * uncertainty

        offset = offsetPredicted + offsetGain * residual
        drift += driftGain * residual

        driftCovariance = newDriftCovariance - driftGain * newOffsetDriftCovariance
        offsetDriftCovariance = newOffsetDriftCovariance - driftGain * newOffsetCovariance
        offsetCovariance = newOffsetCovariance - offsetGain * newOffsetCovariance

        // Only apply drift in the conversions once it is significant
        useDrift = drift * drift > DRIFT_SIGNIFICANCE_THRESHOLD_SQUARED * driftCovariance
    }

    /**
     * The spec's `compute_client_time`: the instant on the local monotonic
     * clock at which the server clock reads [serverTimeMicros]. Clock mapping
     * only, with no playout terms, so it is the one to measure with.
     *
     * Solves `server = client + offset + drift * (client - lastUpdate)` for
     * the client time, as the reference does.
     *
     * Lock-free; safe to call from the audio thread.
     */
    fun computeClientTime(serverTimeMicros: Long): Long {
        val s = snapshot
        val drift = s.effectiveDrift
        return roundHalfAway((serverTimeMicros - s.offset + drift * s.lastUpdateTime) / (1.0 + drift))
    }

    /**
     * When to play audio stamped [serverTimeMicros]: [computeClientTime] plus
     * the user/server sync offset and minus the spec's output delay, so the
     * result is the local instant at which the audio sink should render the
     * corresponding samples.
     */
    fun serverToClient(serverTimeMicros: Long): Long =
        computeClientTime(serverTimeMicros) + syncOffsetMicros - outputDelayMicros

    /**
     * Inverse of [serverToClient]: the reference's `compute_server_time`
     * applied to the client time with the playout terms taken back out.
     * Lock-free.
     */
    fun clientToServer(clientTimeMicros: Long): Long {
        val s = snapshot
        val clientTime = clientTimeMicros - syncOffsetMicros + outputDelayMicros
        val offsetNow = s.offset + s.effectiveDrift * (clientTime - s.lastUpdateTime)
        return clientTime + roundHalfAway(offsetNow)
    }

    /** `std::round`: halfway cases away from zero ([roundToLong] takes them up). */
    private fun roundHalfAway(x: Double): Long = if (x < 0) -(-x).roundToLong() else x.roundToLong()
}
