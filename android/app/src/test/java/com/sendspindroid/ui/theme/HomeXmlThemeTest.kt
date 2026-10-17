package com.sendspindroid.ui.theme

import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import home.theme.HomeThemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HW-125: activities take the person's home theme on the XML side too (dialogs, snackbars, the stats
 * sheet), through the vendored ThemeOverlay.Home.<id> in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeXmlThemeTest {

    @After
    fun tearDown() {
        HomeThemeController.choose(HomeThemes.DEFAULT, "automatic")
    }

    private fun primaryOf(theme: String): Int {
        HomeThemeController.choose(theme, "automatic")
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        val value = TypedValue()
        activity.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)
        return value.data
    }

    @Test
    fun `every home theme has an overlay`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        HomeThemes.all.forEach { assertNotEquals(it.id, 0, HomeXmlTheme.overlayFor(activity, it.id)) }
    }

    @Test
    fun `a new activity takes the chosen theme in light mode`() {
        assertEquals(HomeThemes.byId("lime").light.primary.toInt(), primaryOf("lime"))
        assertEquals(HomeThemes.byId("ink").light.primary.toInt(), primaryOf("ink"))
    }

    @Test
    @Config(qualifiers = "night")
    fun `a new activity takes the chosen theme in dark mode`() {
        assertEquals(HomeThemes.byId("lime").dark.primary.toInt(), primaryOf("lime"))
    }

    @Test
    fun `an activity is recreated when it comes back under another theme`() {
        val controller = Robolectric.buildActivity(RecreateCounter::class.java).setup()

        controller.pause().resume()
        assertEquals(0, controller.get().recreates)

        HomeThemeController.choose("lime", "automatic") // as the Settings picker does
        controller.pause().resume()
        assertEquals(1, controller.get().recreates)
    }

    class RecreateCounter : AppCompatActivity() {
        var recreates = 0
        override fun recreate() {
            recreates++
        }
    }
}
