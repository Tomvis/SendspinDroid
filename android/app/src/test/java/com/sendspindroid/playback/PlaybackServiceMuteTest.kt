package com.sendspindroid.playback

import android.content.Context
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Muting this player from the app (#374). The mute reaches the server, the
 * activity's slider through the session extras, and the next run.
 * roles/player/v1.md: "`volume` and `muted` are independent: a volume change
 * ... MUST NOT clear the mute state", and "Persisting `volume` and `muted`
 * across reboots is RECOMMENDED for players."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceMuteTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val server = UnifiedServer(
        id = "server-1",
        name = "Living Room",
        local = LocalConnection("192.168.1.100:8927"),
    )
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)
    private var controller: ServiceController<PlaybackService>? = null

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        // Search mode: nothing listens on a port of this machine.
        prefs.edit().putBoolean(UserSettings.KEY_SEARCH_FOR_SERVERS, true).commit()
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getServer(server.id) } returns server
        every { UnifiedServerRepository.getDefaultServer() } returns null
        mockkConstructor(SendSpin::class)
        every { anyConstructed<SendSpin>().connectionState } returns clientState
        every { anyConstructed<SendSpin>().connect(any<SendSpinEndpoint>()) } answers {
            clientState.value = TransportState.Connecting
        }
    }

    @After
    fun tearDown() {
        controller?.destroy()
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): PlaybackService {
        controller?.destroy()
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        idle()
        return created.get()
    }

    private fun connect(service: PlaybackService) {
        service.setCurrentServer(server.id)
        service.connectToServer(server.local!!.address)
        idle()
    }

    /** The mute the service last sent the activity, in its session extras. */
    private fun publishedMuted(service: PlaybackService): Boolean {
        val session = PlaybackService::class.java.getDeclaredField("mediaSession")
            .apply { isAccessible = true }.get(service) as MediaSession
        val extras = session.sessionExtras
        assertTrue("no mute in the session extras", extras.containsKey(PlaybackService.EXTRA_MUTED))
        return extras.getBoolean(PlaybackService.EXTRA_MUTED)
    }

    @Test
    fun `muting reaches the server, the slider and the settings`() {
        val service = start()
        connect(service)

        service.setPlayerMuted(true)
        idle()

        verify { anyConstructed<SendSpin>().setMuted(true) }
        assertTrue(service.playbackState.value.muted)
        assertTrue(publishedMuted(service))
        assertTrue(UserSettings.getPlayerMuted())
    }

    @Test
    fun `un-muting reaches the server and the slider`() {
        val service = start()
        connect(service)
        service.setPlayerMuted(true)

        service.setPlayerMuted(false)
        idle()

        verify { anyConstructed<SendSpin>().setMuted(false) }
        assertFalse(publishedMuted(service))
        assertFalse(UserSettings.getPlayerMuted())
    }

    @Test
    fun `a volume change leaves the mute alone`() {
        val service = start()
        connect(service)
        service.setPlayerMuted(true)

        service.setVolume(0.5f)
        idle()

        assertEquals(50, service.playbackState.value.volume)
        assertTrue(service.playbackState.value.muted)
        assertTrue(publishedMuted(service))
    }

    @Test
    fun `a mute set before a restart is reported on the next connection`() {
        start().setPlayerMuted(true)

        val service = start()
        connect(service)

        verify { anyConstructed<SendSpin>().setInitialVolume(any(), true) }
        assertTrue(service.playbackState.value.muted)
    }
}
