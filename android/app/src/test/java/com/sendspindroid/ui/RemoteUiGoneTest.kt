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

    @Test
    fun remoteTransportPackageIsDeletedFromApp() {
        val dir = File("src/main/java/com/sendspindroid/remote")
        assertFalse("app remote package must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun remoteTransportPackageIsDeletedFromShared() {
        val root = File("../shared/src/commonMain/kotlin")
        require(root.isDirectory) { "shared module not found at " + root.absolutePath + " -- module may have moved" }
        val dir = File(root, "com/sendspindroid/remote")
        assertFalse("shared remote package must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun proxyTransportIsDeleted() {
        val root = File("../shared/src/commonMain/kotlin")
        require(root.isDirectory) { "shared module not found at " + root.absolutePath + " -- module may have moved" }
        val f = File(root, "com/sendspindroid/sendspin/transport/ProxyWebSocketTransport.kt")
        assertFalse("ProxyWebSocketTransport must be deleted", f.exists())
    }

    @Test
    fun webRtcDependencyIsGone() {
        val gradle = File("build.gradle.kts")
        require(gradle.exists()) { "app build.gradle.kts not found at " + gradle.absolutePath }
        val offending = gradle.readLines()
            .map { it.trim() }
            .filter { !it.startsWith("//") && it.contains("webrtc") }
        assertEquals("the WebRTC dependency must be removed", emptyList<String>(), offending)
    }

    @Test
    fun noSourceImportsWebRtc() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import org.webrtc") }
                    .map { file.name + ": " + it }
            }
        assertEquals("no source may import org.webrtc", emptyList<String>(), offending)
    }
}
