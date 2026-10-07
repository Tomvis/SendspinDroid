package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Sizes the `min_buffer_ms` we report from how late audio chunks arrive
 * (roles/player/v1.md, "Measuring timing parameters").
 *
 * A chunk's delay is its arrival time minus the local time at which the
 * server sent it. The value reported is the floor plus the worst delay seen
 * in the last five minutes, in steps of [STEP_MS]: "the upper tail of the
 * distribution, measured over a window long enough to include intermittent
 * interference".
 *
 * Chunks sent or received faster than they play are not samples. That is
 * the buffer being filled after a start or a skip, when thirty seconds of
 * audio come at once and the delay measures how fast we can take them in:
 * the "burst conditions that do not represent steady-state delay".
 *
 * A late chunk raises the value straight away, because waiting would risk
 * the underrun the value exists to prevent. It comes back down only when
 * that chunk has left the window, which is the debounce the spec asks for,
 * and it never changes more than once per [MIN_CHANGE_INTERVAL_MS].
 *
 * [addChunk] is called from the receive thread only; [minBufferMs] may be
 * read from any thread.
 *
 * @param floorMs What the player needs in hand with no network delay at all,
 *   with at least [STEP_MS] of margin included.
 */
class MinBufferEstimator(
    private val floorMs: Int = SendSpinProtocol.PlayerTiming.MIN_BUFFER_MS
) {
    companion object {
        // The window is BUCKETS slots of BUCKET_MS, each holding the worst
        // delay seen in it, so nothing is stored per chunk.
        private const val BUCKET_MS = 10_000L
        private const val BUCKETS = 30

        private const val STEP_MS = 50
        private const val MIN_CHANGE_INTERVAL_MS = 1_000L
    }

    private val worstDelayMs = IntArray(BUCKETS)
    private var newestBucket = 0L

    private var lastTimestampMicros = 0L
    private var lastSentMicros = 0L
    private var lastArrivalMicros = 0L
    private var lastChangeMs = 0L

    /** The `min_buffer_ms` to report. */
    @Volatile
    var minBufferMs: Int = floorMs
        private set

    /**
     * Record the arrival of one audio chunk.
     *
     * @param timestampMicros The chunk's playback timestamp (server clock).
     * @param sentMicros When the server sent it, `timestamp - send_ahead`,
     *   converted to the local monotonic clock.
     * @param arrivalMicros When it arrived, on the local monotonic clock.
     * @return true if [minBufferMs] changed.
     */
    fun addChunk(timestampMicros: Long, sentMicros: Long, arrivalMicros: Long): Boolean {
        // Audio is arriving in a burst when it was sent, or is being
        // received, in less than half the time it takes to play.
        val halfPlayTime = (timestampMicros - lastTimestampMicros) / 2
        val burst = sentMicros - lastSentMicros < halfPlayTime ||
            arrivalMicros - lastArrivalMicros < halfPlayTime
        lastTimestampMicros = timestampMicros
        lastSentMicros = sentMicros
        lastArrivalMicros = arrivalMicros
        if (burst) return false

        val nowMs = arrivalMicros / 1000
        val bucket = nowMs / BUCKET_MS
        // Empty the slots of every bucket that started since the last sample.
        for (b in maxOf(newestBucket + 1, bucket - BUCKETS + 1)..bucket) {
            worstDelayMs[(b % BUCKETS).toInt()] = 0
        }
        newestBucket = bucket

        // Clock error can make the delay slightly negative: no delay.
        val slot = (bucket % BUCKETS).toInt()
        val delayMs = ((arrivalMicros - sentMicros) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (delayMs > worstDelayMs[slot]) worstDelayMs[slot] = delayMs

        // Rounding the delay down is covered by the margin in the floor.
        val target = floorMs + worstDelayMs.max() / STEP_MS * STEP_MS
        if (target == minBufferMs || nowMs - lastChangeMs < MIN_CHANGE_INTERVAL_MS) return false
        minBufferMs = target
        lastChangeMs = nowMs
        return true
    }
}
