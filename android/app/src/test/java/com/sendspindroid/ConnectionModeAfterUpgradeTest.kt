package com.sendspindroid

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.playback.PlaybackService
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Advertising is the default for a new install, but an install that was
 * already set up to connect to a default server goes on doing that: the
 * choice between the two is made once, from what the install looks like,
 * and is the user's own setting from then on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionModeAfterUpgradeTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val defaultServer = UnifiedServer(
        id = "server-1",
        name = "Living Room",
        local = LocalConnection("192.168.1.100:8927"),
        isDefaultServer = true,
    )

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun boot() {
        prefs.edit().putBoolean(UserSettings.KEY_AUTO_START_ON_BOOT, true).commit()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    // ---- the choice ----

    @Test
    fun `an install with a default server starts in search mode`() {
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = true)

        assertTrue(UserSettings.searchForServers)
    }

    @Test
    fun `an install without one starts advertising`() {
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = false)

        assertFalse(UserSettings.searchForServers)
    }

    @Test
    fun `the choice is made once and does not follow the default server afterwards`() {
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = false)
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = true)
        assertFalse("a default server added later does not switch the mode", UserSettings.searchForServers)

        prefs.edit().clear().commit()
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = true)
        UserSettings.chooseConnectionModeOnce(hasDefaultServer = false)
        assertTrue("nor does removing it", UserSettings.searchForServers)
    }

    @Test
    fun `what the user chose is left alone`() {
        UserSettings.searchForServers = false

        UserSettings.chooseConnectionModeOnce(hasDefaultServer = true)

        assertFalse(UserSettings.searchForServers)
    }

    // ---- at boot ----

    @Test
    fun `boot with a default server connects to it, as before the upgrade`() {
        every { UnifiedServerRepository.getDefaultServer() } returns defaultServer

        boot()

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(PlaybackService.ACTION_AUTO_CONNECT, started.action)
        assertEquals("server-1", started.getStringExtra(PlaybackService.EXTRA_SERVER_ID))
        assertTrue(UserSettings.searchForServers)
    }

    @Test
    fun `boot with no default server starts waiting for a server`() {
        every { UnifiedServerRepository.getDefaultServer() } returns null

        boot()

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(PlaybackService::class.java.name, started.component?.className)
        assertNull(started.action)
        assertFalse(UserSettings.searchForServers)
    }

    @Test
    fun `boot after the user chose to advertise waits, default server or not`() {
        UserSettings.searchForServers = false
        every { UnifiedServerRepository.getDefaultServer() } returns defaultServer

        boot()

        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService.action)
    }
}
