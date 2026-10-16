package com.sendspindroid.logging

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Public facade for on-device logging.
 *
 * Call sites use category-scoped loggers:
 * ```
 * AppLog.Audio.d("chunk queued")
 * AppLog.Protocol.w("bad message type")
 * AppLog.Network.e("dropped", exception)
 * ```
 *
 * Level is global ([level]), set by the user in Settings. Gating happens in [Logger.x] before the
 * `android.util.Log` call. The [LogcatBridge] is the sole writer to the file system; facade calls
 * do not touch disk directly.
 */
object AppLog {

    private const val PREF_LOG_LEVEL = "log_level"
    private const val PREF_LEGACY_DEBUG_LOGGING = "debug_logging_enabled"
    private const val PREF_LIGHTWEIGHT_MIGRATED = "lightweight_logging_migrated"

    /** Default for fresh installs / the one-time lightweight migration. */
    private val DEFAULT_LEVEL = LogLevel.WARN

    /** Held by [PlayerLog], so the player library's loggers are gated by it too. */
    val level: LogLevel
        get() = PlayerLog.level

    @Volatile
    internal var writer: LogFileWriter? = null
        private set

    // Bridge is wired in Task 6.
    @Volatile
    internal var bridge: LogcatBridge? = null
        private set

    @Volatile
    private var appContext: Context? = null

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val Audio: Logger = PlayerLog.Audio
    val Sync: Logger = PlayerLog.Sync
    val Protocol: Logger = PlayerLog.Protocol
    val Network: Logger = PlayerLog.Network
    val Playback: Logger = PlayerLog.Playback
    val UI: Logger = PlayerLog.UI
    val App: Logger = PlayerLog.App

    /** Session markers for connect/disconnect events. */
    object session {
        fun start(serverName: String, serverAddress: String) {
            App.i("Session started: $serverName ($serverAddress)")
        }

        fun end() {
            App.i("Session ended")
        }
    }

    /**
     * Initialize the logger. Call once at app startup from [com.sendspindroid.MainActivity].
     *
     * Creates the log directory + active file, runs the one-time preference migration from the
     * legacy `debug_logging_enabled` boolean to the new `log_level` string, and applies the
     * resulting level. Bridge startup is handled in [setLevel].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val logsDir = File(context.cacheDir, "logs")
        val w = LogFileWriter(logsDir)
        w.init()
        writer = w

        // Also clean up the legacy single-file location if present.
        File(context.cacheDir, "debug.log").takeIf { it.exists() }?.delete()

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(PREF_LOG_LEVEL, null)
        var resolved: LogLevel = when {
            stored != null -> runCatching { LogLevel.valueOf(stored) }.getOrDefault(LogLevel.OFF)
            prefs.contains(PREF_LEGACY_DEBUG_LOGGING) -> {
                if (prefs.getBoolean(PREF_LEGACY_DEBUG_LOGGING, false)) LogLevel.DEBUG else LogLevel.OFF
            }
            else -> LogLevel.OFF
        }
        // One-time migration to lightweight always-on logging: installs still sitting
        // on the old OFF default are bumped to WARN once, so a first-occurrence bug
        // report isn't empty. A user who deliberately picks OFF afterward is respected
        // -- this runs a single time, guarded by PREF_LIGHTWEIGHT_MIGRATED.
        if (!prefs.getBoolean(PREF_LIGHTWEIGHT_MIGRATED, false) && resolved == LogLevel.OFF) {
            resolved = DEFAULT_LEVEL
        }
        // Persist & strip legacy key regardless of which branch we took, so future runs skip this block.
        prefs.edit()
            .putString(PREF_LOG_LEVEL, resolved.name)
            .remove(PREF_LEGACY_DEBUG_LOGGING)
            .putBoolean(PREF_LIGHTWEIGHT_MIGRATED, true)
            .apply()

        bridge?.stop()
        bridge = LogcatBridge(w, bridgeScope)
        setLevel(resolved)
    }

    /**
     * Change the global log level. Persists to preferences and transitions the [LogcatBridge]:
     * - OFF -> X: start(X)
     * - X -> OFF: stop()
     * - X -> Y (both non-OFF): setLevel(Y) which internally does stop() + start(Y)
     * - OFF -> OFF: no-op
     */
    fun setLevel(newLevel: LogLevel) {
        val previous = level
        PlayerLog.level = newLevel
        appContext?.let { ctx ->
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(PREF_LOG_LEVEL, newLevel.name)
                .apply()
        }
        bridge?.let { br ->
            if (previous == LogLevel.OFF && newLevel != LogLevel.OFF) br.start(newLevel)
            else if (newLevel == LogLevel.OFF && previous != LogLevel.OFF) br.stop()
            else if (newLevel != LogLevel.OFF) br.setLevel(newLevel)
        }
        if (newLevel != LogLevel.OFF) {
            App.i("Log level set to ${newLevel.name}")
        }
    }

    /** For Settings UI. Returns the total size (KB) and file count across rotated files. */
    fun logFileStats(): Pair<Long, Int> {
        val w = writer ?: return 0L to 0
        val files = w.currentFiles()
        val totalBytes = files.sumOf { it.length() }
        return (totalBytes / 1024L) to files.size
    }

    /**
     * Create a share intent with a concatenated, **redacted** log file.
     *
     * [sensitiveTerms] are literal strings (saved/discovered server names, LAN
     * addresses, proxy URLs) the caller knows are sensitive but that aren't
     * pattern-matchable; they're scrubbed alongside the built-in IP/URL/token
     * patterns. See [RedactionFilter].
     *
     * [prepend] is an optional block (e.g. a diagnostic snapshot) written above
     * the logs and redacted with the same filter.
     */
    fun shareIntent(
        context: Context,
        sensitiveTerms: Collection<String> = emptyList(),
        prepend: String = "",
    ): Intent? =
        writer?.shareIntent(context, RedactionFilter(sensitiveTerms), prepend)

    /** Clear all rotated log files. */
    fun clear() {
        writer?.clear()
    }

    /**
     * Synchronously append a crash record to the active log file. Used by
     * [CrashHandler] on the dying process, so it bypasses the async [LogcatBridge]
     * and the level gate to guarantee the trace is on disk before the process exits.
     */
    fun recordCrash(record: String) {
        writer?.appendRaw("\n=== CRASH ===\n$record\n")
    }
}
