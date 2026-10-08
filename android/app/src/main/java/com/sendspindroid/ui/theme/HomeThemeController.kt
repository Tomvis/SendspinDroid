package com.sendspindroid.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import home.theme.HomeThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home theme (HW-65), fork-only, chosen in Settings and kept on this device. Upstream removed
 * the Music Assistant client (#302), so it no longer follows the MA user's theme (HW-85).
 *
 * Light/dark goes through AppCompat's night mode, so Compose, the XML dialogs and
 * `isSystemInDarkTheme()` all follow it; Automatic = the system's.
 */
object HomeThemeController {
    private const val PREFS = "home_theme"
    private const val KEY_LAST = "last"
    val MODES = listOf("automatic", "light", "dark")

    data class Effective(val theme: String, val mode: String)

    private var prefs: SharedPreferences? = null

    private val _effective = MutableStateFlow(Effective(HomeThemes.DEFAULT, "automatic"))
    val effective: StateFlow<Effective> = _effective.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY_LAST, null)?.split("/")?.takeIf { it.size == 2 }?.let { (theme, mode) ->
            apply(normalize(theme, mode))
        }
    }

    fun choose(theme: String, mode: String) = apply(normalize(theme, mode))

    /** Unknown themes and modes fall back to the default and Automatic. */
    fun normalize(theme: String?, mode: String?) =
        Effective(HomeThemes.byId(theme).id, mode?.takeIf { it in MODES } ?: "automatic")

    private fun apply(effective: Effective) {
        _effective.value = effective
        prefs?.edit()?.putString(KEY_LAST, "${effective.theme}/${effective.mode}")?.apply()
        val night = when (effective.mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(night)
    }
}
