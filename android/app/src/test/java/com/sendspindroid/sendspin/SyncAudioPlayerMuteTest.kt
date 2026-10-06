package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.audio.FakeAudioSink
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mute is output gain on the sink, independent of the device volume
 * (roles/player/v1.md: "volume and muted are independent").
 */
class SyncAudioPlayerMuteTest {

    private val sink = FakeAudioSink()
    private val player = SyncAudioPlayer(
        timeFilter = mockk(relaxed = true),
        sampleRate = 48_000,
        channels = 2,
        bitDepth = 16,
        sinkFactory = { _, _, _, _ -> sink },
    )

    @Test
    fun `mute silences the sink and unmute restores it`() {
        player.initialize()

        player.setMuted(true)
        assertEquals(0f, sink.volume)

        player.setMuted(false)
        assertEquals(1f, sink.volume)
    }

    @Test
    fun `mute set before the sink exists applies when it is created`() {
        player.setMuted(true)

        player.initialize()

        assertEquals(0f, sink.volume)
    }
}
