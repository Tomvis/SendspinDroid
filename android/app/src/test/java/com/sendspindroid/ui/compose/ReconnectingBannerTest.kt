package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.main.ReconnectingState
import com.sendspindroid.ui.main.components.ReconnectingBanner
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that ReconnectingBanner displays the server and the attempt count:
 * "Reconnecting to {server} (attempt {n})".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconnectingBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun banner_showsServerAndAttempt() {
        composeTestRule.setContent {
            SendSpinTheme {
                ReconnectingBanner(
                    state = ReconnectingState(
                        serverName = "Kitchen",
                        attempt = 1
                    )
                )
            }
        }

        // String: "Reconnecting to Kitchen (attempt 1)"
        composeTestRule.onNodeWithText(
            "Reconnecting to Kitchen (attempt 1)"
        ).assertIsDisplayed()
    }

    @Test
    fun banner_highAttempt_displaysCorrectly() {
        composeTestRule.setContent {
            SendSpinTheme {
                ReconnectingBanner(
                    state = ReconnectingState(
                        serverName = "Office",
                        attempt = 10
                    )
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Reconnecting to Office (attempt 10)"
        ).assertIsDisplayed()
    }
}
