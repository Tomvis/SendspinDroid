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
 * Algorithm core matches upstream `Sendspin/time-filter` (Apache-2.0)
 * post-PR #6 (2026-04). Local adaptations are documented at their
 * respective sites in this file.
 */
class SendspinTimeFilter {

    companion object {
        // Algorithm and tunables match upstream Sendspin/time-filter (Apache-2.0)
        // post-PR #6 (2026-04). Local additions on top of upstream are documented
        // at their respective sites:
        //   - IQR outlier pre-rejection
        //   - +/-500 ppm hard drift cap
        //   - freeze/thaw with covariance inflation across reconnects

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

        // Local: IQR-based outlier pre-rejection ahead of the Kalman update.
        // Defends against heavy-tailed wifi/cellular RTT spikes that upstream
        // does not see in its testbed.
        private const val OUTLIER_WINDOW_SIZE = 10
        private const val OUTLIER_IQR_MULTIPLIER = 3.0
        private const val MIN_OUTLIER_MEASUREMENTS = 5

        // Local: hard cap on drift to keep prediction sane if a measurement
        // sequence transiently suggests an unphysical clock-rate difference.
        // 500 ppm is generous compared to typical phone crystals (10-50 ppm).
        private const val MAX_DRIFT = 5e-4

        // Readiness and convergence reporting.
        private const val MIN_MEASUREMENTS = 2
        private const val MIN_MEASUREMENTS_FOR_CONVERGENCE = 5
        private const val MAX_ERROR_FOR_CONVERGENCE_US = 10_000L

        private const val TAG = "SendspinTimeFilter"
    }

    // Guards the filter state below (addMeasurement, reset, freeze, thaw).
    // Nothing outside the lock reads that state; other threads read [snapshot].
    private val lock = Any()

    // State vector: [offset, drift]
    private var offset: Double = 0.0
    private var drift: Double = 0.0

    // Covariance matrix (2x2)
    private var p00: Double = Double.MAX_VALUE  // offset variance
    private var p01: Double = 0.0               // offset-drift covariance
    private var p10: Double = 0.0               // drift-offset covariance
    private var p11: Double = 0.0               // drift variance

