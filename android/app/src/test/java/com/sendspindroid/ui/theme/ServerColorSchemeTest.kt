package com.sendspindroid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.sendspindroid.sendspin.protocol.ColorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The color scheme Now Playing takes from a server's color@v1 palette.
 *
 * roles/color/v1.md guarantees contrast for these pairs only: "`background_dark`
 * ... MUST ensure a minimum WCAG contrast ratio of 4.5:1 with white text and
 * with `on_dark`", "`on_dark` ... 4.5:1 with `background_dark` ... and with
 * black text", and the same for light with black text and white. `primary`
 * and `accent` are "Not adjusted for contrast".
 */
class ServerColorSchemeTest {

    private val dark = darkColorScheme()
    private val light = lightColorScheme()

    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = rgb(108, 41, 26),
        backgroundLight = rgb(226, 164, 152),
        primary = rgb(196, 74, 48),
        accent = rgb(34, 30, 62),
        onDark = rgb(238, 214, 170),
        onLight = rgb(34, 30, 62),
    )

    @Test
    fun `dark takes background_dark, white text and on_dark for the controls`() {
        val scheme = requireScheme(dark.withServerColors(palette, dark = true))

        assertEquals(Color(rgb(108, 41, 26)), scheme.background)
        assertEquals(Color(rgb(108, 41, 26)), scheme.surface)
        assertEquals(Color.White, scheme.onBackground)
        assertEquals(Color.White, scheme.onSurface)
        assertEquals(Color(rgb(238, 214, 170)), scheme.primary)
        assertEquals(Color.Black, scheme.onPrimary)
        assertEquals(Color(rgb(238, 214, 170)), scheme.onSurfaceVariant)
        assertEquals(Color(rgb(238, 214, 170)), scheme.onSecondaryContainer)
    }

    @Test
    fun `light takes background_light, black text and on_light for the controls`() {
        val scheme = requireScheme(light.withServerColors(palette, dark = false))

        assertEquals(Color(rgb(226, 164, 152)), scheme.background)
        assertEquals(Color(rgb(226, 164, 152)), scheme.surface)
        assertEquals(Color.Black, scheme.onBackground)
        assertEquals(Color.Black, scheme.onSurface)
        assertEquals(Color(rgb(34, 30, 62)), scheme.primary)
        assertEquals(Color.White, scheme.onPrimary)
        assertEquals(Color(rgb(34, 30, 62)), scheme.onSurfaceVariant)
        assertEquals(Color(rgb(34, 30, 62)), scheme.onSecondaryContainer)
    }

    @Test
    fun `no background for the mode means no server colors`() {
        assertNull(dark.withServerColors(palette.copy(backgroundDark = null), dark = true))
        assertNull(light.withServerColors(palette.copy(backgroundLight = null), dark = false))
        // What aiosendspin sends for a cleared or empty palette
        assertNull(dark.withServerColors(ColorState(timestamp = 1), dark = true))
    }

    @Test
    fun `a missing on color falls back to the text color`() {
        val darkScheme = requireScheme(dark.withServerColors(palette.copy(onDark = null), dark = true))
        assertEquals(Color.White, darkScheme.primary)
        assertEquals(Color.Black, darkScheme.onPrimary)
        assertEquals(Color.White, darkScheme.onSurfaceVariant)

        val lightScheme = requireScheme(light.withServerColors(palette.copy(onLight = null), dark = false))
        assertEquals(Color.Black, lightScheme.primary)
        assertEquals(Color.White, lightScheme.onPrimary)
        assertEquals(Color.Black, lightScheme.onSurfaceVariant)
    }

    @Test
    fun `the server's primary and accent are not used`() {
        for (mode in listOf(true, false)) {
            val base = if (mode) dark else light
            val loud = palette.copy(primary = rgb(255, 0, 255), accent = rgb(0, 255, 0))
            val without = palette.copy(primary = null, accent = null)

            assertEquals(
                base.withServerColors(without, mode).toString(),
                base.withServerColors(loud, mode).toString()
            )
        }
    }

    @Test
    fun `menus, dialogs and the error colors keep the Material scheme`() {
        for (mode in listOf(true, false)) {
            val base = if (mode) dark else light
            val scheme = requireScheme(base.withServerColors(palette, mode))

            assertEquals(base.surfaceContainer, scheme.surfaceContainer)
            assertEquals(base.surfaceContainerHigh, scheme.surfaceContainerHigh)
            assertEquals(base.inverseSurface, scheme.inverseSurface)
            assertEquals(base.inverseOnSurface, scheme.inverseOnSurface)
            assertEquals(base.error, scheme.error)
            assertEquals(base.tertiaryContainer, scheme.tertiaryContainer)
            assertEquals(base.onTertiaryContainer, scheme.onTertiaryContainer)
            assertEquals(base.scrim, scheme.scrim)
        }
    }

    @Test
    fun `text and controls keep 4_5 to 1 on every palette`() {
        for (p in palettes) {
            assertSpecPalette(p)
            for (mode in listOf(true, false)) {
                val base = if (mode) dark else light
                val s = requireScheme(base.withServerColors(p, mode))
                val pairs = listOf(
                    "onBackground/background" to (s.onBackground to s.background),
                    "onSurface/surface" to (s.onSurface to s.surface),
                    "primary/background" to (s.primary to s.background),
                    "onSurfaceVariant/background" to (s.onSurfaceVariant to s.background),
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                )
                for ((name, pair) in pairs) {
                    val ratio = contrast(pair.first, pair.second)
                    assertTrue("$name is $ratio in ${if (mode) "dark" else "light"} for $p", ratio >= 4.5)
                }
            }
        }
    }

    /**
     * Music Assistant's palettes for four sets of cover-art candidates, and
     * palettes at the spec's 4.5:1 floor: the lightest and darkest
     * backgrounds, and saturated ones.
     */
    private val palettes = listOf(
        palette,
        ColorState(1, rgb(30, 68, 120), rgb(148, 172, 208), onDark = rgb(220, 220, 225), onLight = rgb(20, 30, 40)),
        ColorState(1, rgb(88, 80, 21), rgb(252, 238, 118), onDark = rgb(240, 240, 240), onLight = rgb(120, 40, 140)),
        // A dark cover: no candidate reaches Music Assistant's preferred 7:1,
        // so on_dark is at the 4.5:1 floor on black
        ColorState(1, rgb(0, 0, 0), rgb(184, 186, 190), onDark = rgb(114, 116, 126), onLight = rgb(35, 40, 60)),
        ColorState(1, rgb(0x76, 0x76, 0x76), rgb(255, 255, 255), onDark = rgb(255, 255, 255), onLight = rgb(0x76, 0x76, 0x76)),
        ColorState(1, rgb(0, 0, 0), rgb(0x76, 0x76, 0x76), onDark = rgb(0x76, 0x76, 0x76), onLight = rgb(0, 0, 0)),
        ColorState(1, rgb(0, 0, 255), rgb(255, 255, 0), onDark = rgb(0xBC, 0xBC, 0xBC), onLight = rgb(0x71, 0x71, 0x71)),
        ColorState(1, rgb(0, 0, 255), rgb(255, 255, 0)),
    )

    /** The fixture itself meets every pair the spec makes the server keep. */
    private fun assertSpecPalette(p: ColorState) {
        val bgDark = Color(p.backgroundDark!!)
        val bgLight = Color(p.backgroundLight!!)
        val pairs = buildList {
            add("background_dark/white" to contrast(bgDark, Color.White))
            add("background_light/black" to contrast(bgLight, Color.Black))
            p.onDark?.let {
                add("on_dark/background_dark" to contrast(Color(it), bgDark))
                add("on_dark/black" to contrast(Color(it), Color.Black))
            }
            p.onLight?.let {
                add("on_light/background_light" to contrast(Color(it), bgLight))
                add("on_light/white" to contrast(Color(it), Color.White))
            }
        }
        for ((name, ratio) in pairs) assertTrue("fixture $name is $ratio for $p", ratio >= 4.5)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun requireScheme(scheme: ColorScheme?): ColorScheme {
        assertNotNull("expected server colors", scheme)
        return scheme!!
    }
}
