package com.sendspindroid.playback

import android.content.Context
import android.os.Looper
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import com.sendspindroid.sendspin.protocol.ColorState
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The colors the server sends for the current audio (color@v1) reach the
 * activity through [PlaybackService.serverColors], and go when the
 * connection does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceServerColorsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)
    private val colorState = MutableStateFlow<ColorState?>(null)
    private var controller: ServiceController<PlaybackService>? = null

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        // Search mode, so a dial is made at once rather than after the
        // advertisement is withdrawn.
        prefs.edit().putBoolean(UserSettings.KEY_SEARCH_FOR_SERVERS, true).commit()
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        mockkConstructor(SendSpin::class)
        every { anyConstructed<SendSpin>().connectionState } returns clientState
        every { anyConstructed<SendSpin>().colorState } returns colorState
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

    @Test
    fun `the server colors are relayed while connected and cleared when the connection ends`() {
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        created.get().connectToServer("192.168.1.100:8927")
        clientState.value = TransportState.Ready
        idle()
        val palette = ColorState(timestamp = 1, backgroundDark = 0xFF123456.toInt())

        colorState.value = palette
        idle()
        assertEquals(palette, PlaybackService.serverColors.value)

        clientState.value = TransportState.Idle
        idle()
        assertNull(PlaybackService.serverColors.value)
    }
}
