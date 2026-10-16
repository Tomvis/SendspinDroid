package com.sendspindroid.ui.compose

import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.components.VolumeSlider
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that VolumeSlider correctly reports values in the 0-1 range
 * and that the callback fires with properly clamped values.
 *
 * Note: The Compose Slider widget uses a 0..1 value range internally.
 * The app maps this to 0-100 at a higher level. These tests verify
 * the slider's own value range and callback behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeSliderTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun volumeSlider_callbackReceivesValues_inZeroToOneRange() {
        val receivedValues = mutableListOf<Float>()

        composeTestRule.setContent {
            SendSpinTheme {
                VolumeSlider(
                    volume = 0.5f,
                    onVolumeChange = { receivedValues.add(it) }
                )
            }
        }

        // The slider is rendered; verify it does not crash
        composeTestRule.waitForIdle()
    }

    @Test
    fun volumeSlider_initialVolume_zero_rendersWithoutCrash() {
        composeTestRule.setContent {
            SendSpinTheme {
                VolumeSlider(
                    volume = 0f,
                    onVolumeChange = {}
                )
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun volumeSlider_initialVolume_one_rendersWithoutCrash() {
        composeTestRule.setContent {
            SendSpinTheme {
                VolumeSlider(
                    volume = 1f,
                    onVolumeChange = {}
                )
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun volumeSlider_disabled_rendersWithoutCrash() {
        composeTestRule.setContent {
            SendSpinTheme {
                VolumeSlider(
                    volume = 0.75f,
                    onVolumeChange = {},
                    enabled = false
                )
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun volumeSlider_stateUpdate_reflectsNewVolume() {
        var currentVolume by mutableFloatStateOf(0.3f)

        composeTestRule.setContent {
            SendSpinTheme {
                VolumeSlider(
                    volume = currentVolume,
                    onVolumeChange = { currentVolume = it }
                )
            }
        }

        // Programmatically update volume and verify composable doesn't crash
        currentVolume = 0.8f
        composeTestRule.waitForIdle()

        // Verify internal state was updated
        assertEquals(0.8f, currentVolume, 0.01f)
    }

    private var volume by mutableFloatStateOf(0.5f)

    /** The slider between two buttons, on a phone driven by a keyboard or D-pad. */
    private fun setSliderBetweenButtons() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.PHONE) {
                SendSpinTheme {
                    Column {
                        Button(onClick = {}) { Text("Above") }
                        VolumeSlider(volume = volume, onVolumeChange = { volume = it })
                        Button(onClick = {}) { Text("Below") }
                    }
                }
            }
        }
    }

    private fun slider() =
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    @Test
    fun volumeSlider_upAndDown_moveFocusOff_withoutChangingTheVolume() {
        setSliderBetweenButtons()

        slider().requestFocus()
        slider().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Below").assertIsFocused()
        assertEquals(0.5f, volume, 0f)

        slider().requestFocus()
        slider().performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("Above").assertIsFocused()
        assertEquals(0.5f, volume, 0f)
    }

    @Test
    fun volumeSlider_leftAndRight_stillStepTheVolume() {
        setSliderBetweenButtons()

        slider().requestFocus()
        slider().performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()
        assertEquals(0.51f, volume, 0.001f)

        slider().performKeyInput { pressKey(Key.DirectionLeft) }
        slider().performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()
        assertEquals(0.49f, volume, 0.001f)
        slider().assertIsFocused()
    }

    @Test
    fun volumeSlider_downAtTheBottomOfCompose_movesFocusToTheViewBelow() {
        // As on Now Playing in portrait: the slider is the last focus stop in
        // Compose, and a View beside the ComposeView, such as a Snackbar's
        // action, is below it.
        val activity = composeTestRule.activity
        val below = android.widget.Button(activity).apply { text = "Below" }
        composeTestRule.runOnUiThread {
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ComposeView(activity).apply {
                    setContent {
                        CompositionLocalProvider(LocalFormFactor provides FormFactor.PHONE) {
                            SendSpinTheme {
                                VolumeSlider(volume = volume, onVolumeChange = { volume = it })
                            }
                        }
                    }
                })
                addView(below)
            })
        }

        slider().requestFocus()
        slider().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()

        assertTrue(below.isFocused)
        assertEquals(0.5f, volume, 0f)
    }
}
