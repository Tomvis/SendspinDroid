package com.sendspindroid

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app is a Sendspin player. It connects to Music Assistant because Music
 * Assistant is a Sendspin server, and holds no second connection to Music
 * Assistant's own API, so the client for that API must not exist.
 *
 * This module's tests run with working directory android/app, so the :shared
 * module is reached via a relative path one level up.
 */
class MusicAssistantClientGoneTest {

    @Test
    fun clientPackageIsDeletedFromApp() {
        val dir = File("src/main/java/com/sendspindroid/musicassistant")
        assertFalse("musicassistant must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun clientPackageIsDeletedFromShared() {
        val moduleRoot = File("../shared/src/commonMain/kotlin")
        assertTrue("shared commonMain root not found, module may have moved: " + moduleRoot.absolutePath, moduleRoot.isDirectory)
        val dir = File(moduleRoot, "com/sendspindroid/musicassistant")
        assertFalse("shared musicassistant must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheClientPackage() {
        val roots = listOf(
            File("src/main/java"),
            File("src/test/java"),
            File("../shared/src"),
        )
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.musicassistant") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import the musicassistant package", emptyList<String>(), offending)
    }

    @Test
    fun savedServersCarryNoMusicAssistantFlag() {
        val model = File("../shared/src/commonMain/kotlin/com/sendspindroid/model/UnifiedServer.kt")
        assertTrue("UnifiedServer.kt not found at " + model.absolutePath, model.isFile)
        assertFalse(
            "UnifiedServer must not carry isMusicAssistant",
            model.readText().contains("isMusicAssistant")
        )
    }
}
