package com.sendspindroid.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.sendspindroid.musicassistant.MusicAssistant
import com.sendspindroid.coordinator.TransportState
import home.theme.HomeThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The person's home theme (HW-65), fork-only. Same model as MA web and MA mobile (HW-64): MA's
 * `home_theme` provider keeps, per MA user, the *claim* home-monitoring's theme_sync pushes from
 * authentik and an in-app *choice*, which wins while its basis is the claim's current override.
 * Read whenever the MA API signs in and on every resume. Without an MA sign-in (no identity) the
 * picker in Settings is local to this device.
 *
 * Light/dark goes through AppCompat's night mode, so Compose, the XML dialogs and
 * `isSystemInDarkTheme()` all follow it; Automatic = the system's.
 */
object HomeThemeController {
    private const val TAG = "HomeTheme"
    private const val PREFS = "home_theme"
    private const val KEY_LAST = "last"
    const val FOLLOW = "follow"
    val MODES = listOf("automatic", "light", "dark")

    data class Claim(val theme: String, val mode: String, val override: String)
    data class Choice(val theme: String, val mode: String, val basis: String)
    data class ServerState(val claim: Claim?, val choice: Choice?)
    data class Effective(val theme: String, val mode: String, val fromApp: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefs: SharedPreferences? = null

    private val _server = MutableStateFlow<ServerState?>(null)

    /** MA's answer for the signed-in user; null without an MA sign-in or provider. */
    val server: StateFlow<ServerState?> = _server.asStateFlow()

    private val _effective = MutableStateFlow(Effective(HomeThemes.DEFAULT, "automatic", false))
    val effective: StateFlow<Effective> = _effective.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY_LAST, null)?.split("/")?.takeIf { it.size == 2 }?.let { (theme, mode) ->
            apply(Effective(HomeThemes.byId(theme).id, mode.takeIf { it in MODES } ?: "automatic", false))
        }
    }

    suspend fun refresh() {
        if (MusicAssistant.connectionState.value != TransportState.Ready) return
        MusicAssistant.getHomeTheme()
            .onSuccess { onServer(parse(it)) }
            .onFailure { Log.i(TAG, "home_theme/get failed, keeping the last theme: ${it.message}") }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** An in-app choice; a null [theme] = "Follow home theme". Local only without an MA sign-in. */
    fun choose(theme: String?, mode: String) {
        val server = _server.value
        if (server == null) {
            apply(Effective(theme ?: HomeThemes.DEFAULT, mode, theme != null))
            return
        }
        // Optimistic, so the tap shows at once; MA's answer settles it.
        onServer(server.copy(choice = theme?.let { Choice(it, mode, server.claim?.override ?: FOLLOW) }))
        scope.launch {
            MusicAssistant.chooseHomeTheme(theme, mode)
                .onSuccess { onServer(parse(it)) }
                .onFailure { Log.w(TAG, "home_theme/choose failed", it) }
        }
    }

    private fun onServer(state: ServerState) {
        _server.value = state
        apply(resolve(state))
    }

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

    /** The in-app choice while still current, else the claim, else the default. */
    fun resolve(state: ServerState): Effective {
        val known = { id: String -> HomeThemes.byId(id).id }
        val mode = { m: String -> m.takeIf { it in MODES } ?: "automatic" }
        val choice = state.choice?.takeIf { it.basis == (state.claim?.override ?: FOLLOW) }
        return choice?.let { Effective(known(it.theme), mode(it.mode), true) }
            ?: state.claim?.let { Effective(known(it.theme), mode(it.mode), false) }
            ?: Effective(HomeThemes.DEFAULT, "automatic", false)
    }

    fun parse(json: JsonObject): ServerState {
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        fun obj(key: String) = runCatching { json[key]?.jsonObject }.getOrNull()
        val claim = obj("claim")?.let { c ->
            c.str("theme")?.let { Claim(it, c.str("mode") ?: "automatic", c.str("override") ?: FOLLOW) }
        }
        val choice = obj("choice")?.let { c ->
            c.str("theme")?.let { Choice(it, c.str("mode") ?: "automatic", c.str("basis") ?: FOLLOW) }
        }
        return ServerState(claim, choice)
    }
}
