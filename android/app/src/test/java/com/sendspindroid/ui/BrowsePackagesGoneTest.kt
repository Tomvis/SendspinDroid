package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendSpin defines no library, browse, or search concept, so the packages that
 * implemented those against the Music Assistant API have no protocol
 * counterpart and must not exist.
 *
 * This module's tests run with working directory android/app, so the :shared
 * module is reached via a relative path one level up. Same-package files (no
 * import statement, invisible to a plain import grep) is exactly how a copy of
 * this package survived in :shared once before, so both modules are checked.
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
    fun navigationPackageIsDeletedFromSharedCommonMain() {
        val moduleRoot = File("../shared/src/commonMain/kotlin")
        assertTrue("shared commonMain root not found, module may have moved: " + moduleRoot.absolutePath, moduleRoot.isDirectory)
        val dir = File(moduleRoot, "com/sendspindroid/ui/navigation")
        assertFalse("shared commonMain ui/navigation must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun navigationPackageIsDeletedFromSharedAndroidHostTest() {
        val moduleRoot = File("../shared/src/androidHostTest/kotlin")
        assertTrue("shared androidHostTest root not found, module may have moved: " + moduleRoot.absolutePath, moduleRoot.isDirectory)
        val dir = File(moduleRoot, "com/sendspindroid/ui/navigation")
        assertFalse("shared androidHostTest ui/navigation must be deleted, found " + dir.absolutePath, dir.exists())
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

    @Test
    fun detailPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/detail")
        assertFalse("ui/detail must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheDetailPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.detail") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.detail", emptyList<String>(), offending)
    }

    @Test
    fun searchLibraryOnlyToggleIsDeletedFromSettings() {
        val dir = File("src/main/java/com/sendspindroid/ui/settings")
        assertTrue("ui/settings not found: " + dir.absolutePath, dir.isDirectory)
        val offending = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("searchLibraryOnly") }
            .map { it.name }
            .toList()
        assertEquals("searchLibraryOnly must not appear in ui/settings", emptyList<String>(), offending)
    }
}
