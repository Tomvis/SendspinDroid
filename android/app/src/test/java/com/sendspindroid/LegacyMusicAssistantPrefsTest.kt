package com.sendspindroid

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The removed Music Assistant API client left access tokens in a preferences
 * file of its own. The file name and key shapes are spelled out as that client
 * wrote them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyMusicAssistantPrefsTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun legacyPrefs() = context.getSharedPreferences("ma_settings", Context.MODE_PRIVATE)
    private fun legacyFile() = File(context.dataDir, "shared_prefs/ma_settings.xml")

    @Test
    fun `stored tokens are deleted with their file`() {
        legacyPrefs().edit()
            .putString("token_server-1", "access-token")
            .putString("selected_player_server-1", "player-1")
            .putInt("default_port", 8095)
            .commit()
        assertTrue("the test must start from a file on disk", legacyFile().exists())

        deleteLegacyMusicAssistantPrefs(context)

        assertFalse("the file must be gone", legacyFile().exists())
        assertTrue("nothing may survive in memory either", legacyPrefs().all.isEmpty())
    }

    @Test
    fun `nothing happens when there is no file`() {
        assertFalse(legacyFile().exists())

        deleteLegacyMusicAssistantPrefs(context)

        assertFalse(legacyFile().exists())
    }

    @Test
    fun `other preferences files are left alone`() {
        val servers = context.getSharedPreferences("unified_server_repository", Context.MODE_PRIVATE)
        servers.edit().putString("saved_servers", "record").commit()
        legacyPrefs().edit().putString("token_server-1", "access-token").commit()

        deleteLegacyMusicAssistantPrefs(context)

        assertEquals("record", servers.getString("saved_servers", null))
    }
}
