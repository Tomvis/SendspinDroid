package com.sendspindroid.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * The app's recent process deaths, as the system recorded them, for the head of a
 * shared log. A process the system kills in the background writes nothing on its
 * way out, and the next one reconnects without a word, so this is the only trace
 * such a death leaves (#246).
 */
internal object ProcessExits {

    /** How many exits a shared log lists. */
    private const val MAX_EXITS = 5

    /** One [ApplicationExitInfo] as plain values, so [format] runs without the framework. */
    data class Exit(
        val timestampMs: Long,
        val reason: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
    )

    /** This package's most recent exits, newest first, timed in [dateFormat]. */
    fun describe(context: Context, dateFormat: DateFormat): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Recent exits: not recorded before Android 11"
        }
        val exits = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .map { Exit(it.timestamp, it.reason, it.importance, it.pss, it.rss, it.description) }
        } catch (_: Exception) {
            // The header must never cost the user their log.
            return "Recent exits: unavailable"
        }
        return format(exits, dateFormat)
    }

    fun format(exits: List<Exit>, dateFormat: DateFormat): String {
        if (exits.isEmpty()) return "Recent exits: none recorded"
        return buildString {
            append("Recent exits (newest first):")
            for (exit in exits) {
                append("\n  ${dateFormat.format(Date(exit.timestampMs))} ${reasonName(exit.reason)}")
                append(" importance=${exit.importance}")
                // Zero when the system had not sampled the process before it died.
                if (exit.pssKb > 0) append(" pss=${exit.pssKb}kB")
                if (exit.rssKb > 0) append(" rss=${exit.rssKb}kB")
                if (!exit.description.isNullOrEmpty()) append(": ${exit.description}")
            }
        }
    }

    /**
     * The name of an [ApplicationExitInfo] reason, or its number for one this build
     * does not know. The constants are copied in at compile time, so naming one newer
     * than the device is safe.
     */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER" // API 33
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE" // API 34
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED" // API 34
        else -> reason.toString()
    }
}
