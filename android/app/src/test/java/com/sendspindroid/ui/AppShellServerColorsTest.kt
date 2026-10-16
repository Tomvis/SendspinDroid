package com.sendspindroid.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.theme.contrast
import com.sendspindroid.ui.theme.md_theme_dark_background
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Where the server's palette shows: the connected Now Playing shell with its
 * top bar, and on a TV the frame around Now Playing while connected. The
 * server list keeps the Material colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppShellServerColorsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val background = Color(0xFF6C291A)
    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = background.toArgb(),
        backgroundLight = 0xFFE2A498.toInt(),
        onDark = 0xFFEED6AA.toInt(),
        onLight = 0xFF221E3E.toInt(),
    )

    private fun show(formFactor: FormFactor, state: AppConnectionState, colors: ColorState = palette): PixelMap {
        composeTestRule.runOnUiThread { viewModel.updateConnectionState(state) }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme(darkTheme = true, dynamicColor = false) {
                    AppShell(
                        viewModel = viewModel,
                        serverListContent = {},
                        onPreviousClick = {},
                        onPlayPauseClick = {},
                        onNextClick = {},
                        onSwitchGroupClick = {},
                        onVolumeChange = {},
                        onSeek = {},
                        onAllowPairingClick = {},
                        onDisconnectClick = {},
                        onAddServerClick = {},
                        onStatsClick = {},
                        onSettingsClick = {},
                        onEditServerClick = {},
                        onExitAppClick = {},
                        serverColors = colors,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        return capture()
    }

    private fun capture(): PixelMap = composeTestRule.onRoot().captureToImage().toPixelMap()

    private val connected = AppConnectionState.Connected("Kitchen Server", "192.168.1.4:8927")

    /**
     * Within a step of 8-bit rounding: the top bar is the background at 60%
     * alpha over itself.
     */
    private fun isServerBackground(pixel: Color) =
        abs(pixel.red - background.red) <= 1.5f / 255 &&
            abs(pixel.green - background.green) <= 1.5f / 255 &&
            abs(pixel.blue - background.blue) <= 1.5f / 255

    private fun assertServerBackground(where: String, pixel: Color) =
        assertTrue("$where is $pixel", isServerBackground(pixel))

    private fun assertMaterial(where: String, pixel: Color) =
        assertFalse("$where is $pixel", isServerBackground(pixel))

    @Test
    fun `connected, the top bar and Now Playing take the server background`() {
        val screen = show(FormFactor.PHONE, connected)

        assertServerBackground("top bar", screen[screen.width / 2, 2])
        assertServerBackground("Now Playing", screen[2, screen.height - 2])
    }

    @Test
    fun `the server list keeps the Material colors`() {
        val screen = show(FormFactor.PHONE, AppConnectionState.ServerList)

        assertMaterial("top bar", screen[screen.width / 2, 2])
        assertMaterial("server list", screen[2, screen.height - 2])
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    // Fork: the TV Now Playing screen paints its own artwork-tinted
    // background, so only the shell behind the (hidden) rail is checked
    fun `on a TV, connected, the menu rail takes the server background`() {
        val screen = show(FormFactor.TV, connected)

        assertServerBackground("menu rail", screen[2, screen.height / 2])
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV the server list keeps the Material colors`() {
        val screen = show(FormFactor.TV, AppConnectionState.ServerList)

        assertMaterial("menu rail", screen[2, screen.height / 2])
        assertMaterial("server list", screen[screen.width - 2, screen.height - 2])
    }

    /**
     * The server list keeps the Material colors from its first frame: the
     * frame Now Playing was in does not blend from the album colors into it.
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV leaving the connection shows the server list in the Material colors at once`() {
        show(FormFactor.TV, connected)

        // The clock is stepped by hand, so the first frame is what is drawn
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnUiThread { viewModel.updateConnectionState(AppConnectionState.ServerList) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        val screen = capture()
        val pixel = screen[2, screen.height / 2]

        assertTrue(
            "menu rail is $pixel",
            abs(pixel.red - md_theme_dark_background.red) <= 1.5f / 255 &&
                abs(pixel.green - md_theme_dark_background.green) <= 1.5f / 255 &&
                abs(pixel.blue - md_theme_dark_background.blue) <= 1.5f / 255
        )
    }

    /**
     * Music Assistant's palette for a dark cover: no candidate reaches its
     * preferred 7:1, so on_dark is at the 4.5:1 floor on a black background.
     */
    private val darkCover = ColorState(
        timestamp = 1,
        backgroundDark = 0xFF000000.toInt(),
        backgroundLight = 0xFFB8BABE.toInt(),
        onDark = 0xFF72747E.toInt(),
        onLight = 0xFF23283C.toInt(),
    )

    /** The color [text] is laid out in, alpha included. */
    private fun textColor(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    /**
     * The open menu is a Material panel over the screen, and menus keep the
     * Material theme. roles/color/v1.md guarantees `on_dark` "4.5:1 with
     * `background_dark`" and with black text, not with Material's containers,
     * so the server's name and the selected entry are in the Material colors
     * too.
     */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV the open menu's text keeps 4_5 to 1 on its Material panel`() {
        show(FormFactor.TV, connected, darkCover)
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        // Focus off Now Playing, which is then selected but not focused
        composeTestRule.onNodeWithText("Switch Server").requestFocus()
        composeTestRule.waitForIdle()
        val screen = capture()

        val panel = screen[2, screen.height / 2]
        val name = contrast(textColor("Kitchen Server").compositeOver(panel), panel)
        assertTrue("server name is $name", name >= 4.5)

        // The selected entry, on its container at the entry's far end
        val entry = composeTestRule.onNodeWithText("Now Playing").fetchSemanticsNode().boundsInRoot
        val inset = with(composeTestRule.density) { 4.dp.toPx() }
        val container = screen[(entry.right - inset).toInt(), entry.center.y.toInt()]
        val selected = contrast(textColor("Now Playing").compositeOver(container), container)
        assertTrue("selected entry is $selected on $container", selected >= 4.5)
    }
}
