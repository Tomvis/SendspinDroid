package com.sendspindroid.model

import com.sendspindroid.shared.platform.Platform
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackStateTest {

    @Before
    fun setUp() {
        mockkObject(Platform)
        every { Platform.elapsedRealtimeMs() } returns 10_000L
        every { Platform.currentTimeMillis() } returns 1_000_000L
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Default state ---

    @Test
    fun defaultState_displayTitleIsUnknownTrack() {
        val state = PlaybackState()
        assertEquals("Unknown Track", state.displayTitle)
    }

    @Test
    fun defaultState_displayArtistIsUnknownArtist() {
        val state = PlaybackState()
        assertEquals("Unknown Artist", state.displayArtist)
    }

    @Test
    fun defaultState_hasMetadataIsFalse() {
        val state = PlaybackState()
        assertFalse(state.hasMetadata)
    }

    @Test
    fun defaultState_playbackStateIsIdle() {
        val state = PlaybackState()
        assertEquals(PlaybackStateType.IDLE, state.playbackState)
    }

    // --- displayString ---

    @Test
    fun displayString_bothArtistAndTitle_returnsArtistDashTitle() {
        val state = PlaybackState(artist = "Beatles", title = "Hey Jude")
        assertEquals("Beatles - Hey Jude", state.displayString)
    }

    @Test
    fun displayString_onlyTitle_returnsTitleAlone() {
        val state = PlaybackState(title = "Hey Jude")
        assertEquals("Hey Jude", state.displayString)
    }

    @Test
    fun displayString_neither_returnsUnknownTrack() {
        val state = PlaybackState()
        assertEquals("Unknown Track", state.displayString)
    }

    // --- hasMetadata ---

    @Test
    fun hasMetadata_withTitle_isTrue() {
        assertTrue(PlaybackState(title = "Song").hasMetadata)
    }

    @Test
    fun hasMetadata_withArtist_isTrue() {
        assertTrue(PlaybackState(artist = "Artist").hasMetadata)
    }

    @Test
    fun hasMetadata_withAlbum_isTrue() {
        assertTrue(PlaybackState(album = "Album").hasMetadata)
    }

    // --- PlaybackStateType.fromString ---

    @Test
    fun playbackStateType_fromString_playing() {
        assertEquals(PlaybackStateType.PLAYING, PlaybackStateType.fromString("playing"))
    }

    @Test
    fun playbackStateType_fromString_paused() {
        assertEquals(PlaybackStateType.PAUSED, PlaybackStateType.fromString("paused"))
    }

    @Test
    fun playbackStateType_fromString_buffering() {
        assertEquals(PlaybackStateType.BUFFERING, PlaybackStateType.fromString("buffering"))
    }

    @Test
    fun playbackStateType_fromString_stopped() {
        assertEquals(PlaybackStateType.STOPPED, PlaybackStateType.fromString("stopped"))
    }

    @Test
    fun playbackStateType_fromString_unknown_returnsIdle() {
        assertEquals(PlaybackStateType.IDLE, PlaybackStateType.fromString("garbage"))
    }

    @Test
    fun playbackStateType_fromString_caseInsensitive() {
        assertEquals(PlaybackStateType.PLAYING, PlaybackStateType.fromString("PLAYING"))
        assertEquals(PlaybackStateType.PAUSED, PlaybackStateType.fromString("Paused"))
    }

    // --- withMetadata ---

    @Test
    fun withMetadata_updatesFields() {
        val state = PlaybackState(title = "Old Song", artist = "Old Artist")
        val updated = state.withMetadata(
            title = "New Song",
            artist = "New Artist",
            albumArtist = "New Album Artist",
            album = "New Album",
            artworkUrl = "https://art.jpg",
            year = 2024,
            albumTrack = 3,
            queueTrack = 5,
            totalTracks = 12,
            durationMs = 180000,
            positionMs = 5000
        )
        assertEquals("New Song", updated.title)
        assertEquals("New Artist", updated.artist)
        assertEquals("New Album Artist", updated.albumArtist)
        assertEquals("New Album", updated.album)
        assertEquals(2024, updated.year)
        assertEquals(3, updated.albumTrack)
        assertEquals(5, updated.queueTrack)
        assertEquals(12, updated.totalTracks)
        assertEquals(180000, updated.durationMs)
        assertEquals(5000, updated.positionMs)
    }

    @Test
    fun withMetadata_nullFieldsPreserveExisting() {
        val state = PlaybackState(
            title = "Keep This", artist = "Keep Artist", albumArtist = "Keep AA",
            year = 1999, albumTrack = 2, queueTrack = 4, totalTracks = 10
        )
        val updated = state.withMetadata(
            title = null, artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 0, positionMs = 0
        )
        assertEquals("Keep This", updated.title)
        assertEquals("Keep Artist", updated.artist)
        assertEquals("Keep AA", updated.albumArtist)
        assertEquals(1999, updated.year)
        assertEquals(2, updated.albumTrack)
        assertEquals(4, updated.queueTrack)
        assertEquals(10, updated.totalTracks)
    }

    @Test
    fun withMetadata_emptyStringsClearFields() {
        val state = PlaybackState(title = "Song", artist = "Artist", album = "Album", albumArtist = "AA")
        val updated = state.withMetadata(
            title = "", artist = "", albumArtist = "", album = "", artworkUrl = "",
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 0, positionMs = 0
        )
        assertNull(updated.title)
        assertNull(updated.artist)
        assertNull(updated.albumArtist)
        assertNull(updated.album)
    }

    @Test
    fun withMetadata_zeroIntFieldsClearFields() {
        val state = PlaybackState(year = 2024, albumTrack = 3, queueTrack = 5, totalTracks = 12)
        val updated = state.withMetadata(
            title = null, artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = 0, albumTrack = 0, queueTrack = 0, totalTracks = 0,
            durationMs = 0, positionMs = 0
        )
        assertNull(updated.year)
        assertNull(updated.albumTrack)
        assertNull(updated.queueTrack)
        assertNull(updated.totalTracks)
    }

    // --- withClearedMetadata ---

    @Test
    fun withClearedMetadata_clearsAllMetadata() {
        val state = PlaybackState(
            title = "Song", artist = "Artist", albumArtist = "AA", album = "Album",
            artworkUrl = "url", year = 2024, albumTrack = 3, queueTrack = 5, totalTracks = 12,
            durationMs = 180000, positionMs = 5000,
            groupId = "group1"
        )
        val cleared = state.withClearedMetadata()

        assertNull(cleared.title)
        assertNull(cleared.artist)
        assertNull(cleared.albumArtist)
        assertNull(cleared.album)
        assertNull(cleared.artworkUrl)
        assertNull(cleared.year)
        assertNull(cleared.albumTrack)
        assertNull(cleared.queueTrack)
        assertNull(cleared.totalTracks)
        assertEquals(0, cleared.durationMs)
        assertEquals(0, cleared.positionMs)
        assertEquals("group1", cleared.groupId)
    }

    // --- withGroupUpdate ---

    @Test
    fun withGroupUpdate_updatesGroupFields() {
        val state = PlaybackState(title = "Song")
        val updated = state.withGroupUpdate("g1", "Living Room", PlaybackStateType.PLAYING)

        assertEquals("g1", updated.groupId)
        assertEquals("Living Room", updated.groupName)
        assertEquals(PlaybackStateType.PLAYING, updated.playbackState)
        assertEquals("Song", updated.title)
    }

    // --- interpolatedPositionMs ---

    @Test
    fun interpolatedPositionMs_whenPaused_returnsPositionMs() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PAUSED,
            positionMs = 5000,
            positionUpdatedAt = 8_000L,
            durationMs = 180000
        )
        assertEquals(5000L, state.interpolatedPositionMs)
    }

    @Test
    fun interpolatedPositionMs_whenPlaying_addsElapsedTime() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PLAYING,
            positionMs = 5000,
            positionUpdatedAt = 8_000L,
            durationMs = 180000
        )
        assertEquals(7000L, state.interpolatedPositionMs)
    }

    @Test
    fun interpolatedPositionMs_whenPlaying_cappedAtDuration() {
        val state = PlaybackState(
            playbackState = PlaybackStateType.PLAYING,
            positionMs = 179999,
            positionUpdatedAt = 5_000L,
            durationMs = 180000
        )
        assertEquals(180000L, state.interpolatedPositionMs)
    }

    // --- withMetadata positionUpdatedAt behavior (H-29) ---

    @Test
    fun withMetadata_nonZeroPosition_stampsPositionUpdatedAt() {
        val state = PlaybackState()
        val updated = state.withMetadata(
            title = "Song", artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 180000, positionMs = 5000
        )
        // Platform.elapsedRealtimeMs() is mocked to return 10_000L
        assertEquals(10_000L, updated.positionUpdatedAt)
    }

    @Test
    fun withMetadata_zeroPosition_doesNotStampPositionUpdatedAt() {
        // Simulates initial metadata for a new track: positionMs=0 should not
        // stamp positionUpdatedAt, preventing phantom progress in interpolation.
        val state = PlaybackState(positionUpdatedAt = 0L)
        val updated = state.withMetadata(
            title = "New Track", artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 180000, positionMs = 0
        )
        assertEquals(0L, updated.positionUpdatedAt)
    }

    @Test
    fun withMetadata_zeroPosition_preservesExistingTimestamp() {
        // If there was a previous valid timestamp, zero position preserves it
        val state = PlaybackState(positionUpdatedAt = 5_000L)
        val updated = state.withMetadata(
            title = null, artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 0, positionMs = 0
        )
        assertEquals(5_000L, updated.positionUpdatedAt)
    }

    @Test
    fun interpolatedPositionMs_zeroPositionAfterClear_returnsZero() {
        // After withClearedMetadata (positionUpdatedAt=0), a metadata update
        // with positionMs=0 should keep interpolation at 0, not count up.
        val cleared = PlaybackState(
            playbackState = PlaybackStateType.PLAYING
        ).withClearedMetadata()

        val updated = cleared.withMetadata(
            title = "New Track", artist = null, albumArtist = null, album = null, artworkUrl = null,
            year = null, albumTrack = null, queueTrack = null, totalTracks = null,
            durationMs = 180000, positionMs = 0
        )

        // positionUpdatedAt should be 0, so interpolation returns raw positionMs
        assertEquals(0L, updated.positionUpdatedAt)
        assertEquals(0L, updated.interpolatedPositionMs)
    }
}
