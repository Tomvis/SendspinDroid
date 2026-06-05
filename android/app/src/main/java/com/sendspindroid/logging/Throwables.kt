package com.sendspindroid.logging

/**
 * Bounded one-line summary of a [Throwable]: "SimpleName: first line of message"
 * (or just "SimpleName" when the message is blank).
 *
 * Use on hot failure paths (reconnect loops, audio write failures) where logging
 * the full Throwable would call android.util.Log.getStackTraceString(), allocating
 * the entire stack trace as a String on every failure. Under memory pressure that
 * allocation can itself tip the process into OutOfMemoryError. Keep full stack
 * traces only at low-frequency sites where they aid triage.
 *
 * @param maxChars maximum characters kept from the message's first line
 */
fun throwableSummary(t: Throwable, maxChars: Int = 180): String {
    val type = t.javaClass.simpleName.ifBlank { t.javaClass.name }
    val msg = t.message?.substringBefore('\n')?.take(maxChars).orEmpty()
    return if (msg.isBlank()) type else "$type: $msg"
}
