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
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.main.albumArtistToShow
import io.mockk.every
import io.mockk.mockk
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The album artist and year reach Now Playing's metadata through each of the
 * two feeds that race to set it: the session extras, and the Media3
 * MediaMetadata, which the activity reads both when it changes and when it
 * resumes. A feed that left them out would wipe what the other had set, and
 * one that kept the last track's would show them on a track that has none.
 *
 * Set up like [MainActivityRecreateTest]: Robolectric runs no media session,
 * so the controller is a stand-in put where the connected one would be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTrackMetadataTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private val compilation = MediaMetadata.Builder()
        .setTitle("Bohemian Rhapsody")
        .setArtist("Queen")
        .setAlbumTitle("Now 44")
        .setAlbumArtist("Various Artists")
        .setReleaseYear(1999)
        .setRecordingYear(1999)
        .build()

    // A track the server sent no album artist or year for: the forwarding
    // player puts the track artist in for the album artist.
    private val radioEdit = MediaMetadata.Builder()
        .setTitle("Radio Edit")
        .setArtist("Queen")
        .setAlbumTitle("Singles")
        .setAlbumArtist("Queen")
        .build()

    // With no artist either, there is nothing to put in for the album artist.
    private val interlude = MediaMetadata.Builder()
        .setTitle("Interlude")
        .setAlbumTitle("Singles")
        .build()

    private val controller = mockk<MediaController>(relaxed = true)

    @Before
    fun refuseServiceBind() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resumeOver(mediaMetadata: MediaMetadata): ActivityController<MainActivity> {
        every { controller.playbackState } returns Player.STATE_READY
        every { controller.isPlaying } returns true
        every { controller.sessionExtras } returns Bundle()
        every { controller.mediaMetadata } returns mediaMetadata

        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        MainActivity::class.java.getDeclaredField("mediaController").apply {
            isAccessible = true
            set(activity.get(), controller)
        }
        return activity.start().resume()
    }

    private fun ActivityController<MainActivity>.metadata(): TrackMetadata =
        ViewModelProvider(get())[MainActivityViewModel::class.java].metadata.value

    private fun ActivityController<MainActivity>.sessionExtrasArrive(extras: Bundle) {
        MainActivity::class.java.getDeclaredMethod("processSessionExtras", Bundle::class.java)
            .apply { isAccessible = true }
            .invoke(get(), extras)
    }

    private fun ActivityController<MainActivity>.mediaMetadataChanges(mediaMetadata: MediaMetadata) {
        val listener = MainActivity::class.java.declaredClasses
            .first { it.simpleName == "PlayerStateListener" }
            .getDeclaredConstructor(MainActivity::class.java)
            .apply { isAccessible = true }
            .newInstance(get()) as Player.Listener
        listener.onMediaMetadataChanged(mediaMetadata)
    }

    @Test
    fun `a new activity takes the album artist and year of the current track`() {
        val activity = resumeOver(compilation)

        val metadata = activity.metadata()
        assertEquals("Various Artists", metadata.albumArtist)
        assertEquals(1999, metadata.year)
    }

    @Test
    fun `a change of the Media3 metadata carries the album artist and year`() {
        val activity = resumeOver(MediaMetadata.EMPTY)

        activity.mediaMetadataChanges(compilation)

        val metadata = activity.metadata()
        assertEquals("Various Artists", metadata.albumArtist)
        assertEquals(1999, metadata.year)
    }

    @Test
    fun `a change of the Media3 metadata to a track without them drops the year and the album artist`() {
        val activity = resumeOver(compilation)

        activity.mediaMetadataChanges(radioEdit)

        val metadata = activity.metadata()
        // Fork: the view model's TrackMetadata uses 0 and "" for none
        assertEquals(0, metadata.year)
        assertNull(albumArtistToShow(metadata))
    }

    @Test
    fun `a change of the Media3 metadata to a track without an artist drops the last track's album artist`() {
        val activity = resumeOver(compilation)

        activity.mediaMetadataChanges(interlude)

        assertEquals(TrackMetadata("Interlude", "", "Singles"), activity.metadata())
    }

    @Test
    fun `a resume over a track without them drops the last track's year and album artist`() {
        val activity = resumeOver(compilation)

        every { controller.mediaMetadata } returns interlude
        activity.pause().resume()

        assertEquals(TrackMetadata("Interlude", "", "Singles"), activity.metadata())
    }

    @Test
    fun `session extras carry the album artist and year`() {
        val activity = resumeOver(MediaMetadata.EMPTY)

        activity.sessionExtrasArrive(Bundle().apply {
            putString(PlaybackService.EXTRA_TITLE, "Bohemian Rhapsody")
            putString(PlaybackService.EXTRA_ARTIST, "Queen")
            putString(PlaybackService.EXTRA_ALBUM, "Now 44")
            putString(PlaybackService.EXTRA_ALBUM_ARTIST, "Various Artists")
            putInt(PlaybackService.EXTRA_YEAR, 1999)
        })

        assertEquals(
            TrackMetadata("Bohemian Rhapsody", "Queen", "Now 44", albumArtist = "Various Artists", year = 1999),
            activity.metadata()
        )
    }

    @Test
    fun `session extras without an album artist or year leave them null`() {
        val activity = resumeOver(compilation)

        activity.sessionExtrasArrive(Bundle().apply {
            putString(PlaybackService.EXTRA_TITLE, "Radio Edit")
            putString(PlaybackService.EXTRA_ARTIST, "Queen")
            putString(PlaybackService.EXTRA_ALBUM, "Singles")
        })

        // Not "" or 0, and not the previous track's.
        assertEquals(TrackMetadata("Radio Edit", "Queen", "Singles"), activity.metadata())
    }
}
