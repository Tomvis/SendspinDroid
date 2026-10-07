package com.sendspindroid.sendspin

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinStallWatchdogTest {

    private lateinit var mockCallback: SendSpin.Callback
    private lateinit var client: SendSpin
    private lateinit var fakeTransport: FakeTransport

    private class FakeTransport : SendSpinTransport {
        var closeCalled = false
        var closeCode: Int = -1
        var closedReports = 0
        private var listener: SendSpinTransport.Listener? = null
        override var state = TransportState.Connected
        override fun connect() {}
        override fun send(text: String) = true
        override fun send(bytes: ByteArray) = true
        override fun setListener(listener: SendSpinTransport.Listener?) {
            this.listener = listener
        }
        // As the real transport: a local close of a live connection ends it
        // and reports onClosed, once.
        override fun close(code: Int, reason: String) {
            closeCalled = true
            closeCode = code
            if (state != TransportState.Connected) return
            state = TransportState.Closed
            closedReports++
            listener?.onClosed(code, reason)
        }
        override fun destroy() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-id"
        every { UserSettings.getPreferredCodec() } returns "opus"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true

        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockCallback = mockk(relaxed = true)

        client = SendSpin("TestDevice", mockCallback)
        fakeTransport = FakeTransport()

        // Put client in a "connected + handshake complete" state
        val addrField = SendSpin::class.java.getDeclaredField("serverAddress")
        addrField.isAccessible = true
        addrField.set(client, "127.0.0.1:8080")

        val transportField = SendSpin::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, fakeTransport)

        val handshakeField = SendSpin::class.java.superclass.getDeclaredField("handshakeComplete")
        handshakeField.isAccessible = true
        handshakeField.set(client, true)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `lastByteReceivedAtMs is updated on text message`() {
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong

        val before = atomicLong.get()
        Thread.sleep(10)

        val listener = buildTransportListener()
        listener.onMessage("{\"type\":\"ping\"}")

        val after = atomicLong.get()
        assertTrue("lastByteReceivedAtMs should advance on text message (before=$before after=$after)",
            after > before)
    }

    @Test
    fun `lastByteReceivedAtMs is updated on binary message`() {
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong

        val before = atomicLong.get()
        Thread.sleep(10)

        val listener = buildTransportListener()
        listener.onMessage(byteArrayOf(0, 1, 2, 3))

        val after = atomicLong.get()
        assertTrue("lastByteReceivedAtMs should advance on binary message (before=$before after=$after)",
            after > before)
    }

    @Test
    fun `checkStall forces transport close when stalled past timeout`() {
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis() - 60_000L)  // 60s in the past
        val streamActiveField = SendSpin::class.java.getDeclaredField("streamActive")
        streamActiveField.isAccessible = true
        (streamActiveField.get(client) as AtomicBoolean).set(true)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertTrue("Watchdog should have called transport.close()", fakeTransport.closeCalled)
        assertNotEquals(1000, fakeTransport.closeCode)  // non-1000 triggers reconnect
    }

    @Test
    fun `a stalled transport is closed once, not on every watchdog tick`() {
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        (lastByteField.get(client) as AtomicLong).set(System.currentTimeMillis() - 60_000L)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)
        fakeTransport.closeCalled = false
        checkStall.invoke(client)  // the next tick sees a closed transport

        assertFalse("a closed transport must not be closed again", fakeTransport.closeCalled)
        assertEquals(1, fakeTransport.closedReports)
    }

    @Test
    fun `checkStall does not close when recently active`() {
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis())  // just now

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertFalse("Watchdog should NOT close when data was recently received", fakeTransport.closeCalled)
    }

    @Test
    fun `watchdog restarts after onHandshakeComplete so a second stall is detected`() {
        // Simulate the state after a drop: stop the watchdog, then fire
        // onHandshakeComplete and verify the watchdog job is active again.

        // Force-start the watchdog (as if from a prior connect), then stop it.
        val startWatchdog = SendSpin::class.java.getDeclaredMethod("startStallWatchdog")
        startWatchdog.isAccessible = true
        startWatchdog.invoke(client)

        val stopWatchdog = SendSpin::class.java.getDeclaredMethod("stopStallWatchdog")
        stopWatchdog.isAccessible = true
        stopWatchdog.invoke(client)

        // Verify the job is null after stop
        val jobField = SendSpin::class.java.getDeclaredField("stallWatchdogJob")
        jobField.isAccessible = true
        assertNull("stallWatchdogJob should be null after stop", jobField.get(client))

        // Now simulate onHandshakeComplete firing - this is what happens after a
        // reconnect succeeds. We call it through the superclass since the method is
        // declared on SendSpinProtocolHandler and overridden in SendSpin.
        val handshakeMethod = SendSpin::class.java.getDeclaredMethod(
            "onHandshakeComplete", String::class.java, String::class.java
        )
        handshakeMethod.isAccessible = true
        handshakeMethod.invoke(client, "TestServer", "test-server-id")

        // Verify the watchdog job exists again and is active
        val newJob = jobField.get(client) as? kotlinx.coroutines.Job
        assertNotNull("stallWatchdogJob should be restarted by onHandshakeComplete", newJob)
        assertTrue("stallWatchdogJob should be active after onHandshakeComplete",
            newJob!!.isActive)
    }

    @Test
    fun `checkStall does not close during idle when recently active (under idle threshold)`() {
        // Idle threshold is 20s; 15s stale should NOT trip. Issue #127: idle watchdog
        // uses a longer threshold than streaming to accommodate TimeSyncManager burst
        // cadence, but still detects genuine server death well inside the 30s buffer.
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis() - 15_000L)  // 15s stale — under idle threshold

        val streamActiveField = SendSpin::class.java.getDeclaredField("streamActive")
        streamActiveField.isAccessible = true
        val streamActive = streamActiveField.get(client) as AtomicBoolean
        streamActive.set(false)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertFalse("Watchdog should NOT close during idle when within the idle threshold",
            fakeTransport.closeCalled)
    }

    @Test
    fun `checkStall closes during idle when past idle threshold`() {
        // Idle threshold is 20s; 25s stale should trip even with no stream active. Issue #127.
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis() - 25_000L)  // 25s stale — past idle threshold

        val streamActiveField = SendSpin::class.java.getDeclaredField("streamActive")
        streamActiveField.isAccessible = true
        val streamActive = streamActiveField.get(client) as AtomicBoolean
        streamActive.set(false)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertTrue("Watchdog should close during idle when past the 20s idle threshold",
            fakeTransport.closeCalled)
        assertNotEquals(1000, fakeTransport.closeCode)
    }

    @Test
    fun `checkStall does not close streaming within streaming threshold`() {
        // Streaming threshold is 7s; 5s stale should NOT trip. Regression guard against
        // accidental tightening of the streaming threshold.
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis() - 5_000L)  // 5s stale — under streaming threshold

        val streamActiveField = SendSpin::class.java.getDeclaredField("streamActive")
        streamActiveField.isAccessible = true
        val streamActive = streamActiveField.get(client) as AtomicBoolean
        streamActive.set(true)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertFalse("Watchdog should NOT close while streaming within the 7s threshold",
            fakeTransport.closeCalled)
    }

    @Test
    fun `checkStall closes when stream is active and stalled`() {
        // Seed a stale timestamp
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as AtomicLong
        atomicLong.set(System.currentTimeMillis() - 60_000L)

        // Activate the stream
        val streamActiveField = SendSpin::class.java.getDeclaredField("streamActive")
        streamActiveField.isAccessible = true
        val streamActive = streamActiveField.get(client) as AtomicBoolean
        streamActive.set(true)

        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall")
        checkStall.isAccessible = true
        checkStall.invoke(client)

        assertTrue("Watchdog should close when stream is active and stalled",
            fakeTransport.closeCalled)
        assertNotEquals(1000, fakeTransport.closeCode)
    }

    private fun buildTransportListener(): SendSpinTransport.Listener {
        val innerClasses = SendSpin::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpin::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(client) as SendSpinTransport.Listener
    }
}
