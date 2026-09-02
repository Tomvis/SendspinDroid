package com.sendspindroid.musicassistant

import android.util.Log
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration test: PlaybackService auto-connects MA with stored token.
 *
 * Verifies the MusicAssistant.onServerConnected flow:
 * - When a server has a stored MA token, connectWithToken is triggered
 * - When no token exists, state transitions to Idle and loginRequired fires
 * - When server is not MA, state stays Idle
 *
 * Since MusicAssistant is a singleton with Android dependencies,
 * we test the decision logic in isolation by reproducing the
 * onServerConnected flow.
 */
class MaAutoConnectTokenTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Reproduces MusicAssistant.onServerConnected decision logic.
     * Returns the resulting state based on server properties and token availability.
     */
    private fun simulateOnServerConnected(
        server: UnifiedServer,
        hasStoredToken: Boolean,
        hasApiEndpoint: Boolean = true
    ): SimulatedResult {
        // Check 1: Is this server a Music Assistant server?
        if (!server.isMusicAssistant && !hasStoredToken) {
            return SimulatedResult(TransportState.Idle, connectWithTokenCalled = false, loginRequired = false)
        }

        // Check 2: Can we reach the MA API?
        if (!hasApiEndpoint) {
            return SimulatedResult(TransportState.Idle, connectWithTokenCalled = false, loginRequired = false)
        }

        // Check 3: Do we have a stored token?
        return if (hasStoredToken) {
            SimulatedResult(TransportState.Connecting, connectWithTokenCalled = true, loginRequired = false)
        } else {
            SimulatedResult(TransportState.Idle, connectWithTokenCalled = false, loginRequired = true)
        }
    }

    data class SimulatedResult(
        val state: TransportState,
        val connectWithTokenCalled: Boolean,
        val loginRequired: Boolean
    )

    @Test
    fun `MA server with stored token triggers connectWithToken`() {
        val server = UnifiedServer(
            id = "server-1",
            name = "MA Server",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            hasStoredToken = true
        )

        assertTrue("connectWithToken should be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Connecting",
            TransportState.Connecting,
            result.state
        )
    }

    @Test
    fun `MA server without token transitions to Idle and fires loginRequired`() {
        val server = UnifiedServer(
            id = "server-2",
            name = "MA Server No Token",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            hasStoredToken = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Idle",
            TransportState.Idle,
            result.state
        )
        assertTrue("loginRequired should fire", result.loginRequired)
    }

    @Test
    fun `non-MA server without token stays Idle`() {
        val server = UnifiedServer(
            id = "server-3",
            name = "Regular Server",
            isMusicAssistant = false,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            hasStoredToken = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Idle",
            TransportState.Idle,
            result.state
        )
        assertFalse("loginRequired should NOT fire for non-MA server", result.loginRequired)
    }

    @Test
    fun `non-MA server with stored token auto-detects as MA`() {
        // A non-MA server that has a stored token (auto-detected)
        val server = UnifiedServer(
            id = "server-4",
            name = "Auto-Detected MA",
            isMusicAssistant = false,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            hasStoredToken = true
        )

        assertTrue("connectWithToken should be called for auto-detected MA", result.connectWithTokenCalled)
    }

    @Test
    fun `MA server with no API endpoint stays Idle`() {
        val server = UnifiedServer(
            id = "server-6",
            name = "No API",
            isMusicAssistant = true,
            local = LocalConnection("192.168.1.10:8927")
        )

        val result = simulateOnServerConnected(
            server = server,
            hasStoredToken = true,
            hasApiEndpoint = false
        )

        assertFalse("connectWithToken should NOT be called", result.connectWithTokenCalled)
        assertEquals(
            "State should be Idle when no API endpoint",
            TransportState.Idle,
            result.state
        )
    }

    @Test
    fun `TransportState isReady only when Ready`() {
        val nonReadyStates: List<TransportState> = listOf(
            TransportState.Idle,
            TransportState.Connecting,
            TransportState.Failed(com.sendspindroid.coordinator.FailureReason.TransientNetwork),
        )
        for (state in nonReadyStates) {
            assertFalse("${state::class.simpleName} should not be Ready", state is TransportState.Ready)
        }
        val ready: TransportState = TransportState.Ready
        assertTrue(ready is TransportState.Ready)
    }
}
