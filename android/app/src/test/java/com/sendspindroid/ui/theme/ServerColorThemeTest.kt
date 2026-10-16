package com.sendspindroid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import com.sendspindroid.sendspin.protocol.ColorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ServerColorTheme] puts the server's palette into the Material scheme for
 * what it wraps, blending into each change, and leaves the scheme alone
 * without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerColorThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val darkBackground = Color(0xFF6C291A)
    private val lightBackground = Color(0xFFE2A498)
    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = darkBackground.toArgb(),
        backgroundLight = lightBackground.toArgb(),
        onDark = 0xFFEED6AA.toInt(),
        onLight = 0xFF221E3E.toInt(),
    )

    private var base: ColorScheme? = null
    private var seen: ColorScheme? = null

    private fun show(colors: () -> ColorState?, dark: Boolean = true, blend: () -> Boolean = { true }) {
        composeTestRule.setContent {
            SendSpinTheme(darkTheme = dark, dynamicColor = false) {
                base = MaterialTheme.colorScheme
                ServerColorTheme(colors(), blend()) { seen = MaterialTheme.colorScheme }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `without a palette the Material scheme is kept`() {
        show({ null })

        assertEquals(base.toString(), seen.toString())
    }

    @Test
    fun `a palette already there when shown is used at once`() {
        show({ palette })

        assertEquals(darkBackground, seen!!.background)
        // Every role withServerColors sets, none left out of the theme
        assertEquals(base!!.withServerColors(palette, dark = true).toString(), seen.toString())
    }

    @Test
    fun `a new palette is blended into, and its clearing back out of`() {
        var colors by mutableStateOf<ColorState?>(null)
        show({ colors })
        val material = base!!.background

        // The clock is stepped by hand; waitForIdle recomposes for the
        // change without running the blend
        composeTestRule.mainClock.autoAdvance = false
        colors = palette
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(200)
        assertNotEquals(material, seen!!.background)
        assertNotEquals(darkBackground, seen!!.background)

        composeTestRule.mainClock.advanceTimeBy(1_000)
        assertEquals(darkBackground, seen!!.background)
        assertEquals(Color.White, seen!!.onBackground)

        colors = null
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        assertEquals(base.toString(), seen.toString())
    }

    @Test
    fun `without blending a change is shown at once, and a later blend starts from it`() {
        var colors by mutableStateOf<ColorState?>(palette)
        var blend by mutableStateOf(true)
        show({ colors }, blend = { blend })

        // The clock is stepped by hand; waitForIdle takes the change and the
        // frame after it draws
        composeTestRule.mainClock.autoAdvance = false
        colors = null
        blend = false
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        assertEquals(base.toString(), seen.toString())

        // Blending again soon after, with nothing new: no fade from what was
        // left behind
        composeTestRule.mainClock.advanceTimeBy(100)
        blend = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        assertEquals(base.toString(), seen.toString())
    }

    @Test
    fun `light mode uses background_light`() {
        show({ palette }, dark = false)

        assertEquals(lightBackground, seen!!.background)
        assertEquals(Color.Black, seen!!.onBackground)
    }

    @Test
    fun `a palette with no background for the mode leaves the Material scheme`() {
        show({ palette.copy(backgroundDark = null) })

        assertEquals(base.toString(), seen.toString())
    }
}
