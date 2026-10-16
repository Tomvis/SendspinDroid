package com.sendspindroid.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.ServerListScreen
import com.sendspindroid.ui.main.components.ConnectionModeState
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server list driven by a TV remote: every action is reachable with the
 * D-pad, and focus starts on, and stays on, something that can be used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land")
class ServerListTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun server(id: String, name: String, isDefault: Boolean = false) = UnifiedServer(
        id = id,
        name = name,
        local = LocalConnection("192.168.1.$id:8927"),
        isDefaultServer = isDefault,
    )

    private val livingRoom = server("1", "Living Room")
    private val bedroom = server("2", "Bedroom")
    private val office = server("3", "Office")
    private val kitchen = server("4", "Kitchen Server").copy(isDiscovered = true)

    private val saved = mutableStateOf(emptyList<UnifiedServer>())
    private val discovered = mutableStateOf(emptyList<UnifiedServer>())
    private val menus = mutableListOf<String>()
    private val connects = mutableListOf<String>()

    private fun show(formFactor: FormFactor = FormFactor.TV, searching: Boolean = false) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    ServerListScreen(
                        savedServers = saved.value,
                        discoveredServers = discovered.value,
                        onlineSavedServerIds = emptySet(),
                        isScanning = false,
                        serverStatuses = emptyMap(),
                        reconnectInfo = emptyMap(),
                        onServerClick = {},
                        onServerLongClick = { menus += it.name },
                        onQuickConnectClick = { connects += it.name },
                        onAddServerClick = {},
                        mode = ConnectionModeState(searching, "Living Room TV", 8928) {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun moreOptions(name: String) = "More options for $name"

    @Test
    fun `on a TV the More button is one step right of a card and OK on it opens the menu`() {
        saved.value = listOf(livingRoom)
        show()
        val card = composeTestRule.onNodeWithText("Living Room")
        card.requestFocus()

        card.performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()
        val more = composeTestRule.onNodeWithContentDescription(moreOptions("Living Room"))
        more.assertIsFocused()

        more.performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()
        assertEquals(listOf("Living Room"), menus)
    }

    @Test
    fun `on a TV a nearby server's card has a More button too`() {
        saved.value = listOf(livingRoom)
        discovered.value = listOf(kitchen)
        show(searching = true)

        composeTestRule.onNodeWithContentDescription(moreOptions("Kitchen Server")).assertExists()
        composeTestRule.onNodeWithText("Quick Connect").assertDoesNotExist()
    }

    @Test
    fun `on a phone a card has no More button`() {
        saved.value = listOf(livingRoom)
        show(FormFactor.PHONE)

        composeTestRule.onNodeWithContentDescription(moreOptions("Living Room")).assertDoesNotExist()
    }

    @Test
    fun `on a TV there is no FAB`() {
        saved.value = listOf(livingRoom)
        show()

        composeTestRule.onNodeWithContentDescription("Add Server").assertDoesNotExist()
    }

    @Test
    fun `on a TV the default server's card takes focus`() {
        saved.value = listOf(livingRoom, bedroom.copy(isDefaultServer = true), office)
        show()

        composeTestRule.onNodeWithText("Bedroom").assertIsFocused()
    }

    @Test
    fun `on a TV with no default the first saved server's card takes focus`() {
        saved.value = listOf(livingRoom, bedroom)
        show()

        composeTestRule.onNodeWithText("Living Room").assertIsFocused()
    }

    @Test
    fun `on a TV a default server further down is scrolled to and takes focus`() {
        saved.value = (1..9).map { server("$it", "Room $it", isDefault = it == 8) }
        show()

        composeTestRule.onNodeWithText("Room 8").assertIsFocused()
    }

    @Test
    fun `on a TV with no saved server Add Your First Server takes focus`() {
        show()

        composeTestRule.onNodeWithText("Add Your First Server").assertIsFocused()
    }

    @Test
    fun `on a TV focus stays on Add Your First Server when the first server is found`() {
        show(searching = true)
        composeTestRule.onNodeWithText("Add Your First Server").assertIsFocused()

        discovered.value = listOf(kitchen)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1 server(s) found nearby").assertExists()
        composeTestRule.onNodeWithText("Add Your First Server").assertIsFocused()
    }

    @Test
    fun `on a TV a server found below the welcome is reached with Down`() {
        discovered.value = listOf(kitchen)
        show(searching = true)
        val modeButton = composeTestRule.onNodeWithText("Wait for a server to connect instead")
        modeButton.requestFocus()

        modeButton.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen Server").assertIsFocused()
    }

    @Test
    fun `on a TV holding OK on a found server connects`() {
        discovered.value = listOf(kitchen)
        show(searching = true)
        val modeButton = composeTestRule.onNodeWithText("Wait for a server to connect instead")
        modeButton.requestFocus()
        modeButton.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        val card = composeTestRule.onNodeWithText("Kitchen Server")
        card.assertIsFocused()

        // Held past the long-press timeout. The card has no menu to open.
        card.performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            keyUp(Key.DirectionCenter)
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("Kitchen Server"), connects)
    }

    @Test
    fun `on a phone a long press on a found server connects`() {
        discovered.value = listOf(kitchen)
        show(FormFactor.PHONE, searching = true)

        composeTestRule.onNodeWithText("Kitchen Server").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("Kitchen Server"), connects)
    }

    @Test
    fun `on a TV a found server that goes away while focused gives focus back to Add`() {
        discovered.value = listOf(kitchen)
        show(searching = true)
        val modeButton = composeTestRule.onNodeWithText("Wait for a server to connect instead")
        modeButton.requestFocus()
        modeButton.performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Kitchen Server").assertIsFocused()

        discovered.value = emptyList()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Add Your First Server").assertIsFocused()
    }

    @Test
    fun `on a TV the list takes focus when its first server is saved`() {
        show()
        composeTestRule.onNodeWithText("Add Your First Server").assertIsFocused()

        saved.value = listOf(livingRoom)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Living Room").assertIsFocused()
    }

    @Test
    fun `on a TV deleting the focused server moves focus to the next card`() {
        saved.value = listOf(livingRoom, bedroom, office)
        show()
        composeTestRule.onNodeWithText("Bedroom").requestFocus()

        saved.value = listOf(livingRoom, office)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Office").assertIsFocused()
    }

    @Test
    fun `on a TV deleting the focused last server moves focus to the first card`() {
        saved.value = listOf(livingRoom, bedroom, office)
        show()
        // Deleted from its menu, so focus is on its More button
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(moreOptions("Office")))
        composeTestRule.onNodeWithContentDescription(moreOptions("Office")).requestFocus()

        saved.value = listOf(livingRoom, bedroom)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Living Room").assertIsFocused()
    }
}
