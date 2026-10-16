package com.sendspindroid

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.shape.MaterialShapeDrawable
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.ui.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The welcome comes before the network. Building the MediaController binds
 * PlaybackService, which advertises the player as soon as it is created, so
 * on the first run the controller waits for the welcome's Continue: the
 * user names the player before any server can see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWelcomeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshSettings() {
        // UserSettings is a process-wide object: give it this test's
        // preferences, or a name saved by an earlier test would carry over.
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
        // Robolectric runs no media session, and by default answers the
        // controller's bind with a null component, which media3 throws on.
        // Refused, the bind fails as it would for a missing service.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    // A post left on the main looper is dropped when the test ends. Compose's
    // AndroidUiDispatcher, which every test in this JVM shares, never posts
    // again after one of its posts is dropped, and a Compose test that runs
    // after this one would never get idle.
    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun welcomeDone() =
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .getBoolean("onboarding_shown", false)

    private fun controllerFuture(activity: MainActivity): Any? =
        MainActivity::class.java.getDeclaredField("mediaControllerFuture").run {
            isAccessible = true
            get(activity)
        }

    private fun welcomeDialog(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun confirm(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The dialog delivers the click through its handler.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a first run shows the welcome and does not start the service`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNotNull(welcomeDialog())
        assertNull(controllerFuture(activity))
    }

    @Test
    fun `continue starts the service and the welcome is not shown again`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        confirm(welcomeDialog()!!)

        assertNotNull(controllerFuture(activity))
        assertTrue(welcomeDone())
    }

    @Test
    fun `after the welcome the service starts at once`() {
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNull(welcomeDialog())
        assertNotNull(controllerFuture(activity))
    }

    private fun View.firstEditText(): EditText? = when (this) {
        is EditText -> this
        is ViewGroup -> children.firstNotNullOfOrNull { it.firstEditText() }
        else -> null
    }

    private fun AlertDialog.nameField(): EditText = window!!.decorView.firstEditText()!!

    @Test
    fun `the welcome offers the name the player would use`() {
        Robolectric.buildActivity(MainActivity::class.java).create()

        assertEquals(Build.MODEL, welcomeDialog()!!.nameField().text.toString())
    }

    @Test
    fun `the name field is the outlined field`() {
        Robolectric.buildActivity(MainActivity::class.java).create()

        // The TextInputLayout gives the field its outline as a background. A
        // field that missed the layout's theme overlay keeps the plain
        // EditText underline instead, and no outline is drawn.
        assertTrue(welcomeDialog()!!.nameField().background is MaterialShapeDrawable)
    }

    @Test
    fun `continue keeps the name typed in the welcome`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val dialog = welcomeDialog()!!

        dialog.nameField().setText("Kitchen")
        confirm(dialog)

        assertEquals("Kitchen", UserSettings.getPlayerName())
        assertNotNull(controllerFuture(activity))
    }

    @Test
    fun `a blank name falls back to the device model`() {
        Robolectric.buildActivity(MainActivity::class.java).create()
        val dialog = welcomeDialog()!!

        dialog.nameField().setText("")
        confirm(dialog)

        assertEquals(Build.MODEL, UserSettings.getPlayerName())
    }

    @Test
    fun `done on the keyboard does what continue does`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val field = welcomeDialog()!!.nameField()

        field.setText("Kitchen")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Kitchen", UserSettings.getPlayerName())
        assertNotNull(controllerFuture(activity))
        assertTrue(welcomeDone())
    }

    private fun countRenames(): () -> Int {
        var told = 0
        LocalBroadcastManager.getInstance(app).registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { told++ }
            },
            IntentFilter(SettingsViewModel.ACTION_PLAYER_NAME_CHANGED)
        )
        return { told }
    }

    @Test
    fun `a service already running is told the new name`() {
        val renames = countRenames()
        Robolectric.buildActivity(MainActivity::class.java).create()

        confirm(welcomeDialog()!!)

        assertEquals(1, renames())
    }

    @Test
    fun `pressing continue twice confirms once`() {
        val renames = countRenames()
        Robolectric.buildActivity(MainActivity::class.java).create()
        val continueButton = welcomeDialog()!!.getButton(AlertDialog.BUTTON_POSITIVE)

        // A remote's OK pressed twice: both clicks are queued before the
        // dialog's dismissal is.
        continueButton.performClick()
        continueButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, renames())
    }
}
