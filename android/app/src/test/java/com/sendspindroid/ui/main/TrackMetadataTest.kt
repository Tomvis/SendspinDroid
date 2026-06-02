package com.sendspindroid.ui.main

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [TrackMetadata] value semantics.
 *
 * Covers the isEmpty property for blank, empty, and populated fields.
 */
class TrackMetadataTest {

    @Test
    fun `isEmpty is true for all empty strings`() {
        val metadata = TrackMetadata("", "", "")
        assertTrue(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for all blank strings`() {
        val metadata = TrackMetadata("   ", "  ", " ")
        assertTrue(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for EMPTY constant`() {
        assertTrue(TrackMetadata.EMPTY.isEmpty)
    }

    @Test
    fun `isEmpty is false when title is set`() {
        val metadata = TrackMetadata("Song Title", "", "")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when artist is set`() {
        val metadata = TrackMetadata("", "Artist Name", "")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when album is set`() {
        val metadata = TrackMetadata("", "", "Album Name")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is false when all fields are set`() {
        val metadata = TrackMetadata("Song", "Artist", "Album")
        assertFalse(metadata.isEmpty)
    }

    @Test
    fun `isEmpty is true for mixed empty and blank`() {
        val metadata = TrackMetadata("", " ", "  ")
        assertTrue(metadata.isEmpty)
    }

    // -----------------------------------------------------------------------
    // mergeTrackMetadata -- Media3 sync frankenmetadata fix
    // -----------------------------------------------------------------------

    @Test
    fun `merge same track preserves null ancillary fields`() {
        // The SendSpin broadcast has already populated queueTrack/totalTracks
        // from the authoritative state/metadata frame. A subsequent Media3
        // refresh (artwork URL change, etc.) fires onMediaMetadataChanged
        // with the same title/artist/album but no queue info -- we keep prev.
        val prev = TrackMetadata(
            "Song", "Artist", "Album",
            albumArtist = "Album Artist", year = 2024,
            albumTrack = 3, queueTrack = 5, totalTracks = 12
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "Song", artist = "Artist", album = "Album"
        )
        assertEquals("Album Artist", merged.albumArtist)
        assertEquals(2024, merged.year)
        assertEquals(3, merged.albumTrack)
        assertEquals(5, merged.queueTrack)
        assertEquals(12, merged.totalTracks)
    }

    @Test
    fun `merge new track clears null ancillary fields`() {
        // Media3 path fires onMediaMetadataChanged for a NEW track. It carries
        // title/artist/album/albumArtist/year/albumTrack but does NOT carry
        // queueTrack/totalTracks. Without this fix, the new track would
        // inherit the previous track's "5 of 12" rail line.
        val prev = TrackMetadata(
            "Old Song", "Old Artist", "Old Album",
            albumArtist = "Old AA", year = 2010,
            albumTrack = 7, queueTrack = 5, totalTracks = 12
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "New Song", artist = "New Artist", album = "New Album",
            albumArtist = "New AA", year = 2024, albumTrack = 1
            // queueTrack and totalTracks deliberately omitted
        )
        assertEquals("New AA", merged.albumArtist)
        assertEquals(2024, merged.year)
        assertEquals(1, merged.albumTrack)
        assertEquals(0, merged.queueTrack)  // cleared
        assertEquals(0, merged.totalTracks) // cleared
    }

    @Test
    fun `merge new track with all explicit nulls clears everything`() {
        // Bare title/artist/album emission with no ancillary data on a new
        // track. Verifies all five aux fields drop to their "absent" sentinels.
        val prev = TrackMetadata(
            "Old", "Old", "Old",
            albumArtist = "Old AA", year = 2010,
            albumTrack = 7, queueTrack = 5, totalTracks = 12
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "New", artist = "New", album = "New"
        )
        assertEquals("", merged.albumArtist)
        assertEquals(0, merged.year)
        assertEquals(0, merged.albumTrack)
        assertEquals(0, merged.queueTrack)
        assertEquals(0, merged.totalTracks)
    }

    @Test
    fun `merge explicit value overrides regardless of track-change`() {
        // SendSpin broadcast path always passes 0/"" explicitly. Verify that
        // an explicit value beats both "preserve prev" and "clear on new".
        val prev = TrackMetadata(
            "Song", "Artist", "Album",
            queueTrack = 5, totalTracks = 12
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "Song", artist = "Artist", album = "Album",
            queueTrack = 3, totalTracks = 8
        )
        assertEquals(3, merged.queueTrack)
        assertEquals(8, merged.totalTracks)
    }

    @Test
    fun `merge from EMPTY initial state clears nulls`() {
        // First metadata emission. prev is EMPTY (all blank/0). isNewTrack=true.
        // Aux fields with no value should be 0/"" -- not inherited from EMPTY
        // (which is also 0/"" -- but the logic must work either way).
        val merged = mergeTrackMetadata(
            prev = TrackMetadata.EMPTY,
            title = "First", artist = "First", album = "First"
        )
        assertEquals("", merged.albumArtist)
        assertEquals(0, merged.queueTrack)
    }

    @Test
    fun `merge detects new track when only title differs`() {
        val prev = TrackMetadata(
            "Song A", "Artist", "Album",
            queueTrack = 5
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "Song B", artist = "Artist", album = "Album"
        )
        assertEquals(0, merged.queueTrack)
    }

    @Test
    fun `merge detects new track when only album differs`() {
        val prev = TrackMetadata(
            "Song", "Artist", "Album X",
            albumTrack = 3
        )
        val merged = mergeTrackMetadata(
            prev = prev,
            title = "Song", artist = "Artist", album = "Album Y"
        )
        assertEquals(0, merged.albumTrack)
    }
}
