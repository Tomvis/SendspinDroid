package com.sendspindroid.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.protocol.TrackMetadata
import com.sendspindroid.sendspin.protocol.TrackProgress
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * How the service carries the metadata role's album artist, year and track
 * from SendSpin's callback to PlaybackState and on to the session's
 * MediaMetadata. MetadataForwardingPlayer replaces those three on every
 * update, so the service's call into it (fork: updateMediaMetadata only)
 * must pass them: one that leaves them out wipes them from the session.
 *
 * The callback, the forwarding player and the URL artwork path are private
 * to the service, so they are reached by reflection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceMetadataTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var controller: ServiceController<PlaybackService>? = null
    private lateinit var service: PlaybackService
    private lateinit var callback: SendSpin.Callback

    private val compilationTrack = TrackMetadata(
        timestamp = 1,
        title = "Bohemian Rhapsody",
        artist = "Queen",
        albumArtist = "Various Artists",
        album = "Now 44",
        year = 1999,
        track = 7,
        // Fork: the parser fills albumTrack from album_track, else track.
        albumTrack = 7,
        progress = TrackProgress(trackProgress = 0, trackDuration = 354_000, playbackSpeed = 1000),
    )

    /** A track the server has no album artist, year, track or progress for. */
    private val bareTrack = TrackMetadata(timestamp = 2, title = "Radio Edit", artist = "Queen", album = "Singles")

    @Before
    fun setUp() {
        UserSettings.resetForTesting()
        UserSettings.initializeForTesting(prefs, context.getSharedPreferences("sensitive", Context.MODE_PRIVATE))
        mockkObject(UnifiedServerRepository)
        every { UnifiedServerRepository.initialize(any()) } just runs
        every { UnifiedServerRepository.getDefaultServer() } returns null
        mockkConstructor(SendSpin::class)
        every { anyConstructed<SendSpin>().connectionState } returns MutableStateFlow<TransportState>(TransportState.Idle)

        val created = Robolectric.buildService(PlaybackService::class.java).create()
        controller = created
        service = created.get()
        idle()
        callback = PlaybackService::class.java.declaredClasses
            .first { it.simpleName == "SendSpinClientCallback" }
            .getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }
            .newInstance(service) as SendSpin.Callback
    }

    @After
    fun tearDown() {
        controller?.destroy()
        unmockkAll()
        UserSettings.resetForTesting()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun metadataArrives(metadata: TrackMetadata) {
        callback.onMetadataUpdate(metadata, 0L)
        idle()
    }

    private fun sessionMetadata(): MediaMetadata {
        val player = PlaybackService::class.java.getDeclaredField("forwardingPlayer")
            .apply { isAccessible = true }.get(service) as MetadataForwardingPlayer
        return player.mediaMetadata
    }

    /**
     * The image fetched from the track's artwork URL reaches the session.
     * Fork: what fetchArtwork does on success (urlArtwork for the current
     * lastArtworkUrl, then updateMediaMetadata).
     */
    private fun urlArtworkArrives() {
        fun field(name: String) = PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("lastArtworkUrl").set(service, "http://server/art.jpg")
        field("urlArtwork").set(service, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        PlaybackService::class.java.getDeclaredMethod("updateMediaMetadata")
            .apply { isAccessible = true }
            .invoke(service)
        idle()
    }

    @Test
    fun `the server's album artist, year and track reach the state and the session`() {
        metadataArrives(compilationTrack)

        val state = service.playbackState.value
        assertEquals("Various Artists", state.albumArtist)
        assertEquals(1999, state.year)
        assertEquals(7, state.albumTrack)
        val session = sessionMetadata()
        assertEquals("Various Artists", session.albumArtist?.toString())
        assertEquals(1999, session.releaseYear)
        assertEquals(1999, session.recordingYear)
        assertEquals(7, session.trackNumber)
    }

    @Test
    fun `artwork that arrives after the metadata keeps them on the session`() {
        metadataArrives(compilationTrack)

        urlArtworkArrives()

        val session = sessionMetadata()
        assertNotNull("the artwork reached the session", session.artworkData)
        assertEquals("Various Artists", session.albumArtist?.toString())
        assertEquals(1999, session.recordingYear)
        assertEquals(7, session.trackNumber)
    }

    @Test
    fun `a track without them keeps none of the last track's`() {
        metadataArrives(compilationTrack)

        metadataArrives(bareTrack)

        val state = service.playbackState.value
        assertNull(state.albumArtist)
        assertNull(state.year)
        assertNull(state.albumTrack)
        val session = sessionMetadata()
        // Fork: no fallback to the track artist.
        assertNull(session.albumArtist)
        assertNull(session.releaseYear)
        assertNull(session.recordingYear)
        assertNull(session.trackNumber)
    }

    @Test
    fun `the playback speed is the progress object's, and 0 without one`() {
        metadataArrives(compilationTrack.copy(progress = TrackProgress(0, 354_000, 1500)))
        assertEquals(1500, service.playbackState.value.playbackSpeed)

        // roles/metadata/v1.md: "`progress?`: object - ... Omitting it clears
        // the client's position", so there is nothing to advance.
        metadataArrives(bareTrack)
        assertEquals(0, service.playbackState.value.playbackSpeed)
    }
}
