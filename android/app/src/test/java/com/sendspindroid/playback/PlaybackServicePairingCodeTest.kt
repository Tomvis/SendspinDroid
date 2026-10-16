package com.sendspindroid.playback

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The dynamic pairing code reaches the activity through the session extras
 * with its emission format. In `qr_code` the code is a pairing token,
 * "rendered as a QR code on its display" (pairing.md, "QR-code emission"):
 * one that arrived without its format would be taken for digits and shown,
 * and read out, as text.
 *
 * The callback is private to the service, so it is reached by reflection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePairingCodeTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val clientState = MutableStateFlow<TransportState>(TransportState.Idle)
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

    /** The service, connected: the pairing code is sent only while it is. */
    private fun connectedService(): PlaybackService {
        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        created.get().connectToServer("192.168.1.100:8927")
        clientState.value = TransportState.Ready
        idle()
        return created.get()
    }

    private fun callback(service: PlaybackService): SendSpin.Callback =
        PlaybackService::class.java.declaredClasses
            .first { it.simpleName == "SendSpinClientCallback" }
            .getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }
            .newInstance(service) as SendSpin.Callback

    private fun sessionExtras(service: PlaybackService): Bundle =
        (PlaybackService::class.java.getDeclaredField("mediaSession")
            .apply { isAccessible = true }.get(service) as MediaSession).sessionExtras

    @Test
    fun `a qr_code pairing code reaches the session extras with its format`() {
        val service = connectedService()

        callback(service).onDynamicPairingCodeEmitted(TOKEN, PairingCodeFormat.QR_CODE)
        idle()

        val extras = sessionExtras(service)
        assertEquals(TOKEN, extras.getString(PlaybackService.EXTRA_PAIRING_CODE))
        // Read as the activity reads it.
        assertEquals(
            PairingCodeFormat.QR_CODE,
            PairingCodeFormat.fromWire(extras.getString(PlaybackService.EXTRA_PAIRING_CODE_FORMAT)),
        )
    }

    private companion object {
        /** pairing.md's reference vector, for `code = 0xe0 0xe1 ... 0xf7`. */
        const val TOKEN = "SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y"
    }
}
