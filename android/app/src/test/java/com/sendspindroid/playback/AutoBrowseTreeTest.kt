package com.sendspindroid.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto keeps SendSpin server selection -- it is the only way to choose
 * or switch a server from a car -- but must not offer a Music Assistant
 * library. SendSpin's protocol has no library, so those branches had no
 * counterpart to keep.
 */
class AutoBrowseTreeTest {

    private fun treeSource(): String =
        File("src/main/java/com/sendspindroid/playback/AutoBrowseTree.kt").readText()

    @Test
    fun serverSelectionSurvives() {
        val src = treeSource()
        val missing = listOf(
            "MEDIA_ID_ROOT",
            "MEDIA_ID_DISCOVERED",
            "MEDIA_ID_SERVER_PREFIX",
            "MEDIA_ID_SAVED_SERVER_PREFIX",
            "MEDIA_ID_MESSAGE_NO_SERVERS"
        ).filterNot { src.contains(it) }
        assertEquals("server selection must survive in the Auto tree", emptyList<String>(), missing)
    }

    @Test
    fun musicAssistantBranchesAreGone() {
        val src = treeSource()
        val offending = listOf(
            "MEDIA_ID_MA_PLAYLISTS",
            "MEDIA_ID_MA_ALBUMS",
            "MEDIA_ID_MA_ARTISTS",
            "MEDIA_ID_MA_RADIO",
            "MEDIA_ID_MA_PLAYLIST_PREFIX",
            "MEDIA_ID_MA_ALBUM_PREFIX",
            "MEDIA_ID_MA_ARTIST_PREFIX"
        ).filter { src.contains(it) }
        assertEquals("MA library branches must be gone from the Auto tree", emptyList<String>(), offending)
    }

    @Test
    fun playbackServiceCallsNoLibraryMethods() {
        val src = File("src/main/java/com/sendspindroid/playback/PlaybackService.kt").readText()
        val offending = listOf(
            "MusicAssistant.getPlaylists",
            "MusicAssistant.getAlbums",
            "MusicAssistant.getArtists",
            "MusicAssistant.getRadioStations",
            "MusicAssistant.search"
        ).filter { src.contains(it) }
        assertEquals("PlaybackService must not call MA library methods", emptyList<String>(), offending)
    }

    @Test
    fun playbackServiceStillServesServerBrowsing() {
        val src = File("src/main/java/com/sendspindroid/playback/PlaybackService.kt").readText()
        assertTrue("onGetChildren must survive for server selection", src.contains("onGetChildren"))
        assertTrue("the discovered-servers branch must survive", src.contains("MEDIA_ID_DISCOVERED"))
    }
}
