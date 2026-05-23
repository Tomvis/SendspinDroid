package com.sendspindroid.shared.platform

/**
 * JVM `actual` for [Platform] used by the conformance-client.
 *
 * `elapsedRealtimeMs` returns `System.nanoTime() / 1_000_000` so it is a
 * monotonic clock (matches the Android `SystemClock.elapsedRealtime` contract:
 * the value increases even while the system is asleep, modulo the absence
 * of system-sleep on a server JVM).
 */
actual object Platform {
    actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun base64Decode(input: String): ByteArray =
        java.util.Base64.getDecoder().decode(input)

    actual fun manufacturer(): String = "Conformance"
}
