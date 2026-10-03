package com.sendspindroid.ui.theme

import com.sendspindroid.ui.theme.HomeThemeController.Choice
import com.sendspindroid.ui.theme.HomeThemeController.Claim
import com.sendspindroid.ui.theme.HomeThemeController.Effective
import com.sendspindroid.ui.theme.HomeThemeController.ServerState
import home.theme.HomeThemes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** HW-65: same precedence as MA web/mobile (HW-64). */
class HomeThemeControllerTest {
    private val c = HomeThemeController

    @Test
    fun nothingStoredIsTheDefaultFollowingTheDevice() {
        assertEquals(Effective(HomeThemes.DEFAULT, "automatic", false), c.resolve(ServerState(null, null)))
    }

    @Test
    fun theClaimAppliesWithoutAnInAppChoice() {
        assertEquals(Effective("dusk", "dark", false), c.resolve(ServerState(Claim("dusk", "dark", "follow"), null)))
    }

    @Test
    fun anInAppChoiceWinsWhileItsBasisIsTheCurrentOverride() {
        val state = ServerState(Claim("dusk", "dark", "follow"), Choice("mint", "light", "follow"))
        assertEquals(Effective("mint", "light", true), c.resolve(state))
    }

    @Test
    fun aNewPerAppOverrideBeatsAnOlderInAppChoice() {
        val state = ServerState(Claim("ochre", "dark", "ochre/dark"), Choice("mint", "light", "follow"))
        assertEquals(Effective("ochre", "dark", false), c.resolve(state))
    }

    @Test
    fun unknownThemesAndModesFallBack() {
        val state = ServerState(Claim("nope", "sepia", "follow"), null)
        assertEquals(Effective(HomeThemes.DEFAULT, "automatic", false), c.resolve(state))
    }

    @Test
    fun theServerPayloadParses() {
        val json = Json.parseToJsonElement(
            """{"user_id":"u1","claim":{"theme":"klein","mode":"light","override":"follow"},"choice":null}""",
        ).jsonObject
        assertEquals(ServerState(Claim("klein", "light", "follow"), null), c.parse(json))
    }

    @Test
    fun slateKeepsTheHw48Palette() {
        assertEquals(22, HomeThemes.all.size)
        assertEquals(0xFF466A77, HomeThemes.byId("slate").light.primary)
        assertEquals(0xFF8DB0BD, HomeThemes.byId("slate").dark.primary)
    }
}
