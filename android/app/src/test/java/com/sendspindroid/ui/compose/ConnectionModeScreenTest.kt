package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
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
 * The default screen says which way the app and a server find each other:
 * it advertises and waits by default, and offers to search instead. Adding a
 * server by hand and dialling a saved one stay available either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionModeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val saved = UnifiedServer(
        id = "1",
        name = "Living Room",
        local = LocalConnection("192.168.1.100:8927"),
    )
    private val discovered = UnifiedServer(
        id = "2",
        name = "Kitchen Server",
        local = LocalConnection("192.168.1.101:8927"),
        isDiscovered = true,
    )

    private val modeChanges = mutableListOf<Boolean>()
    private val clicked = mutableListOf<String>()

    private fun show(searching: Boolean, savedServers: List<UnifiedServer> = emptyList(), port: Int? = 8928) {
        composeTestRule.setContent {
            SendSpinTheme {
                ServerListScreen(
                    savedServers = savedServers,
                    discoveredServers = listOf(discovered),
                    onlineSavedServerIds = emptySet(),
                    isScanning = false,
                    serverStatuses = emptyMap(),
                    reconnectInfo = emptyMap(),
                    onServerClick = { clicked += it.name },
                    onServerLongClick = {},
                    onQuickConnectClick = {},
                    onAddServerClick = { clicked += "add" },
                    mode = ConnectionModeState(searching, "Kitchen Tablet", port) { modeChanges += it },
                )
            }
        }
    }

    @Test
    fun `by default the first screen says it is advertising and waiting`() {
        show(searching = false)

        composeTestRule.onNodeWithText("Waiting for a server to connect").assertIsDisplayed()
        composeTestRule.onNodeWithText("Advertising as \"Kitchen Tablet\"", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening on port 8928").assertIsDisplayed()
    }

    @Test
    fun `the button offers to search for servers instead`() {
        show(searching = false)

        composeTestRule.onNodeWithText("Search for servers instead").performScrollTo().performClick()

        assertEquals(listOf(true), modeChanges)
    }

    @Test
    fun `search mode offers the way back to advertising`() {
        show(searching = true)

        composeTestRule.onNodeWithText("Searching for servers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wait for a server to connect instead").performScrollTo().performClick()

        assertEquals(listOf(false), modeChanges)
    }

    @Test
    fun `a server can be added by hand in both modes`() {
        show(searching = false)
        composeTestRule.onNodeWithText("Add Your First Server").performClick()

        assertEquals(listOf("add"), clicked)
    }

    @Test
    fun `saved servers stay listed and dialable while advertising`() {
        show(searching = false, savedServers = listOf(saved))

        composeTestRule.onNodeWithText("Waiting for a server to connect").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search for servers instead").assertIsDisplayed()
        composeTestRule.onNodeWithText("Living Room").performClick()
        composeTestRule.onNodeWithContentDescription("Add Server").performClick()

        assertEquals(listOf("Living Room", "add"), clicked)
    }

    @Test
    fun `nothing is listed as nearby while advertising`() {
        show(searching = false, savedServers = listOf(saved))

        composeTestRule.onNodeWithText("Nearby Servers").assertDoesNotExist()
        composeTestRule.onNodeWithText("Kitchen Server").assertDoesNotExist()
    }

    @Test
    fun `nearby servers are listed while searching`() {
        show(searching = true, savedServers = listOf(saved))

        composeTestRule.onNodeWithText("Nearby Servers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen Server").assertIsDisplayed()
    }

    @Test
    fun `says so while the listener is still starting`() {
        show(searching = false, port = null)

        composeTestRule.onNodeWithText("Starting to listen", substring = true).assertIsDisplayed()
    }
}
