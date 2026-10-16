package com.sendspindroid.ui.adaptive

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The transport controls lose focus while they are disabled, and get it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusReturnTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var enabled by mutableStateOf(true)

    private fun show() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    Row {
                        Button(onClick = {}) { Text("Art") }
                        val play = remember { FocusRequester() }
                        Row(modifier = Modifier.then(rememberFocusReturn(enabled, play))) {
                            Button(onClick = {}, enabled = enabled, modifier = Modifier.focusRequester(play)) {
                                Text("Play")
                            }
                            Button(onClick = {}, enabled = enabled) { Text("Next") }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `focus lost to disabled controls goes back to Play when they return`() {
        show()
        composeTestRule.onNodeWithText("Next").requestFocus()

        enabled = false
        composeTestRule.waitForIdle()

        enabled = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `initial focus waits for controls that cannot take it yet`() {
        enabled = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    val play = remember { FocusRequester() }
                    TvInitialFocus(play, canFocus = enabled)
                    Button(onClick = {}, enabled = enabled, modifier = Modifier.focusRequester(play)) {
                        Text("Play")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        enabled = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `focus elsewhere is not taken when the controls return`() {
        show()
        composeTestRule.onNodeWithText("Art").requestFocus()

        enabled = false
        composeTestRule.waitForIdle()
        enabled = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Art").assertIsFocused()
    }
}
