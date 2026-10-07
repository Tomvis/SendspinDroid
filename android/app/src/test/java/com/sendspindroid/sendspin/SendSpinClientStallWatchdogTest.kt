package com.sendspindroid.sendspin

import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.e2e.E2ETestBase
import io.mockk.verify
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The stall watchdog: while connected, a transport that has delivered nothing
 * for too long is closed so the drop is noticed. Two thresholds, 7 s while a
 * stream is active and 20 s while idle (issue #127).
 */
class SendSpinStallWatchdogTest : E2ETestBase() {

    private fun lastByte(): AtomicLong = getField(client, "lastByteReceivedAtMs")

    private fun silentFor(ms: Long) = lastByte().set(System.currentTimeMillis() - ms)

    private fun checkStall() {
        val m = SendSpin::class.java.getDeclaredMethod("checkStall")
        m.isAccessible = true
        m.invoke(client)
    }

    @Test
    fun `lastByteReceivedAtMs is updated on a handshake text frame`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        silentFor(10_000)

        fakeTransport.simulateRawTextFrame("""{"type":"server/init"}""")

        assertTrue(client.getLastByteReceivedAgoMs() < 5_000)
    }

    @Test
    fun `lastByteReceivedAtMs is updated on binary message`() {
        connectAndHandshake()
        silentFor(10_000)

        fakeServer.sendGroupUpdate()

        assertTrue(client.getLastByteReceivedAgoMs() < 5_000)
    }

    @Test
    fun `checkStall forces transport close when stalled past timeout`() {
        connectAndHandshake()
        fakeServer.sendStreamStart()
        silentFor(60_000)

        checkStall()

        assertTrue("Watchdog should have called transport.close()", fakeTransport.closed)
        assertEquals(1001, fakeTransport.closeCode)
        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `a stalled transport is closed once, not on every watchdog tick`() {
        connectAndHandshake()
        silentFor(60_000)

        checkStall()
        checkStall()  // the next tick finds no connection

        verify(exactly = 1) { mockCallback.onDisconnected(true) }
    }

    @Test
    fun `checkStall does not close when recently active`() {
        connectAndHandshake()
        silentFor(0)

        checkStall()

        assertFalse("Watchdog should NOT close when data was recently received", fakeTransport.closed)
    }

    @Test
    fun `the watchdog runs for each connection`() {
        connectAndHandshake()
        val first: Job? = getField(client, "stallWatchdogJob")
        assertNotNull("the watchdog starts at server hello", first)

        fakeTransport.simulateClosed(1006, "abnormal")
        assertNull("and stops with the connection", getField<Job?>(client, "stallWatchdogJob"))
        assertFalse(first!!.isActive)

        connectAndHandshake()
        val second: Job? = getField(client, "stallWatchdogJob")
        assertTrue("so a second stall is detected", second!!.isActive)
    }

    @Test
    fun `checkStall does not close during idle when recently active (under idle threshold)`() {
        // Idle threshold is 20s; 15s stale should NOT trip. Issue #127: the idle
        // watchdog uses a longer threshold than streaming to accommodate the
        // TimeSyncManager burst cadence.
        connectAndHandshake()
        silentFor(15_000)

        checkStall()

        assertFalse("Watchdog should NOT close during idle when within the idle threshold",
            fakeTransport.closed)
    }

    @Test
    fun `checkStall closes during idle when past idle threshold`() {
        // Idle threshold is 20s; 25s stale should trip even with no stream active. Issue #127.
        connectAndHandshake()
        silentFor(25_000)

        checkStall()

        assertTrue("Watchdog should close during idle when past the 20s idle threshold",
            fakeTransport.closed)
        assertEquals(1001, fakeTransport.closeCode)
    }

    @Test
    fun `checkStall does not close streaming within streaming threshold`() {
        // Streaming threshold is 7s; 5s stale should NOT trip. Regression guard against
        // accidental tightening of the streaming threshold.
        connectAndHandshake()
        fakeServer.sendStreamStart()
        silentFor(5_000)

        checkStall()

        assertFalse("Watchdog should NOT close while streaming within the 7s threshold",
            fakeTransport.closed)
    }

    @Test
    fun `stream clear keeps the streaming threshold`() {
        // A skip clears the buffers and the audio carries on: 10s of silence
        // after it is a stall, not an idle connection.
        connectAndHandshake()
        fakeServer.sendStreamStart()
        fakeTransport.simulateTextMessage("""{"type":"stream/clear","payload":{}}""")
        silentFor(10_000)

        checkStall()

        assertTrue("stream/clear must not move the watchdog to the idle threshold",
            fakeTransport.closed)
    }

    @Test
    fun `stream end returns to the idle threshold`() {
        connectAndHandshake()
        fakeServer.sendStreamStart()
        fakeServer.sendStreamEnd()
        silentFor(10_000)

        checkStall()

        assertFalse("10s of silence is normal with no stream", fakeTransport.closed)
    }
}
