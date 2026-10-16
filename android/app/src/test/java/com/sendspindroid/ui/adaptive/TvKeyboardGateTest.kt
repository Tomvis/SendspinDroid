package com.sendspindroid.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV a text field keeps the keyboard back until OK, and Up and Down leave
 * it whatever device sent them: a test's keys, like adb's, come from no D-pad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvKeyboardGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(formFactor: FormFactor) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    Column {
                        Button(onClick = {}) { Text("Above") }
                        var value by remember { mutableStateOf("Kitchen") }
                        val gate = rememberTvKeyboardGate()
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            readOnly = gate.readOnly,
                            modifier = Modifier.then(gate.modifier)
                        )
                        Button(onClick = {}) { Text("Below") }
                    }
                }
            }
        }
    }

    @Test
    fun `on a TV up and down leave the field`() {
        show(FormFactor.TV)
        val field = composeTestRule.onNode(hasText("Kitchen"))
        field.requestFocus()

        field.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Below").assertIsFocused()

        field.requestFocus()
        field.performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Above").assertIsFocused()
    }

    @Test
    fun `on a TV the field is read-only until OK`() {
        show(FormFactor.TV)
        val field = composeTestRule.onNode(hasText("Kitchen"))
        field.requestFocus()
        composeTestRule.onNode(hasText("Kitchen") and hasSetTextAction()).assertDoesNotExist()

        field.performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasText("Kitchen") and hasSetTextAction()).assertExists()
    }

    @Test
    fun `on a phone the field is editable from the start`() {
        show(FormFactor.PHONE)

        composeTestRule.onNode(hasText("Kitchen") and hasSetTextAction()).assertExists()
    }
}
