package com.sendspindroid.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player package must be driven by SendSpin protocol state only:
 * server/state metadata, artwork frames, and controller commands.
 *
 * Music Assistant's multi-room grouping sheet lived here. SendSpin defines
 * group/update as a server-to-client notification with no client-initiated
 * grouping message, so a player cannot set group membership under the spec
 * and that UI has no counterpart to port to.
 */
class PlayerPackagePurityTest {

    @Test
    fun playerPackageHasNoMusicAssistantImports() {
        val dir = File("src/main/java/com/sendspindroid/ui/player")

        val offending = dir.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.musicassistant") }
                    .map { file.name + ": " + it }
            }

        assertEquals("player package must not import MA types", emptyList<String>(), offending)
    }

    @Test
    fun groupingSheetIsGone() {
        val removed = listOf("PlayerViewModel.kt", "PlayerSheetContent.kt", "PlayerBottomSheet.kt")
            .map { File("src/main/java/com/sendspindroid/ui/player/" + it) }
            .filter { it.exists() }
            .map { it.name }

        assertEquals("MA grouping sheet must be deleted", emptyList<String>(), removed)
    }
}
