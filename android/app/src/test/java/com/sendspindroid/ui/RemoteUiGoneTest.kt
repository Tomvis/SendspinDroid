package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote access via Music Assistant's signaling server and via an authenticated
 * reverse proxy is removed. SendSpin's spec defines no remote-access mechanism,
 * and the signaling endpoint was a third-party dependency. Local connection and
 * manual address entry remain.
 */
class RemoteUiGoneTest {

    @Test
    fun remoteUiPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/remote")
        assertFalse("ui/remote must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheRemoteUiPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.remote") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.remote", emptyList<String>(), offending)
    }

    @Test
    fun wizardOffersNoRemoteOrProxyMethod() {
        val source = File("src/main/java/com/sendspindroid/ui/wizard/WizardNavigation.kt")
        require(source.exists()) { "WizardNavigation.kt not found at " + source.absolutePath }
        val text = source.readText()
        val offending = listOf("REMOTE_ID", "PROXY").filter { text.contains(it) }
        assertEquals("the wizard must offer no remote or proxy method", emptyList<String>(), offending)
    }

    @Test
    fun localAddressEntrySurvives() {
        val source = File("src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt")
        require(source.exists()) { "wizard view model not found at " + source.absolutePath }
        assertTrue("manual address entry must survive", source.readText().contains("localAddress"))
    }
}
