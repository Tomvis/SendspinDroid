package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Sizes the `min_buffer_ms` we report from how late audio chunks arrive
 * (roles/player/v1.md, "Measuring timing parameters").
 *
 * A chunk's delay is its arrival time minus the local time at which the
 * server sent it. The value reported is the floor plus the worst delay that
 * came back in at least two of the ten-second slots of the last five
 * minutes, in steps of [STEP_MS]: "the upper tail of the distribution,
 * measured over a window long enough to include intermittent interference".
 * One late chunk on its own is the "transient fluctuation" the spec asks
 * players not to report; it costs every player in the group latency on
 * every start for as long as it is reported.
 *
 * Chunks sent or received faster than they play are not samples. That is
 * the buffer being filled after a start or a skip, when thirty seconds of
 * audio come at once and the delay measures how fast we can take them in:
 * the "burst conditions that do not represent steady-state delay".
 *
 * A delay that recurs raises the value as soon as it has, and it comes back
 * down only when no slot in the window still holds it, a full window after
 * it last occurred: a delay that recurs every few minutes is still covered
 * when it next comes. It never changes more than once per
 * [MIN_CHANGE_INTERVAL_MS].
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

        // Raise once a delay has come back in another slot (the second-largest
        // slot); keep it while any slot in the window still holds it, so it
        // lasts a full window after the latest occurrence. Rounding down is
        // covered by the margin in the floor. One pass rather than a sort, as
        // this runs for every chunk on the receive thread.
        var largest = 0
        var secondLargest = 0
        for (d in worstDelayMs) {
            if (d > largest) {
                secondLargest = largest
                largest = d
            } else if (d > secondLargest) {
                secondLargest = d
            }
        }
        val reportedDelayMs = minBufferMs - floorMs
        val heldDelayMs = if (largest / STEP_MS * STEP_MS >= reportedDelayMs) reportedDelayMs else 0
        val target = floorMs + maxOf(secondLargest / STEP_MS * STEP_MS, heldDelayMs)
        if (target == minBufferMs || nowMs - lastChangeMs < MIN_CHANGE_INTERVAL_MS) return false
        minBufferMs = target
        lastChangeMs = nowMs
        return true
    }
}
