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
 * BootReceiver is exported, so any app can send it a broadcast. It acts on
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED only, which only the system can send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {

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
        prefs.edit()
            .putBoolean(UserSettings.KEY_AUTO_START_ON_BOOT, true)
            .putBoolean(UserSettings.KEY_SEARCH_FOR_SERVERS, true)
            .commit()
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getDefaultServer() } returns defaultServer
    }

    @After
    fun tearDown() {
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun receive(action: String) = BootReceiver().onReceive(context, Intent(action))

    private fun nextStartedService(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `boot completed connects to the default server`() {
        receive(Intent.ACTION_BOOT_COMPLETED)

        val started = nextStartedService()
        assertEquals(PlaybackService.ACTION_AUTO_CONNECT, started?.action)
        assertEquals(defaultServer.id, started?.getStringExtra(PlaybackService.EXTRA_SERVER_ID))
    }

    @Test
    fun `our own update reconnects too, since it kills the process and its player`() {
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        val started = nextStartedService()
        assertEquals(PlaybackService.ACTION_AUTO_CONNECT, started?.action)
        assertEquals(defaultServer.id, started?.getStringExtra(PlaybackService.EXTRA_SERVER_ID))
    }

    @Test
    fun `resumes only on system-only actions`() {
        assertTrue(BootReceiver.resumesOn(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootReceiver.resumesOn(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(BootReceiver.resumesOn("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(BootReceiver.resumesOn(null))
    }

    @Test
    fun `quick boot power-on, which any app can send, is ignored`() {
        receive("android.intent.action.QUICKBOOT_POWERON")

        assertNull(nextStartedService())
    }

    @Test
    fun `another broadcast is ignored`() {
        receive(Intent.ACTION_SCREEN_ON)

        assertNull(nextStartedService())
    }
}
