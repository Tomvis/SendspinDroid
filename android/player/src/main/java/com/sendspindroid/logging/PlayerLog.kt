package com.sendspindroid.logging

import android.util.Log

/**
 * The level gate and the category loggers, without the file capture.
 *
 * The player logs through these. An app that wants the output on disk, as
 * SendSpinDroid's `AppLog` does, sets [level] and reads logcat itself.
 */
object PlayerLog {

    @Volatile
    var level: LogLevel = LogLevel.OFF

    val Audio: Logger = Logger(LogCategory.Audio)
    val Sync: Logger = Logger(LogCategory.Sync)
    val Protocol: Logger = Logger(LogCategory.Protocol)
    val Network: Logger = Logger(LogCategory.Network)
    val Playback: Logger = Logger(LogCategory.Playback)
    val UI: Logger = Logger(LogCategory.UI)
    val App: Logger = Logger(LogCategory.App)
}

/**
 * Per-category logger. One instance exists as a property on [PlayerLog] per [LogCategory].
 * All methods are gate-checked against [PlayerLog.level] before delegating to [android.util.Log].
 */
class Logger internal constructor(private val category: LogCategory) {

    fun v(msg: String) {
        if (PlayerLog.level.permits(LogLevel.VERBOSE)) Log.v(category.tag, msg)
    }

    fun d(msg: String) {
        if (PlayerLog.level.permits(LogLevel.DEBUG)) Log.d(category.tag, msg)
    }

    fun i(msg: String) {
        if (PlayerLog.level.permits(LogLevel.INFO)) Log.i(category.tag, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (PlayerLog.level.permits(LogLevel.WARN)) {
            if (t != null) Log.w(category.tag, msg, t) else Log.w(category.tag, msg)
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (PlayerLog.level.permits(LogLevel.ERROR)) {
            if (t != null) Log.e(category.tag, msg, t) else Log.e(category.tag, msg)
        }
    }
}
