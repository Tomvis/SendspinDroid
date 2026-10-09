package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a connection can make the app do before the server has said what it
 * is for, and for roles it was never given.
 *
 * "The server MUST NOT send other Sendspin messages until it sends the
 * initial server/activate", and only a playback-capable connection may carry
 * active roles. A server that connects to the listener needs nothing but the
 * published Sentinel key to get as far as `server/hello`, so neither rule can
 * be left to the server to keep.
 */
class UnactivatedConnectionTest : E2ETestBase() {

    private val setOutputDelay =
        """{"type":"server/command","payload":{"player":{"command":"set_output_delay","output_delay_ms":3000}}}"""
    private val setVolume =
        """{"type":"server/command","payload":{"player":{"command":"volume","volume":100}}}"""
    private val unmute =
        """{"type":"server/command","payload":{"player":{"command":"mute","mute":false}}}"""
    private val serverState = """{"type":"server/state","payload":{"metadata":{"timestamp":0,"title":"T"},""" +
        """"controller":{"supported_commands":["play"],"volume":10,"muted":false}}}"""
    private val syncOffset =
        """{"type":"client/sync_offset","payload":{"offset_ms":2500,"source":"server"}}"""

    override fun configureUserSettings() {
        every { UserSettings.setOutputDelayMs(any()) } returns true
    }

    /** Everything a server could send to reach the audio, the display or the settings. */
    private fun sendEverything() {
        fakeTransport.simulateTextMessage(setOutputDelay)
        fakeTransport.simulateTextMessage(setVolume)
        fakeTransport.simulateTextMessage(unmute)
        fakeTransport.simulateTextMessage(serverState)
        fakeTransport.simulateTextMessage(syncOffset)
        fakeServer.sendGroupUpdate()
        fakeServer.sendStreamStart()
        fakeServer.sendArtworkStreamStart()
        fakeServer.sendAudioChunk(timestampMicros = 1_000_000L, audioData = fakeServer.generateSilence(20))
        fakeServer.sendArtwork(channel = 0, imageData = ByteArray(10))
        fakeTransport.simulateTextMessage("""{"type":"stream/clear","payload":{}}""")
        fakeTransport.simulateTextMessage("""{"type":"stream/end","payload":{}}""")
    }

    private fun connectAsFarAsHello() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeServer.sendServerHello()
        assertTrue(fakeTransport.hasSentMessageContaining("client/hello"))
        clearMocks(mockCallback, answers = false)
    }

    @Test
    fun `nothing sent before the initial activation is acted on`() {
        connectAsFarAsHello()

        sendEverything()

        verify(exactly = 0) { UserSettings.setOutputDelayMs(any()) }
        assertEquals(0.0, client.getTimeFilter().outputDelayMs, 0.0)
        assertEquals(0.0, client.getTimeFilter().staticDelayMs, 0.0)
        verify { mockCallback wasNot io.mockk.Called }
        // Not a protocol error the spec asks to close for, and not answered.
        assertFalse(fakeTransport.closed)
        assertEquals(listOf("client/init", "client/hello"), sentTypes())
    }

    @Test
    fun `a connection activated without roles cannot play, command or display`() {
        connectAsFarAsHello()
        fakeServer.sendServerActivate(activities = emptyList(), activeRoles = emptyList())
        assertTrue(fakeTransport.hasSentMessageContaining("client/state"))
        clearMocks(mockCallback, answers = false)

        sendEverything()

        verify(exactly = 0) { UserSettings.setOutputDelayMs(any()) }
        assertEquals(0.0, client.getTimeFilter().outputDelayMs, 0.0)
        verify(exactly = 0) { mockCallback.onStreamStart(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockCallback.onAudioChunk(any(), any()) }
        verify(exactly = 0) { mockCallback.onStreamClear() }
        verify(exactly = 0) { mockCallback.onStreamEnd() }
        verify(exactly = 0) { mockCallback.onVolumeChanged(any()) }
        verify(exactly = 0) { mockCallback.onMutedChanged(any()) }
        verify(exactly = 0) { mockCallback.onMetadataUpdate(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockCallback.onArtwork(any()) }
        assertEquals(null, client.controllerState.value)
        assertFalse(fakeTransport.closed)
    }

    @Test
    fun `the same messages are acted on once the roles are active`() {
        connectAndHandshake()
        fakeServer.sendServerActivate(
            activities = listOf("playback"),
            activeRoles = listOf("player@v1", "controller@v1", "metadata@v1", "artwork@v1"),
        )

        sendEverything()

        verify(exactly = 1) { UserSettings.setOutputDelayMs(3000) }
        verify(exactly = 1) { mockCallback.onStreamStart(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { mockCallback.onAudioChunk(1_000_000L, any()) }
        verify(exactly = 1) { mockCallback.onVolumeChanged(100) }
        verify(exactly = 1) { mockCallback.onMutedChanged(false) }
    }

    private fun sentTypes(): List<String> = fakeTransport.sentTextMessages.map {
        it.substringAfter("\"type\":\"").substringBefore('"')
    }
}
