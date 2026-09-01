package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendSpin defines no viewable queue, so the queue surface has no protocol
 * counterpart. SaveQueueAsPlaylistDialog additionally wrote to a Music
 * Assistant playlist, a library-write feature outside the player role.
 */
class QueueSurfaceGoneTest {

    @Test
    fun queuePackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/queue")
        assertFalse("ui/queue must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheQueuePackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.queue") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.queue", emptyList<String>(), offending)
    }

    @Test
    fun nowPlayingScreenStillRenders() {
        val src = File("src/main/java/com/sendspindroid/ui/AppShell.kt").readText()
        assertTrue("AppShell must still render NowPlayingScreen", src.contains("NowPlayingScreen("))
    }

    @Test
    fun transportControlsSurvive() {
        val src = File("src/main/java/com/sendspindroid/ui/main/NowPlayingScreen.kt").readText()
        val missing = listOf(
            "onPlayPauseClick",
            "onNextClick",
            "onPreviousClick",
            "onVolumeChange"
        ).filterNot { src.contains(it) }
        assertEquals("transport controls must survive", emptyList<String>(), missing)
    }
}
