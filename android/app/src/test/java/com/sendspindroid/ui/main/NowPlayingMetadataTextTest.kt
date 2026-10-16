package com.sendspindroid.ui.main

import com.sendspindroid.ui.adaptive.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text of Now Playing's artist / album line, the album artist it shows
 * under that line, and the layouts with room for it (#215).
 */
class NowPlayingMetadataTextTest {

    @Test
    fun `artist, album and year read as the artist, a bullet, then the album and (1975)`() {
        assertEquals(
            "Queen \u2022 A Night at the Opera (1975)",
            buildMetadataString("Queen", "A Night at the Opera", 1975)
        )
    }

    @Test
    fun `without a year the line is as it was`() {
        assertEquals("Queen \u2022 A Night at the Opera", buildMetadataString("Queen", "A Night at the Opera", null))
        assertEquals("Queen", buildMetadataString("Queen", "", null))
        assertEquals("A Night at the Opera", buildMetadataString("", "A Night at the Opera", null))
        assertEquals("", buildMetadataString("", "", null))
    }

    @Test
    fun `a year without an album is its own segment`() {
        assertEquals("Queen \u2022 1975", buildMetadataString("Queen", "", 1975))
    }

    @Test
    fun `a year alone`() {
        assertEquals("1975", buildMetadataString("", "", 1975))
    }

    @Test
    fun `a year of 0 or less is not shown`() {
        // aiosendspin accepts a year of 0, which is no year in YYYY format
        assertEquals("Queen \u2022 Singles", buildMetadataString("Queen", "Singles", 0))
        assertEquals("Queen", buildMetadataString("Queen", "", 0))
        assertEquals("", buildMetadataString("", "", -1))
    }

    @Test
    fun `an album artist that differs from the artist is shown`() {
        assertEquals(
            "Various Artists",
            albumArtistToShow(TrackMetadata("Bohemian Rhapsody", "Queen", "Now 44", albumArtist = " Various Artists "))
        )
    }

    @Test
    fun `an album artist equal to the artist is not shown, whatever its case and surrounding space`() {
        assertNull(albumArtistToShow(TrackMetadata("Love of My Life", "Queen", "A Night at the Opera", albumArtist = "Queen")))
        assertNull(albumArtistToShow(TrackMetadata("Love of My Life", "Queen", "A Night at the Opera", albumArtist = "  queen ")))
        assertNull(albumArtistToShow(TrackMetadata("Love of My Life", " QUEEN", "A Night at the Opera", albumArtist = "Queen")))
    }

    @Test
    fun `a blank or absent album artist is not shown`() {
        assertNull(albumArtistToShow(TrackMetadata("Radio Edit", "Queen", "Singles")))
        assertNull(albumArtistToShow(TrackMetadata("Radio Edit", "Queen", "Singles", albumArtist = "")))
        assertNull(albumArtistToShow(TrackMetadata("Radio Edit", "", "Singles", albumArtist = "   ")))
    }

    @Test
    fun `a TV has room for the album artist`() {
        assertTrue(showsAlbumArtist(FormFactor.TV, screenHeightDp = 540))
    }

    @Test
    fun `a tablet at least 600dp tall has room for the album artist`() {
        assertTrue(showsAlbumArtist(FormFactor.TABLET_7, screenHeightDp = 600))
        assertTrue(showsAlbumArtist(FormFactor.TABLET_10, screenHeightDp = 800))
    }

    @Test
    fun `a phone on its side, as wide as a tablet but shorter, has no room`() {
        assertFalse(showsAlbumArtist(FormFactor.TABLET_7, screenHeightDp = 360))
        assertFalse(showsAlbumArtist(FormFactor.TABLET_10, screenHeightDp = 599))
    }

    @Test
    fun `a phone and a head unit have no room, however tall`() {
        assertFalse(showsAlbumArtist(FormFactor.PHONE, screenHeightDp = 891))
        assertFalse(showsAlbumArtist(FormFactor.HEADUNIT, screenHeightDp = 1280))
    }
}
