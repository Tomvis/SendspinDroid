package com.sendspindroid.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.protocol.ControllerState
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SendSpinPlayer] against the server's controller state: the media session
 * offers a command only while the server lists it in `supported_commands`,
 * seek goes out as `seek` / `seek_relative`, and repeat and shuffle report
 * what the server says.
 *
 * Robolectric, because Player.Commands is backed by a framework class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendSpinPlayerControllerStateTest {

    private val player = SendSpinPlayer()

    private val serverCommands = listOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SHUFFLE_MODE,
    )

    private fun offered() = serverCommands.filter { player.isCommandAvailable(it) }

    @Test
    fun `no server command is offered before a controller state arrives`() {
        assertEquals(emptyList<Int>(), offered())

        // The role was dropped: the handler publishes an empty state.
        player.updateControllerState(ControllerState())
        assertEquals(emptyList<Int>(), offered())

        // What the player does locally stays available throughout.
        assertTrue(player.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(player.isCommandAvailable(Player.COMMAND_GET_METADATA))
    }

    @Test
    fun `offered commands follow supported_commands`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        player.addListener(listener)

        player.updateControllerState(ControllerState(supportedCommands = listOf("play", "pause", "next")))
        assertEquals(listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT), offered())
        verify(exactly = 1) { listener.onAvailableCommandsChanged(any()) }

        // A change that leaves the commands alone is not announced.
        player.updateControllerState(
            ControllerState(supportedCommands = listOf("play", "pause", "next"), volume = 40)
        )
        verify(exactly = 1) { listener.onAvailableCommandsChanged(any()) }

        player.updateControllerState(
            ControllerState(
                supportedCommands = listOf(
                    "previous", "seek", "seek_relative", "repeat_all", "shuffle", "unshuffle"
                ),
                seekMaxMs = 200_000,
            )
        )
        assertEquals(serverCommands - Player.COMMAND_PLAY_PAUSE - Player.COMMAND_SEEK_TO_NEXT, offered())
        verify(exactly = 2) { listener.onAvailableCommandsChanged(any()) }

        player.updateControllerState(null)
        assertEquals(emptyList<Int>(), offered())
    }

    @Test
    fun `seek is offered only with a seek_max_ms and makes the item seekable`() {
        player.updateMediaItem("Title", "Artist", "Album", 200_000)

        player.updateControllerState(ControllerState(supportedCommands = listOf("seek")))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.isCurrentMediaItemSeekable)

        player.updateControllerState(
            ControllerState(supportedCommands = listOf("seek"), seekMaxMs = 200_000)
        )
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertTrue(player.isCurrentMediaItemSeekable)
        assertTrue(player.currentTimeline.getWindow(0, Timeline.Window()).isSeekable)
    }

    @Test
    fun `seekTo sends seek and seekBack and seekForward send seek_relative`() {
        val client = mockk<SendSpin>(relaxed = true)
        player.setSendSpinClient(client)

        player.seekTo(42_000)
        player.seekBack()
        player.seekForward()
        // "The default position" is not a position to send.
        player.seekTo(0, C.TIME_UNSET)

        verify(exactly = 1) { client.seek(42_000) }
        verify(exactly = 1) { client.seek(any()) }
        verify(exactly = 1) { client.seekRelative(-10_000) }
        verify(exactly = 1) { client.seekRelative(10_000) }
        verify(exactly = 0) { client.previous() }
        verify(exactly = 0) { client.next() }
    }

    @Test
    fun `repeat and shuffle send commands and report the server state`() {
        val client = mockk<SendSpin>(relaxed = true)
        val listener = mockk<Player.Listener>(relaxed = true)
        player.setSendSpinClient(client)
        player.addListener(listener)

        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = true

        verify(exactly = 1) { client.setRepeatMode("all") }
        verify(exactly = 1) { client.setShuffle(true) }
        // Nothing changes until the server says so.
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        assertFalse(player.shuffleModeEnabled)

        player.updateControllerState(ControllerState(repeat = "all", shuffle = true))

        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertTrue(player.shuffleModeEnabled)
        verify(exactly = 1) { listener.onRepeatModeChanged(Player.REPEAT_MODE_ALL) }
        verify(exactly = 1) { listener.onShuffleModeEnabledChanged(true) }
    }
}