    // Client time of the last accepted measurement
    private var lastUpdateTime: Long = 0
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
        val offsetVariance: Double = Double.MAX_VALUE,
        val measurementCount: Int = 0
    ) {
        /** The drift the conversions apply: zero until the estimate is significant. */
        val effectiveDrift: Double get() = if (useDrift) drift else 0.0
    }

    @Volatile private var snapshot = Snapshot()

    /** Publish the current filter state. Call with [lock] held, after every change. */
    private fun publish() {
        snapshot = Snapshot(offset, drift, useDrift, lastUpdateTime, p00, measurementCount)
    }

    // Outlier pre-rejection: tracks recent accepted offset measurements
    private val recentOffsets = DoubleArray(OUTLIER_WINDOW_SIZE)
    private var recentOffsetsIndex = 0
    private var recentOffsetsCount = 0
    private var rejectedCount = 0  // Consecutive rejections (for forced acceptance)

    // Static delay: the sync offset set by the user's slider or pushed by the
    // server. Output latency up to the DAC is not part of it - the player
    // schedules against AudioTrack timestamps, which already include it.
    // @Volatile: read by the audio thread (serverToClient), written from
    // UI/main threads.
    @Volatile private var userSyncOffsetMicros: Long = 0

    // Convergence tracking
    private var convergenceTimeMs: Long = 0L       // Time to reach isConverged
    private var firstMeasurementTimeMs: Long = 0L  // Timestamp of first measurement
    private var hasLoggedConvergence: Boolean = false

    // Frozen state for reconnection - preserves sync across network drops
    @Volatile private var frozenState: FrozenState? = null

    private data class FrozenState(
        val offset: Double,
        val drift: Double,
        val p00: Double,
        val p01: Double,
        val p10: Double,
        val p11: Double,
        val measurementCount: Int,
        val lastUpdateTime: Long,
        val recentOffsets: DoubleArray,
        val recentOffsetsIndex: Int,
        val recentOffsetsCount: Int,
        val serverName: String?,
        val serverId: String?
    )

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
        get() = snapshot.offsetVariance.let { if (it.isFinite() && it >= 0) sqrt(it).toLong() else Long.MAX_VALUE }

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
        get() = userSyncOffsetMicros / 1000.0

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
     * Write the user's manual sync-offset correction (milliseconds).
     * Called by the settings slider's broadcast path.
     */
    fun setUserSyncOffsetMs(ms: Double) {
        userSyncOffsetMicros = (ms * 1000).toLong()
    }

    /**
     * Write a server-pushed sync-offset (from `client/sync_offset`).
     * Goes into the same field as the user slider because both are
     * corrections to when this device should play.
     */
    fun setServerSyncOffsetMs(ms: Double) {
        userSyncOffsetMicros = (ms * 1000).toLong()
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
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 0.0
        lastUpdateTime = 0
        measurementCount = 0
        useDrift = false
        recentOffsetsIndex = 0
        recentOffsetsCount = 0
        rejectedCount = 0
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        publish()
    }

    /**
     * Whether the filter has frozen state that can be restored.
     */
    val isFrozen: Boolean
        get() = frozenState != null

    /**
     * Capture a snapshot of the current sync state so [thaw] can restore it
     * after a reconnect to the same server. No-op if the filter is not yet
     * [isReady].
     *
     * @param serverName Display name of the currently-connected server (from server/hello).
     * @param serverId   Stable identifier of the currently-connected server (from server/hello).
     */
    fun freeze(serverName: String?, serverId: String?) {
        synchronized(lock) {
            if (!isReady) return

            frozenState = FrozenState(
                offset = offset,
                drift = drift,
                p00 = p00,
                p01 = p01,
                p10 = p10,
                p11 = p11,
                measurementCount = measurementCount,
                lastUpdateTime = lastUpdateTime,
                recentOffsets = recentOffsets.copyOf(),
                recentOffsetsIndex = recentOffsetsIndex,
                recentOffsetsCount = recentOffsetsCount,
                serverName = serverName,
                serverId = serverId
            )
        }
    }

    /**
     * Restore a frozen sync state captured by [freeze] if and only if the
     * provided identity matches the one captured at freeze-time. On
     * identity mismatch the frozen snapshot is discarded.
     *
     * Call this after a reconnect handshake completes, before resuming time
     * sync.
     *
     * @param serverName Display name of the just-handshook server.
     * @param serverId   Stable identifier of the just-handshook server.
     * @return true if state was restored, false if no frozen state existed
     *         or the identity did not match.
     */
    fun thaw(serverName: String?, serverId: String?): Boolean {
        synchronized(lock) {
            val frozen = frozenState ?: return false

            if (frozen.serverName != serverName || frozen.serverId != serverId) {
                frozenState = null
                return false
            }

            offset = frozen.offset
            drift = frozen.drift

            p00 = frozen.p00 * 100.0
            p01 = frozen.p01 * 10.0
            p10 = frozen.p10 * 10.0
            p11 = frozen.p11 * 100.0

            measurementCount = MIN_MEASUREMENTS
            lastUpdateTime = frozen.lastUpdateTime

            frozen.recentOffsets.copyInto(recentOffsets)
            recentOffsetsIndex = frozen.recentOffsetsIndex
            recentOffsetsCount = frozen.recentOffsetsCount
            rejectedCount = 0
            useDrift = false

            hasLoggedConvergence = false
            convergenceTimeMs = 0
            firstMeasurementTimeMs = Platform.currentTimeMillis()
            publish()

            frozenState = null
            return true
        }
    }

    /**
     * Discard frozen state and perform full reset.
     * Call this when reconnection fails and we need to start fresh.
     * Thread-safe: synchronized to prevent concurrent mutation.
     */
    fun resetAndDiscard() = synchronized(lock) {
        frozenState = null
        offset = 0.0
        drift = 0.0
        p00 = Double.MAX_VALUE
        p01 = 0.0
        p10 = 0.0
        p11 = 0.0
        lastUpdateTime = 0
        measurementCount = 0
        useDrift = false
        recentOffsetsIndex = 0
        recentOffsetsCount = 0
        rejectedCount = 0
        convergenceTimeMs = 0
        firstMeasurementTimeMs = 0
        hasLoggedConvergence = false
        publish()
    }

    /**
     * Add a new time measurement to the filter.
     *
     * Includes outlier pre-rejection: measurements that deviate significantly from
     * recent history are rejected before reaching the Kalman filter, protecting
     * against cellular congestion spikes and handoff transients.
     *
     * Thread-safe: synchronized to prevent concurrent mutation of filter state.
     *
     * @param measurementOffset The measured offset in microseconds
     * @param maxError The maximum error (uncertainty) in microseconds
     * @param clientTimeMicros The client timestamp when measurement was taken
     * @return true if measurement was accepted, false if rejected as outlier
     */
    fun addMeasurement(
        measurementOffset: Long,
        maxError: Long,
        clientTimeMicros: Long
    ): Boolean = synchronized(lock) {
        if (measurementCount > 0 && clientTimeMicros <= lastUpdateTime) {
            return false
        }

        val measurement = measurementOffset.toDouble()
        val maxErrorD = maxError.toDouble().coerceAtLeast(1.0)
        val updateStdDev = maxErrorD * MAX_ERROR_SCALE
        val measurementVariance = updateStdDev * updateStdDev

        if (measurementCount == 0) {
            firstMeasurementTimeMs = Platform.currentTimeMillis()
        }

        when (measurementCount) {
            0 -> {
                offset = measurement
                p00 = measurementVariance
                lastUpdateTime = clientTimeMicros
                measurementCount = 1
                recordAcceptedOffset(measurement)
            }
            1 -> {
                val dt = (clientTimeMicros - lastUpdateTime).toDouble()
                drift = ((measurement - offset) / dt).coerceIn(-MAX_DRIFT, MAX_DRIFT)
                p11 = (p00 + measurementVariance) / (dt * dt)
                offset = measurement
                p00 = measurementVariance
                lastUpdateTime = clientTimeMicros
                measurementCount = 2
                recordAcceptedOffset(measurement)
            }
            else -> {
                if (!shouldAcceptMeasurement(measurement, maxErrorD)) {
                    rejectedCount++
                    return false
                }
                rejectedCount = 0

                kalmanUpdate(measurement, maxErrorD, clientTimeMicros)
                recordAcceptedOffset(measurement)
            }
        }
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

    /**
     * Determine if a measurement should be accepted or rejected as an outlier.
     *
     * Uses robust statistics (median + IQR) to detect measurements that are
     * far from the recent accepted history. This protects the Kalman filter
     * from being pulled by cellular congestion spikes (200ms+ outliers).
     *
     * Force-accepts after 3 consecutive rejections to handle genuine step changes
     * (e.g., network route change where ALL measurements shift).
     */
    private fun shouldAcceptMeasurement(measurement: Double, maxError: Double): Boolean {
        // Accept during early warmup - not enough history for outlier detection
        if (recentOffsetsCount < MIN_OUTLIER_MEASUREMENTS) return true

        // Force-accept after consecutive rejections (genuine step change)
        if (rejectedCount >= 3) return true

        val count = minOf(recentOffsetsCount, OUTLIER_WINDOW_SIZE)
        val sorted = DoubleArray(count)
        for (i in 0 until count) {
            sorted[i] = recentOffsets[(recentOffsetsIndex - count + i + OUTLIER_WINDOW_SIZE) % OUTLIER_WINDOW_SIZE]
        }
        sorted.sort()

        val median = if (count % 2 == 0) {
            (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
        } else {
            sorted[count / 2]
        }

        val q1 = sorted[count / 4]
        val q3 = sorted[(count * 3) / 4]
        val iqr = q3 - q1

        // Threshold: at least RTT-sized window (maxError), or IQR-based
        val threshold = maxOf(OUTLIER_IQR_MULTIPLIER * iqr, maxError)

        return abs(measurement - median) <= threshold
    }

    /**
     * Record an accepted offset measurement in the recent history window.
     */
    private fun recordAcceptedOffset(measurement: Double) {
        recentOffsets[recentOffsetsIndex] = measurement
        recentOffsetsIndex = (recentOffsetsIndex + 1) % OUTLIER_WINDOW_SIZE
        if (recentOffsetsCount < OUTLIER_WINDOW_SIZE) recentOffsetsCount++
    }

    private fun kalmanUpdate(measurement: Double, maxError: Double, clientTimeMicros: Long) {
        val dt = (clientTimeMicros - lastUpdateTime).toDouble()
        val dtSquared = dt * dt
        val updateStdDev = maxError * MAX_ERROR_SCALE
        val measurementVariance = updateStdDev * updateStdDev

        // Predict: x = F * x, P = F * P * F^T + Q with F = [[1, dt], [0, 1]]
        // and Q = diag(PROCESS_VARIANCE, DRIFT_PROCESS_VARIANCE) * dt.
        // The prediction always uses the drift estimate, significant or not:
        // the gain below is computed for that model, and it is the innovation
        // against it that corrects a wrong drift.
        val offsetPredicted = offset + drift * dt

        var p00New = p00 + 2 * p01 * dt + p11 * dtSquared + PROCESS_VARIANCE * dt
        var p01New = p01 + p11 * dt
        var p10New = p10 + p11 * dt
        var p11New = p11 + DRIFT_PROCESS_VARIANCE * dt

        val innovation = measurement - offsetPredicted

        // Adaptive forgetting: residuals exceeding FORGETTING_THRESHOLD * max_error
        // indicate a step change (route flip, server clock jump, etc). Inflate the
        // entire predicted covariance so the next gain step is large enough to
        // adopt the new measurement quickly. Gated by measurement count so a
        // handful of early outliers cannot wipe the model.
        if (measurementCount >= MIN_SAMPLES_FOR_FORGETTING &&
            abs(innovation) > FORGETTING_THRESHOLD * maxError
        ) {
            p00New *= FORGETTING_VARIANCE_FACTOR
            p01New *= FORGETTING_VARIANCE_FACTOR
            p10New *= FORGETTING_VARIANCE_FACTOR
            p11New *= FORGETTING_VARIANCE_FACTOR
        }

        // Update: K = P * H^T * S^-1, x = x + K * y, P = (I - K * H) * P
        // with H = [1, 0] and S = P[0,0] + R.
        val s = p00New + measurementVariance

        val k0 = p00New / s
        val k1 = p10New / s

        offset = offsetPredicted + k0 * innovation
        drift = (drift + k1 * innovation).coerceIn(-MAX_DRIFT, MAX_DRIFT)

        p00 = (1 - k0) * p00New
        p01 = (1 - k0) * p01New
        p10 = p10New - k1 * p00New
        p11 = p11New - k1 * p01New

        useDrift = drift * drift > DRIFT_SIGNIFICANCE_THRESHOLD_SQUARED * p11

        lastUpdateTime = clientTimeMicros
        measurementCount++
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
        return ((serverTimeMicros - s.offset + drift * s.lastUpdateTime) / (1.0 + drift)).roundToLong()
    }

    /**
     * When to play audio stamped [serverTimeMicros]: [computeClientTime] plus
     * the user/server sync offset and minus the spec's output delay, so the
     * result is the local instant at which the audio sink should render the
     * corresponding samples.
     */
    fun serverToClient(serverTimeMicros: Long): Long =
        computeClientTime(serverTimeMicros) + userSyncOffsetMicros - outputDelayMicros

    /**
     * Inverse of [serverToClient]: the reference's `compute_server_time`
     * applied to the client time with the playout terms taken back out.
     * Lock-free.
     */
    fun clientToServer(clientTimeMicros: Long): Long {
        val s = snapshot
        val clientTime = clientTimeMicros - userSyncOffsetMicros + outputDelayMicros
        val offsetNow = s.offset + s.effectiveDrift * (clientTime - s.lastUpdateTime)
        return clientTime + offsetNow.roundToLong()
    }
}
