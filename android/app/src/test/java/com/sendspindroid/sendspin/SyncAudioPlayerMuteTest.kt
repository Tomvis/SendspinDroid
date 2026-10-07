package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.SyncAudioPlayer.MuteReason
import com.sendspindroid.sendspin.audio.FakeAudioSink
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mute is output gain on the sink, independent of the device volume
 * (roles/player/v1.md: "volume and muted are independent"). There is one
 * mute, held for any number of independent reasons.
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
    fun `every reason silences the sink and clearing it restores it`() {
        player.initialize()

        for (reason in MuteReason.values()) {
            player.setMuted(reason, true)
            assertEquals("$reason", 0f, sink.volume)

            player.setMuted(reason, false)
            assertEquals("$reason", 1f, sink.volume)
        }
    }

    @Test
    fun `mute set before the sink exists applies when it is created`() {
        player.setMuted(MuteReason.INTERRUPTION, true)

        player.initialize()

        assertEquals(0f, sink.volume)
    }

    @Test
    fun `interruption ending leaves the player mute in force`() {
        player.initialize()
        player.setMuted(MuteReason.PLAYER, true)
        player.setMuted(MuteReason.INTERRUPTION, true)

        player.setMuted(MuteReason.INTERRUPTION, false)

        assertEquals(0f, sink.volume)
    }

    @Test
    fun `player un-mute during an interruption stays silent until it ends`() {
        player.initialize()
        player.setMuted(MuteReason.PLAYER, true)
        player.setMuted(MuteReason.INTERRUPTION, true)

        player.setMuted(MuteReason.PLAYER, false)
        assertEquals(0f, sink.volume)

        player.setMuted(MuteReason.INTERRUPTION, false)
        assertEquals(1f, sink.volume)
    }

    @Test
    fun `repeating a mute or an un-mute changes nothing`() {
        player.initialize()

        player.setMuted(MuteReason.INTERRUPTION, true)
        player.setMuted(MuteReason.INTERRUPTION, true)
        player.setMuted(MuteReason.INTERRUPTION, false)
        assertEquals(1f, sink.volume)

        player.setMuted(MuteReason.SYNC, false)
        assertEquals(1f, sink.volume)
    }
}
