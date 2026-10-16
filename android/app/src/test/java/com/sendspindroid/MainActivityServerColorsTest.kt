package com.sendspindroid

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.ui.main.MainActivityViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The colors PlaybackService relays from the server reach Now Playing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityServerColorsTest {

    private val app get() = RuntimeEnvironment.getApplication()

    // What the service sets from the client's color state
    @Suppress("UNCHECKED_CAST")
    private val relayed = PlaybackService::class.java.getDeclaredField("_serverColors").run {
        isAccessible = true
        get(null) as MutableStateFlow<ColorState?>
    }

    private val background = Color(0xFF6C291A)
    private val palette = ColorState(
        timestamp = 1,
        backgroundDark = background.toArgb(),
        backgroundLight = 0xFFE2A498.toInt(),
        onDark = 0xFFEED6AA.toInt(),
        onLight = 0xFF221E3E.toInt(),
    )

    @Before
    fun setUp() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
        // Robolectric runs no media session, and by default answers the
        // controller's bind with a null component, which media3 throws on.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        // Past the welcome, so nothing covers Now Playing
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()
    }

    @After
    fun tearDown() {
        relayed.value = null
        // A post left on the main looper would leave Compose's shared
        // dispatcher unable to get idle in a later test
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun connected(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ViewModelProvider(activity)[MainActivityViewModel::class.java]
            .updateConnectionState(AppConnectionState.Connected("Kitchen Server", "192.168.1.4:8927"))
        return activity
    }

    /** A pixel at the bottom left of the app, after any blend has run. */
    private fun MainActivity.nowPlayingBackground(): Color {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = root.drawToBitmap()
        return Color(bitmap.getPixel(2, bitmap.height - 2))
    }

    @Test
    fun `connected, Now Playing takes the server's background`() {
        val activity = connected()
        assertNotEquals(background, activity.nowPlayingBackground())

        relayed.value = palette

        assertEquals(background, activity.nowPlayingBackground())
    }

    @Test
    fun `the Album colors switch takes effect at once`() {
        val activity = connected()
        relayed.value = palette
        assertEquals(background, activity.nowPlayingBackground())

        UserSettings.serverColors = false
        assertNotEquals(background, activity.nowPlayingBackground())

        UserSettings.serverColors = true
        assertEquals(background, activity.nowPlayingBackground())
    }
}
