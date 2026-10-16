package com.sendspindroid

import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.ShownPairingCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The activity takes the dynamic pairing code from the session extras with
 * its emission format, the other half of the hop that
 * [com.sendspindroid.playback.PlaybackServicePairingCodeTest] covers. A
 * token read without its format would go to the digits display and be
 * shown, and read out, as text, where pairing.md "QR-code emission" has it
 * "rendered as a QR code on its display".
 *
 * The extras handler is private, so it is reached by reflection. The welcome
 * is left unconfirmed, so no controller binds the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPairingCodeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
    }

    // A post left on the main looper is dropped when the test ends. Compose's
    // AndroidUiDispatcher, which every test in this JVM shares, never posts
    // again after one of its posts is dropped, and a Compose test that runs
    // after this one would never get idle.
    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a qr_code pairing code from the session extras keeps its format`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val extras = Bundle().apply {
            putString(PlaybackService.EXTRA_PAIRING_CODE, TOKEN)
            putString(PlaybackService.EXTRA_PAIRING_CODE_FORMAT, PairingCodeFormat.QR_CODE.wire)
        }

        MainActivity::class.java.getDeclaredMethod("processSessionExtras", Bundle::class.java)
            .apply { isAccessible = true }
            .invoke(activity, extras)

        val viewModel = ViewModelProvider(activity)[MainActivityViewModel::class.java]
        assertEquals(ShownPairingCode(TOKEN, PairingCodeFormat.QR_CODE), viewModel.pairingCode.value)
    }

    private companion object {
        /** pairing.md's reference vector, for `code = 0xe0 0xe1 ... 0xf7`. */
        const val TOKEN = "SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y"
    }
}
