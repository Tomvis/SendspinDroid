package com.sendspindroid.logging

import android.content.Context
import java.io.File

/**
 * Fork (HW-301): where the on-device logs live.
 *
 * The app's external files dir (`/sdcard/Android/data/<pkg>/files/logs`), so
 * `adb pull` can read them on a release build: a TV has no share target for
 * Export Debug Logs, and `run-as` needs a debuggable build. Falls back to the
 * cache dir when external storage is unavailable.
 */
internal object LogsDir {

    private const val NAME = "logs"

    /** Logs left in the old cache location are kept here, untouched by rotation. */
    internal const val LEGACY_SUBDIR = "from-cache"

    fun resolve(context: Context): File {
        val legacy = File(context.cacheDir, NAME)
        val external = runCatching { context.getExternalFilesDir(NAME) }.getOrNull()
            ?: return legacy
        migrate(legacy, external)
        return external
    }

    /** Moves `sendspin-log-*.txt` from [from] into [to]/[LEGACY_SUBDIR]. Never throws. */
    internal fun migrate(from: File, to: File) {
        try {
            val files = from.listFiles { f -> f.isFile && f.name.startsWith("sendspin-log-") }
            if (files.isNullOrEmpty()) return
            val dest = File(to, LEGACY_SUBDIR).apply { mkdirs() }
            for (f in files) {
                val target = File(dest, f.name)
                if (!target.exists()) f.copyTo(target)
                f.delete()
            }
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }
}
