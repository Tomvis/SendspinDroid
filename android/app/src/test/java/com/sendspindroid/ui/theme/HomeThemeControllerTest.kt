package com.sendspindroid.ui.theme

import com.sendspindroid.ui.theme.HomeThemeController.Effective
import home.theme.HomeThemes
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeThemeControllerTest {
    private val c = HomeThemeController

    @Test
    fun knownThemesAndModesAreKept() {
        assertEquals(Effective("dusk", "dark"), c.normalize("dusk", "dark"))
    }

    @Test
    fun unknownThemesAndModesFallBack() {
        assertEquals(Effective(HomeThemes.DEFAULT, "automatic"), c.normalize("nope", "sepia"))
        assertEquals(Effective(HomeThemes.DEFAULT, "automatic"), c.normalize(null, null))
    }

    @Test
    fun slateKeepsTheHw48Palette() {
        assertEquals(22, HomeThemes.all.size)
        assertEquals(0xFF466A77, HomeThemes.byId("slate").light.primary)
        assertEquals(0xFF8DB0BD, HomeThemes.byId("slate").dark.primary)
    }
}
