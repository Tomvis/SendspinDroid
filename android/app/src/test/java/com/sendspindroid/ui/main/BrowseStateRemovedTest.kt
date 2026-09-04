package com.sendspindroid.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SendSpin defines no library, browse, or search concept, so the app has no
 * browse destinations to model. NavTab and DetailDestination existed only to
 * navigate Music Assistant's library screens.
 */
class BrowseStateRemovedTest {

    private fun sourceLines(relativePath: String): List<String> {
        val source = File(relativePath)
        require(source.exists()) { "not found: " + source.absolutePath }
        return source.readLines().map { it.trim() }
    }

    @Test
    fun browseTypesAreGone() {
        val lines = sourceLines("src/main/java/com/sendspindroid/ui/main/MainUiState.kt")
        val offending = lines.filter {
            it.startsWith("enum class NavTab") || it.startsWith("sealed class DetailDestination")
        }
        assertEquals("browse navigation types must be deleted", emptyList<String>(), offending)
    }

    @Test
    fun playerStateTypesSurvive() {
        val text = File("src/main/java/com/sendspindroid/ui/main/MainUiState.kt").readText()
        val missing = listOf(
            "data class TrackMetadata",
            "sealed class ArtworkSource",
            "data class ReconnectingState",
            "sealed class ServerStatus",
            "data class PlayerColors"
        ).filterNot { text.contains(it) }
        assertEquals("player state types must be retained", emptyList<String>(), missing)
    }
}
