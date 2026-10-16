package com.sendspindroid.shared.platform

import android.os.Build
import android.os.SystemClock

actual object Platform {
    actual fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    // CLOCK_BOOTTIME goes on through deep sleep and CLOCK_MONOTONIC stops.
    // The monotonic read is bracketed so that a preemption between the reads
    // cannot pass for sleep: the tightest bracket of three is used.
    actual fun suspendedTimeMicros(): Long {
        var best = Long.MAX_VALUE
        var value = 0L
        repeat(3) {
            val before = SystemClock.elapsedRealtimeNanos()
            val monotonic = System.nanoTime()
            val after = SystemClock.elapsedRealtimeNanos()
            if (after - before < best) {
                best = after - before
                value = (before + (after - before) / 2 - monotonic) / 1000
            }
            if (best < 100_000L) return value
        }
        return value
    }
    actual fun base64Decode(input: String): ByteArray =
        java.util.Base64.getDecoder().decode(input)
    actual fun manufacturer(): String = Build.MANUFACTURER
}
