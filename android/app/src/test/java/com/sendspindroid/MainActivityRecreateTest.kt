package com.sendspindroid

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.PlaybackState
import io.mockk.every
import io.mockk.mockk
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
import org.robolectric.annotation.Config

/**
 * A [MainActivity] created while [com.sendspindroid.playback.PlaybackService]
 * is already playing, which is what Back followed by a relaunch produces.
 *
 * The new Activity has a new ViewModel, and the player's listener only reports
 * changes, so the state the player is already in has to be read on resume. Now
 * Playing enables the transport controls on READY or BUFFERING and advances
 * the elapsed time on isPlaying, so both were stuck off until the next change.
 *
 * Robolectric runs no media session, so the controller is a stand-in put where
 * the connected one would be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRecreateTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun refuseServiceBind() {
        // Robolectric by default answers the controller's bind with a null
        // component, which media3 throws on. Refused, the bind fails as it
        // would for a missing service.
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

    private fun resumeOver(playerState: Int, playing: Boolean): MainActivityViewModel {
        val controller = mockk<MediaController>(relaxed = true)
        every { controller.playbackState } returns playerState
        every { controller.isPlaying } returns playing
        every { controller.sessionExtras } returns Bundle()
        every { controller.mediaMetadata } returns MediaMetadata.EMPTY

        // A relaunch: the welcome was confirmed on the first run, so the
        // activity builds its controller in onCreate.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        MainActivity::class.java.getDeclaredField("mediaController").apply {
            isAccessible = true
            set(activity.get(), controller)
        }
        // Not idling the looper: the real controller's bind to a service that
        // is not there is still queued on it, and fails when delivered, after
        // the test.
        activity.start().resume()

        return ViewModelProvider(activity.get())[MainActivityViewModel::class.java]
    }

    @Test
    fun `a new activity takes the state of a player that is already playing`() {
        val viewModel = resumeOver(Player.STATE_READY, playing = true)

        assertEquals(PlaybackState.READY, viewModel.playbackState.value)
        assertTrue(viewModel.isPlaying.value)
    }

    @Test
    fun `a new activity takes the state of a player that is buffering`() {
        val viewModel = resumeOver(Player.STATE_BUFFERING, playing = false)

        assertEquals(PlaybackState.BUFFERING, viewModel.playbackState.value)
        assertFalse(viewModel.isPlaying.value)
    }
}
