package com.sendspindroid.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.ui.main.components.VolumeSlider
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.theme.ServerColorTheme
import com.sendspindroid.ui.theme.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The volume row's label and icons, as drawn on the background.
 *
 * roles/color/v1.md guarantees `on_dark` "4.5:1 with `background_dark`", and
 * `on_light` the same with `background_light`: only opaque, so on a server
 * palette the row draws its on color opaque. On the Material colors it stays
 * dimmed as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VolumeSliderServerColorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Music Assistant's palette for a dark cover: no candidate reaches its
     * preferred 7:1, so on_dark is at the 4.5:1 floor on a black background.
     */
    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = 0xFF000000.toInt(),
        backgroundLight = 0xFFB8BABE.toInt(),
        onDark = 0xFF72747E.toInt(),
        onLight = 0xFF23283C.toInt(),
    )

    private lateinit var scheme: ColorScheme

    private fun show(colors: ColorState?, dark: Boolean) {
        composeTestRule.setContent {
            SendSpinTheme(darkTheme = dark, dynamicColor = false) {
                ServerColorTheme(colors) {
                    scheme = MaterialTheme.colorScheme
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        VolumeSlider(volume = 0.5f, onVolumeChange = {})
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** The label's color as laid out, alpha included. */
    private fun labelColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText("Device Volume")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    /**
     * The volume-down icon as drawn: the pixel of the band it alone takes,
     * after the row's 8dp padding, that stands out most from the background.
     */
    private fun iconPixel(): Color {
        val screen = composeTestRule.onRoot().captureToImage().toPixelMap()
        val band = with(composeTestRule.density) { 8.dp.roundToPx() until 28.dp.roundToPx() }
        return band.flatMap { x -> (0 until screen.height).map { y -> screen[x, y] } }
            .maxBy { contrast(it, scheme.background) }
    }

    private fun assertReadable(dark: Boolean) {
        show(palette, dark)
        val mode = if (dark) "dark" else "light"

        val label = contrast(labelColor().compositeOver(scheme.background), scheme.background)
        assertTrue("label is $label in $mode", label >= 4.5)
        val icon = contrast(iconPixel(), scheme.background)
        assertTrue("icon is $icon in $mode", icon >= 4.5)
    }

    @Test
    fun `on a server palette the label and icons keep 4_5 to 1 in dark mode`() = assertReadable(dark = true)

    @Test
    fun `on a server palette the label and icons keep 4_5 to 1 in light mode`() = assertReadable(dark = false)

    @Test
    fun `on the Material colors the label and icons stay dimmed`() {
        show(null, dark = true)
        val dimmed = scheme.onSurfaceVariant.copy(alpha = 0.7f)

        assertEquals(dimmed, labelColor())
        val expected = dimmed.compositeOver(scheme.background)
        val icon = iconPixel()
        assertTrue(
            "icon is $icon, not $expected",
            abs(icon.red - expected.red) <= 1.5f / 255 &&
                abs(icon.green - expected.green) <= 1.5f / 255 &&
                abs(icon.blue - expected.blue) <= 1.5f / 255
        )
    }
}
