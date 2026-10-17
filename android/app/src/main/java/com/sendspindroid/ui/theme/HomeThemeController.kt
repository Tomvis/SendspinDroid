package com.sendspindroid.ui.theme

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import home.theme.HomeThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home theme (HW-65), fork-only, chosen in Settings and kept on this device. Upstream removed
 * the Music Assistant client (#302), so it no longer follows the MA user's theme (HW-85).
 *
 * Light/dark is upstream's Theme setting (UserSettings.themeMode, #323) since HW-114; [Effective.mode]
 * is only read once, by [takeLegacyMode], to carry an older fork choice over to it.
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
        // Before the guard: a test sandbox keeps this object across Application instances
        (context.applicationContext as? Application)?.let(HomeXmlTheme::register)
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
    }

    /**
     * A light/dark choice made in the fork's own picker before HW-114 ("light"/"dark"), handed
     * over once: the stored mode goes back to Automatic.
     */
    fun takeLegacyMode(): String? {
        val mode = _effective.value.mode.takeIf { it == "light" || it == "dark" } ?: return null
        apply(_effective.value.copy(mode = "automatic"))
        return mode
    }
}
