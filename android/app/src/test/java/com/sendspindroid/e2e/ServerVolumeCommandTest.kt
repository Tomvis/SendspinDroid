package com.sendspindroid.e2e

import io.mockk.verify
import org.junit.Test

/**
 * A `server/command` carrying a volume must reach the client callback.
 *
 * Filed as #255: "server volume pushes move the slider but never change device
 * volume". The reported evidence is four abridged log lines, and reading the
 * code the path looks intact - `handleServerCommand` -> `onVolumeCommand` ->
 * `SendSpin` -> `callback.onVolumeChanged` -> `PlaybackService.setVolume` ->
 * `AudioManager.setStreamVolume`.
 *
 * This pins the protocol half of that chain, so a future regression there is
 * caught and the remaining uncertainty is narrowed to the service half, which
 * this suite cannot reach (the service is not instantiated by these tests).
 */
class ServerVolumeCommandTest : E2ETestBase() {

    private fun send(json: String) {
        fakeTransport.simulateTextMessage(json)
    }

    @Test
    fun `a server volume command reaches the client callback`() {
        connectAndHandshake()
        send("""{"type":"server/command","payload":{"player":{"command":"volume","volume":26}}}""")
        verify(exactly = 1) { mockCallback.onVolumeChanged(26) }
    }

    @Test
    fun `volume zero is delivered and not treated as absent`() {
        connectAndHandshake()
        send("""{"type":"server/command","payload":{"player":{"command":"volume","volume":0}}}""")
        verify(exactly = 1) { mockCallback.onVolumeChanged(0) }
    }

    @Test
    fun `volume 100 is delivered`() {
        connectAndHandshake()
        send("""{"type":"server/command","payload":{"player":{"command":"volume","volume":100}}}""")
        verify(exactly = 1) { mockCallback.onVolumeChanged(100) }
    }

    /** Out-of-range values are rejected by the parser rather than clamped. */
    @Test
    fun `an out-of-range volume is ignored`() {
        connectAndHandshake()
        send("""{"type":"server/command","payload":{"player":{"command":"volume","volume":150}}}""")
        verify(exactly = 0) { mockCallback.onVolumeChanged(any()) }
    }

    @Test
    fun `a mute command reaches the callback`() {
        connectAndHandshake()
        send("""{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}""")
        verify(exactly = 1) { mockCallback.onMutedChanged(true) }
    }
}
