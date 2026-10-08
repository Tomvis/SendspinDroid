package com.sendspindroid

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import com.google.android.material.color.DynamicColors
import com.sendspindroid.diagnostics.Telemetry
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.CrashHandler

/**
 * Preferences file of the removed Music Assistant API client. It held that
 * client's access tokens (one per saved server), its API port and its selected
 * player ids. Nothing reads any of them any more.
 */
internal const val LEGACY_MA_PREFS_FILE = "ma_settings"

/**
 * Deletes [LEGACY_MA_PREFS_FILE] so its tokens do not stay on the device.
 * Runs on every start rather than once: deleting a file that is not there is
 * a no-op, and Auto Backup can restore the file onto a new install.
 */
internal fun deleteLegacyMusicAssistantPrefs(context: Context) {
    context.deleteSharedPreferences(LEGACY_MA_PREFS_FILE)
}

class SendSpinApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        deleteLegacyMusicAssistantPrefs(this)
        // Fork (HW-65): last home theme + light/dark before the first activity draws.
        com.sendspindroid.ui.theme.HomeThemeController.initialize(this)
        // Initialize logging and install crash capture as early as possible, so
        // startup-path issues are captured and an unexpected exit can be reported
        // on the next launch. Runs the one-time log-level migration too.
        AppLog.init(this)
        CrashHandler.install(this)
        // Opt-in telemetry (off by default). init() also flushes any queued
        // handoff episodes from a previous run over an unmetered connection.
        Telemetry.init(this)
        // On Android 12+, applies wallpaper-derived colors to all activities.
        // On older devices, falls back to the static theme colors.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    /**
     * Provides the app-wide singleton ImageLoader.
     */
    override fun newImageLoader(): ImageLoader {
        val builder = ImageLoader.Builder(this)

        if (UserSettings.lowMemoryMode) {
            // Disable all caching to minimize memory footprint
            builder
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
        } else {
            builder.crossfade(true)
        }

        return builder.build()
    }
}
