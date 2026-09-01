package com.sendspindroid.e2e

import com.sendspindroid.playback.SendSpinPlayer
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import com.sendspindroid.sendspin.PlaybackState as SyncPlaybackState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * E2E Test 3: SendSpinPlayer transport forwarding and playback state
 *
 * SendSpin has no queue state, so SendSpinPlayer exposes a single-item timeline
 * only. This covers what remains testable at the Player level: forwarding
 * next/previous transport commands to the client, and reflecting SyncAudioPlayer
 * state changes through the Player interface.
 */
class BrowseMaLibraryQueueTest : E2ETestBase() {

    private lateinit var player: SendSpinPlayer
    private lateinit var mockSyncAudioPlayer: SyncAudioPlayer

    override fun setUp() {
        super.setUp()
        player = SendSpinPlayer()
        mockSyncAudioPlayer = mockk(relaxed = true)

        // Connect the player to the client
        player.setSendSpinClient(client)
    }

    @Test
    fun `seekToNext sends next command to client`() {
        connectAndHandshake()
        player.setSendSpinClient(client)

        player.seekToNext()

        // Should have sent a "next" command via the transport
        assertTrue(
            "seekToNext should send next command",
            fakeTransport.hasSentMessageContaining("next")
        )
    }

    @Test
    fun `seekToPrevious sends previous command to client`() {
        connectAndHandshake()
        player.setSendSpinClient(client)

        player.seekToPrevious()

        assertTrue(
            "seekToPrevious should send previous command",
            fakeTransport.hasSentMessageContaining("previous")
        )
    }

    @Test
    fun `playback state updates correctly when sync player set`() {
        every { mockSyncAudioPlayer.getPlaybackState() } returns SyncPlaybackState.PLAYING

        player.setSyncAudioPlayer(mockSyncAudioPlayer)

        assertTrue("Should be playing", player.isPlaying)
        assertEquals(
            androidx.media3.common.Player.STATE_READY,
            player.playbackState
        )
    }
}
