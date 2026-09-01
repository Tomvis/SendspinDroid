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
    fun connectionStateNeverSelectsABrowseTab() {
        val offending = appShellLines().filter { it.contains("NavTab.HOME") }
        assertEquals("Now Playing is the only root; nothing may select HOME", emptyList<String>(), offending)
    }

    @Test
    fun noLaunchedEffectResetsTheTabOnConnect() {
        val offending = appShellLines().filter { it.startsWith("LaunchedEffect(isMaConnected)") }
        assertEquals("connection changes must not reset the root destination", emptyList<String>(), offending)
    }
}
