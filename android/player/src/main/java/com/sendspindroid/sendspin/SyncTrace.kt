package com.sendspindroid.sendspin

import kotlin.math.abs

/**
 * Fork (HW-301): the last [capacity] sync-error readings, kept in memory and
 * written to the log only when the DAC timestamps misbehave, so an episode's
 * log shows what the player measured going into it. Audio thread only; a
 * reading allocates nothing.
 *
 * Per row: t = time before the newest reading; pos/age = the AudioTimestamp
 * (frame position, and how old it was when read); pend = frames written but
 * not yet at the DAC; jit = how far the timestamp's position moved beyond its
 * own clock since the previous distinct timestamp (0 for a sane track);
 * err/sm = raw and smoothed sync error; soft = soft-correction schedule
 * (+1 drop, -1 insert); drop/ins = cumulative frames dropped/inserted;
 * os = one-shot resync applied at this reading (+drop, -insert frames).
 */
class SyncTrace(private val capacity: Int = 500) {
    private val atUs = LongArray(capacity)
    private val tsPosition = LongArray(capacity)
    private val tsNanoTime = LongArray(capacity)
    private val written = LongArray(capacity)
    private val errorUs = LongArray(capacity)
    private val smoothedUs = LongArray(capacity)
    private val soft = IntArray(capacity)
    private val dropped = LongArray(capacity)
    private val inserted = LongArray(capacity)
    private val oneShot = LongArray(capacity)
    private var next = 0
    private var size = 0

    fun record(
        atUs: Long, pos: Long, tsNanoTime: Long, written: Long, errorUs: Long, smoothedUs: Long,
        soft: Int, dropped: Long, inserted: Long,
    ) {
        val i = next
        this.atUs[i] = atUs
        tsPosition[i] = pos
        this.tsNanoTime[i] = tsNanoTime
        this.written[i] = written
        this.errorUs[i] = errorUs
        this.smoothedUs[i] = smoothedUs
        this.soft[i] = soft
        this.dropped[i] = dropped
        this.inserted[i] = inserted
        oneShot[i] = 0L
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** The newest reading got a one-shot resync: + frames dropped, - inserted. */
    fun markOneShot(frames: Long) {
        if (size > 0) oneShot[(next - 1 + capacity) % capacity] = frames
    }

    fun dump(reason: String, sampleRate: Int, log: (String) -> Unit) {
        if (size == 0) return
        val first = (next - size + capacity) % capacity
        val newestUs = atUs[(next - 1 + capacity) % capacity]
        log("Sync trace [$reason]: $size readings")
        var prev = -1
        for (k in 0 until size) {
            val i = (first + k) % capacity
            // Against the previous distinct timestamp; position wraps at 32 bits
            var jitUs = 0L
            if (prev >= 0 && tsNanoTime[i] != tsNanoTime[prev]) {
                val dPos = (tsPosition[i] - tsPosition[prev]).toInt().toLong()
                jitUs = dPos * 1_000_000L / sampleRate - (tsNanoTime[i] - tsNanoTime[prev]) / 1000
            }
            if (prev < 0 || tsNanoTime[i] != tsNanoTime[prev]) prev = i
            val tMs = (atUs[i] - newestUs) / 100 / 10.0
            log(
                "Sync trace: t=${tMs}ms pos=${tsPosition[i]} age=${atUs[i] - tsNanoTime[i] / 1000} " +
                    "pend=${(written[i] - tsPosition[i]).toInt()} jit=${signed(jitUs)} " +
                    "err=${signed(errorUs[i])} sm=${signed(smoothedUs[i])} soft=${soft[i]} " +
                    "drop=${dropped[i]} ins=${inserted[i]} os=${signed(oneShot[i])}"
            )
        }
    }

    private fun signed(v: Long) = if (v >= 0) "+$v" else "-${abs(v)}"
}
