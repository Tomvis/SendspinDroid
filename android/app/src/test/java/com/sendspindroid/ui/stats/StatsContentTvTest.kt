package com.sendspindroid.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a TV the stats are scrolled with the remote: Up and Down, from the moment the sheet opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsContentTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val networkHeader = context.getString(R.string.stats_section_network)

    private var backReachedSheet = false

    private fun showStats(formFactor: FormFactor) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    // Shorter than the stats, as the sheet is on a TV. Back
                    // must get past the stats to the sheet, which it dismisses.
                    Box(
                        Modifier
                            .height(300.dp)
                            .onKeyEvent {
                                if (it.key == Key.Back) backReachedSheet = true
                                false
                            }
                    ) {
                        StatsContent(state = StatsState(connectionState = "Connected"))
                    }
                }
            }
        }
    }

    private fun scrollPosition(): Float = composeTestRule.onNode(isFocusable()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun `on a TV the stats take focus when shown and Down and Up scroll them`() {
        showStats(FormFactor.TV)
        val stats = composeTestRule.onNode(isFocusable())
        stats.assertIsFocused()
        assertEquals(0f, scrollPosition())
        composeTestRule.onNodeWithText(networkHeader).assertIsNotDisplayed()

        stats.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        val afterOneDown = scrollPosition()
        assertTrue("Down scrolls the stats", afterOneDown > 0f)

        repeat(20) { stats.performKeyInput { pressKey(Key.DirectionDown) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(networkHeader).assertIsDisplayed()
        val atBottom = scrollPosition()

        stats.performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()
        assertTrue("Up scrolls the stats back", scrollPosition() < atBottom)
    }

    @Test
    fun `on a TV Back is left to dismiss the sheet`() {
        showStats(FormFactor.TV)
        composeTestRule.onNode(isFocusable()).performKeyInput { pressKey(Key.Back) }
        composeTestRule.waitForIdle()
        assertTrue(backReachedSheet)
    }

    @Test
    fun `on a phone nothing in the stats takes focus`() {
        showStats(FormFactor.PHONE)
        composeTestRule.onAllNodes(isFocusable()).assertCountEquals(0)
    }
}
