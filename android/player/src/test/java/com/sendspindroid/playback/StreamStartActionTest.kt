package com.sendspindroid.playback

import com.sendspindroid.sendspin.protocol.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStartActionTest {

    private val flac48 = StreamConfig("flac", 48000, 2, 16, byteArrayOf(1, 2, 3))

    @Test
    fun `first stream start begins a new stream`() {
        assertEquals(StreamStartAction.NEW_STREAM, StreamStartAction.of(null, flac48))
    }

    @Test
    fun `identical format on an active stream changes nothing`() {
        // A separately parsed message: equal content, different header array.
        val repeat = StreamConfig("flac", 48000, 2, 16, byteArrayOf(1, 2, 3))
        assertEquals(StreamStartAction.UNCHANGED, StreamStartAction.of(flac48, repeat))
    }

    @Test
    fun `changed format on an active stream keeps the stream`() {
        val changes = listOf(
            flac48.copy(codec = "opus", codecHeader = null),
            flac48.copy(sampleRate = 44100),
            flac48.copy(channels = 1),
            flac48.copy(bitDepth = 24),
            flac48.copy(codecHeader = byteArrayOf(9)),
        )
        for (next in changes) {
            assertEquals(next.toString(), StreamStartAction.FORMAT_CHANGE, StreamStartAction.of(flac48, next))
        }
    }
}
