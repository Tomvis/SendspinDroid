package com.sendspindroid.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.UserSettings
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Album colors switch, under the Theme row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsServerColorsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
    }

    @After
    fun tearDown() {
        UserSettings.resetForTesting()
    }

    private fun show() {
        val viewModel = SettingsViewModel(app)
        composeTestRule.setContent {
            SendSpinTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportLogs = {},
                    onReportProblem = {},
                    onRestartApp = {},
                    canShare = true
                )
            }
        }
    }

    private val albumColorsSwitch = isToggleable() and hasParent(hasText("Album colors"))

    @Test
    fun `the switch is on, and turning it off is saved`() {
        UserSettings.serverColors = true // fork: off by default (HW-125)
        show()
        composeTestRule.onNodeWithText("Album colors").performScrollTo()
        composeTestRule.onNode(albumColorsSwitch).assertIsOn()

        composeTestRule.onNodeWithText("Album colors").performClick()

        composeTestRule.onNode(albumColorsSwitch).assertIsOff()
        assertFalse(UserSettings.serverColors)
    }

    @Test
    fun `a switch turned off shows off and turns back on`() {
        UserSettings.serverColors = false
        show()
        composeTestRule.onNodeWithText("Album colors").performScrollTo()
        composeTestRule.onNode(albumColorsSwitch).assertIsOff()

        composeTestRule.onNodeWithText("Album colors").performClick()

        assertTrue(UserSettings.serverColors)
    }
}
