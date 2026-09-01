package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SendSpinDroid is a SendSpin player. The protocol defines no library,
 * browse, or search concept, so Now Playing is the only root destination
 * and connection state must not select a browse tab.
 *
 * AppShell encodes Now Playing as selectedNavTab == null && currentDetail
 * == null, so the rule is enforced by asserting that nothing in the file
 * selects NavTab.HOME or reacts to connection state by changing the tab.
 */
class NowPlayingRootTest {

    private fun appShellLines(): List<String> {
        val source = File("src/main/java/com/sendspindroid/ui/AppShell.kt")
        require(source.exists()) { "AppShell.kt not found at " + source.absolutePath }
        return source.readLines().map { it.trim() }
    }

    @Test
    fun nothingSelectsABrowseDestination() {
        val offending = appShellLines().filter { it.contains("NavTab") }
        assertEquals("Now Playing is the only root; NavTab must be unreachable", emptyList<String>(), offending)
    }

    @Test
    fun noDetailNavigationRemains() {
        val offending = appShellLines().filter {
            it.contains("DetailDestination") || it.contains("navigateToDetail") || it.contains("currentDetail")
        }
        assertEquals("SendSpin defines no browse surface to navigate into", emptyList<String>(), offending)
    }

    @Test
    fun noBrowseScreenImportsRemain() {
        val offending = appShellLines().filter {
            it.startsWith("import com.sendspindroid.musicassistant") ||
                it.startsWith("import com.sendspindroid.ui.navigation") ||
                it.startsWith("import com.sendspindroid.ui.detail")
        }
        assertEquals("AppShell must not import MA or browse packages", emptyList<String>(), offending)
    }

    @Test
    fun nowPlayingScreenIsStillRendered() {
        val rendersNowPlaying = appShellLines().any { it.contains("NowPlayingScreen(") }
        assertEquals(
            "AppShell must still render NowPlayingScreen -- these tests only assert absence of browse code",
            true,
            rendersNowPlaying
        )
    }
}
