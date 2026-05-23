package com.sendspindroid.shared.log

/**
 * JVM `actual` for [Log] used by the conformance-client.
 *
 * Routes through `java.util.logging` (already on the classpath) so the
 * harness or operator can redirect via standard JUL configuration. Returned
 * Int matches the Android Log API contract (irrelevant on JVM, kept for
 * source-level compatibility).
 */
actual object Log {
    private val logger = java.util.logging.Logger.getLogger("SendSpin")

    actual fun v(tag: String, msg: String): Int = log(java.util.logging.Level.FINEST, tag, msg)
    actual fun d(tag: String, msg: String): Int = log(java.util.logging.Level.FINE, tag, msg)
    actual fun i(tag: String, msg: String): Int = log(java.util.logging.Level.INFO, tag, msg)
    actual fun w(tag: String, msg: String): Int = log(java.util.logging.Level.WARNING, tag, msg)
    actual fun w(tag: String, msg: String, tr: Throwable): Int =
        log(java.util.logging.Level.WARNING, tag, msg, tr)
    actual fun e(tag: String, msg: String): Int = log(java.util.logging.Level.SEVERE, tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable): Int =
        log(java.util.logging.Level.SEVERE, tag, msg, tr)

    private fun log(
        level: java.util.logging.Level,
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ): Int {
        if (tr == null) logger.log(level, "[$tag] $msg")
        else logger.log(level, "[$tag] $msg", tr)
        return 0
    }
}
