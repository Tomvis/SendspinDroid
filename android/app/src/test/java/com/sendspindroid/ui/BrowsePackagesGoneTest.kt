package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * SendSpin defines no library, browse, or search concept, so the packages that
 * implemented those against the Music Assistant API have no protocol
 * counterpart and must not exist.
 *
 * Task 4 extends this to ui/detail once the queue surface stops importing it.
 */
class BrowsePackagesGoneTest {

    @Test
    fun navigationPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/navigation")
        assertFalse("ui/navigation must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheNavigationPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.navigation") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.navigation", emptyList<String>(), offending)
    }
}
