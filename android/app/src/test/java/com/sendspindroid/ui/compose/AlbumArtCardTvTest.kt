package com.sendspindroid.ui.compose

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.components.AlbumArtCard
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A TV remote cannot swipe the artwork card: there OK turns it to the artist photo and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumArtCardTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val albumArt = context.getString(R.string.album_art)
    private val artistPhoto = context.getString(R.string.artist_photo)

    private fun showCard(formFactor: FormFactor, artistImage: ByteArray?) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    AlbumArtCard(artworkSource = null, isBuffering = false, artistImage = artistImage)
                }
            }
        }
    }

    @Test
    fun `on a TV OK on the focused card turns it to the artist photo and back`() {
        showCard(FormFactor.TV, artistImage = ByteArray(1))
        val card = composeTestRule.onNode(isFocusable())
        card.requestFocus()

        card.performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(artistPhoto).assertIsDisplayed()

        card.performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(albumArt).assertIsDisplayed()
    }

    @Test
    fun `on a TV with no artist photo the card does not take focus`() {
        showCard(FormFactor.TV, artistImage = null)
        composeTestRule.onAllNodes(isFocusable()).assertCountEquals(0)
    }

    @Test
    fun `on a TV focus goes to the fallback when the focused card stops taking focus`() {
        var artistImage by mutableStateOf<ByteArray?>(ByteArray(1))
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    val play = remember { FocusRequester() }
                    Column {
                        // First in line: where focus would land if nothing moved it
                        Button(onClick = {}) { Text("Menu") }
                        AlbumArtCard(
                            artworkSource = null,
                            isBuffering = false,
                            artistImage = artistImage,
                            focusFallback = play
                        )
                        Button(onClick = {}, modifier = Modifier.focusRequester(play)) {
                            Text("Play")
                        }
                    }
                }
            }
        }
        val card = composeTestRule.onNode(isFocusable() and hasAnyDescendant(hasContentDescription(albumArt)))
        card.requestFocus()
        card.assertIsFocused()

        // The next track has no artist photo
        artistImage = null
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `on a phone the card is swiped and does not take focus`() {
        showCard(FormFactor.PHONE, artistImage = ByteArray(1))
        composeTestRule.onAllNodes(isFocusable()).assertCountEquals(0)
    }
}
