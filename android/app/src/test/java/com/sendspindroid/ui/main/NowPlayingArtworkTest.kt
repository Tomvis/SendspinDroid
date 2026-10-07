package com.sendspindroid.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingArtworkTest {

    private val thumb = byteArrayOf(1, 2, 3)
    private val fullRes = byteArrayOf(4, 5, 6, 7)

    @Test
    fun `imageproxy thumbnail is asked for the display size`() {
        assertEquals(
            "http://10.0.0.118:8095/imageproxy/abc?size=1024&fmt=jpeg",
            nowPlayingArtworkUrl("http://10.0.0.118:8095/imageproxy/abc?size=512&fmt=jpeg"),
        )
        assertEquals(
            "http://ma/imageproxy?path=x&provider=p&size=1024",
            nowPlayingArtworkUrl("http://ma/imageproxy?path=x&provider=p&size=512"),
        )
    }

    @Test
    fun `other urls are left alone`() {
        val cdn = "https://cdn.example.com/cover.jpg?size=300"
        assertEquals(cdn, nowPlayingArtworkUrl(cdn))
        val unsized = "http://ma/imageproxy/abc"
        assertEquals(unsized, nowPlayingArtworkUrl(unsized))
    }

    @Test
    fun `an http artwork uri wins over the session thumbnail`() {
        assertEquals(
            ArtworkSource.Url("http://ma/imageproxy/abc?size=1024"),
            nowPlayingArtworkSource(thumb, "http://ma/imageproxy/abc?size=512", fullRes),
        )
    }

    @Test
    fun `it matches the extras url so the two paths do not flip-flop`() {
        val url = "http://ma/imageproxy/abc?size=512"
        assertEquals(
            ArtworkSource.Url(nowPlayingArtworkUrl(url)),
            nowPlayingArtworkSource(thumb, url, null),
        )
    }

    @Test
    fun `binary artwork prefers the unscaled bytes`() {
        val source = nowPlayingArtworkSource(thumb, null, fullRes) as ArtworkSource.ByteArray
        assertSame(fullRes, source.data)
    }

    @Test
    fun `binary artwork falls back to the session thumbnail`() {
        val source = nowPlayingArtworkSource(thumb, null, null) as ArtworkSource.ByteArray
        assertSame(thumb, source.data)
    }

    @Test
    fun `nothing to show is null`() {
        assertNull(nowPlayingArtworkSource(null, null, fullRes))
        assertNull(nowPlayingArtworkSource(byteArrayOf(), "", null))
    }
}
