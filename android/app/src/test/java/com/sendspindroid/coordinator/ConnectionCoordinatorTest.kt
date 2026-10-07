package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.ProxyConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    @Test
    fun `sessionState combines current server and sendSpin state`() = runTest {
        val server = MutableStateFlow<UnifiedServer?>(null)
        val sendSpin = MutableStateFlow<TransportState>(TransportState.Idle)

        val coordinator = ConnectionCoordinator(
            currentServerFlow = server,
            sendSpinStateFlow = sendSpin,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            connectAttempt = { _, _ -> false },
            context = mockk(relaxed = true),
        )

        // Initial state
        assertEquals(SessionState(), coordinator.sessionState.first())

        // Transitions on each input flow propagate
        sendSpin.value = TransportState.Ready
        testScheduler.runCurrent()

        val combined = coordinator.sessionState.first()
        assertEquals(TransportState.Ready, combined.sendSpin)
    }

    @Test
    fun `connect reports Attempting before it returns`() = runTest {
        val coordinator = makeCoordinatorForRetryTest(connectAttempt = { _, _ -> false })

        coordinator.connect(makeTestServerWithLocal())

        // Nothing has been scheduled yet: whoever started the loop can rely
        // on the status straight away.
        assertEquals(
            ReconnectStatus.Attempting("test-local", attempt = 1, method = null),
            coordinator.reconnectStatus.value,
        )
        coordinator.cancelReconnect()
    }

    @Test
    fun `connect succeeds on first method and emits Attempting then Succeeded`() = runTest {
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ -> true },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceUntilIdle()

        // Reconnect loop completed: status should be Succeeded.
        val status = coordinator.reconnectStatus.value
        assertTrue("Expected Succeeded but got $status", status is ReconnectStatus.Succeeded)
    }

    @Test
    fun `connect retries the sole Local method and never falls through to remote or proxy`() = runTest {
        val attemptedMethods = mutableListOf<ConnectionType>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, method ->
                attemptedMethods.add(method)
                false
            },
        )

        coordinator.connect(makeTestServerWithAllMethods())
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()

        // The priority list is now [LOCAL] alone, so a server offering all
        // three methods must still only ever see LOCAL attempted.
        assertEquals(listOf(ConnectionType.LOCAL), attemptedMethods)
        // The loop does not end by itself any more.
        coordinator.cancelReconnect()
    }

    @Test
    fun `the wait between attempts grows to a minute and stays there`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attemptTimes.add(testScheduler.currentTime)
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceTimeBy(5 * 60_000L)
        testScheduler.runCurrent()

        val waits = listOf(attemptTimes.first()) + attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(
            listOf(500L, 1000L, 2000L, 4000L, 8000L, 15000L, 30000L, 60000L, 60000L, 60000L),
            waits.take(10),
        )
        coordinator.cancelReconnect()
    }

    @Test
    fun `the loop has no attempt cap`() = runTest {
        var attempts = 0
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attempts++
                false
            },
        )

        // A server that stays away for a day is still being tried, once a
        // minute, at the end of it.
        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceTimeBy(24 * 60 * 60_000L)
        testScheduler.runCurrent()

        assertTrue("expected about 1440 attempts in a day, got $attempts", attempts > 1400)
        val status = coordinator.reconnectStatus.value
        assertTrue("Expected still Attempting but got $status", status is ReconnectStatus.Attempting)

        coordinator.cancelReconnect()
    }

    @Test
    fun `an attempt that succeeds after many failures ends the loop`() = runTest {
        var attempts = 0
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ -> ++attempts == 20 },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceUntilIdle()

        assertEquals(20, attempts)
        assertEquals(ReconnectStatus.Succeeded("test-local"), coordinator.reconnectStatus.value)
    }

    @Test
    fun `retryNow skips the rest of the backoff`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attemptTimes.add(testScheduler.currentTime)
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        // Well into the one-minute waits: the last attempt was at 2:00.5 and
        // the next is not due until 3:00.5.
        testScheduler.advanceTimeBy(130_000L)
        testScheduler.runCurrent()
        val before = attemptTimes.size

        // The server is seen again on mDNS.
        coordinator.retryNow()
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()

        assertEquals("one attempt, right away", before + 1, attemptTimes.size)
        assertEquals(130_500L, attemptTimes.last())
        coordinator.cancelReconnect()
    }

    @Test
    fun `retryNow in quick succession retries once`() = runTest {
        var attempts = 0
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attempts++
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceTimeBy(130_000L)
        testScheduler.runCurrent()
        val before = attempts

        // An mDNS announcement usually arrives more than once.
        coordinator.retryNow()
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()
        coordinator.retryNow()
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()

        assertEquals(before + 1, attempts)
        coordinator.cancelReconnect()
    }

    @Test
    fun `retryNow does nothing when no loop is running`() = runTest {
        var attempts = 0
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attempts++
                false
            },
        )

        coordinator.retryNow()
        testScheduler.advanceUntilIdle()

        assertEquals(0, attempts)
        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.value)
    }

    @Test
    fun `cancelReconnect stops the loop and emits Idle`() = runTest {
        val coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                kotlinx.coroutines.delay(60_000)  // never returns within test
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()
        coordinator.cancelReconnect()
        testScheduler.advanceUntilIdle()

        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.value)
    }

    @Test
    fun `cancelReconnect from inside an attempt ends the loop`() = runTest {
        var attempts = 0
        lateinit var coordinator: ConnectionCoordinator
        coordinator = makeCoordinatorForRetryTest(
            connectAttempt = { _, _ ->
                attempts++
                coordinator.cancelReconnect()
                false
            },
        )

        coordinator.connect(makeTestServerWithLocal())
        testScheduler.advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(ReconnectStatus.Idle, coordinator.reconnectStatus.value)
    }

    private fun TestScope.makeCoordinatorForRetryTest(
        connectAttempt: suspend (UnifiedServer, ConnectionType) -> Boolean,
    ): ConnectionCoordinator {
        return ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            connectAttempt = connectAttempt,
            context = mockk(relaxed = true),
        )
    }

    private fun makeTestServerWithLocal(): UnifiedServer {
        return UnifiedServer(
            id = "test-local",
            name = "Test Local",
            local = LocalConnection(address = "192.168.1.100:8095"),
        )
    }

    private fun makeTestServerWithAllMethods(): UnifiedServer {
        return UnifiedServer(
            id = "test-all",
            name = "Test All Methods",
            local = LocalConnection(address = "192.168.1.100:8095"),
            remote = RemoteConnection(remoteId = "ABCDE12345"),
            proxy = ProxyConnection(url = "wss://proxy.example.com", authToken = "token123"),
        )
    }
}
