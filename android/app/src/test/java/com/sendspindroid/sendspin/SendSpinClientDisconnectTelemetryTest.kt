package com.sendspindroid.sendspin

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.LogLevel
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
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the connection-health telemetry introduced by issue #128.
 *
 * State being tracked on every disconnect event:
 *  - lastDisconnectCode / lastDisconnectReason
 *  - connectedAtMs cleared
 *
 * Plus: the isStallWatchdogArmed() accessor is gated on handshake +
 * not-user-initiated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinDisconnectTelemetryTest {

    private lateinit var mockCallback: SendSpin.Callback
    private lateinit var client: SendSpin

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

        // AppLog writes to android.util.Log under the hood; with level=OFF the
        // permit check short-circuits so the mocks above aren't even exercised,
        // but set OFF explicitly for robustness if the default ever changes.
        AppLog.setLevel(LogLevel.OFF)

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

        // Seed connection info so the disconnect paths execute fully.
        setField("serverAddress", "127.0.0.1:8080")
        setField("serverPath", "/sendspin")

        val fakeTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true
            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true
            override fun setListener(listener: SendSpinTransport.Listener?) {}
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
        }
        setField("transport", fakeTransport)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    // =========================================================================
    // Disconnect telemetry on onClosed / onFailure
    // =========================================================================

    @Test
    fun `onClosed abnormal populates lastDisconnectCode and reason`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onClosed(code = 1006, reason = "ping-timeout")

        assertEquals(Integer.valueOf(1006), client.getLastDisconnectCode())
        assertEquals("ping-timeout", client.getLastDisconnectReason())
        assertNull("connectedAtMs should be cleared after disconnect", client.getConnectedAtMs())
    }

    @Test
    fun `onClosed normal-closure still records code 1000`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onClosed(code = 1000, reason = "server shutdown")

        assertEquals(Integer.valueOf(1000), client.getLastDisconnectCode())
        assertEquals("server shutdown", client.getLastDisconnectReason())
    }

    @Test
    fun `onFailure populates code=null and reason=error message`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        val error = SocketException("connection reset")
        listener.onFailure(error, isRecoverable = true)

        assertNull("code should be null for onFailure", client.getLastDisconnectCode())
        assertEquals("connection reset", client.getLastDisconnectReason())
    }

    @Test
    fun `onFailure with null message falls back to exception class name`() {
        setHandshakeComplete(true)
        val listener = buildTransportListener()

        listener.onFailure(SocketException(), isRecoverable = true)

        // SocketException() without a message => error.message is null => reason = class simple name
        assertEquals("SocketException", client.getLastDisconnectReason())
    }

    // =========================================================================
    // isStallWatchdogArmed accessor
    // =========================================================================

    @Test
    fun `isStallWatchdogArmed is false pre-handshake`() {
        setHandshakeComplete(false)
        assertFalse(client.isStallWatchdogArmed())
    }

    @Test
    fun `isStallWatchdogArmed is true when handshake complete and not user-initiated`() {
        setHandshakeComplete(true)
        assertTrue(client.isStallWatchdogArmed())
    }

    @Test
    fun `isStallWatchdogArmed is false after user-initiated disconnect`() {
        setHandshakeComplete(true)
        val userField = SendSpin::class.java.getDeclaredField("userInitiatedDisconnect")
        userField.isAccessible = true
        (userField.get(client) as AtomicBoolean).set(true)
        assertFalse(client.isStallWatchdogArmed())
    }

    // =========================================================================
    // getLastByteReceivedAgoMs accessor
    // =========================================================================

    @Test
    fun `getLastByteReceivedAgoMs returns positive elapsed time from lastByteReceivedAtMs`() {
        // Force the timestamp to ~5 seconds ago via reflection.
        val lastByteField = SendSpin::class.java.getDeclaredField("lastByteReceivedAtMs")
        lastByteField.isAccessible = true
        val atomicLong = lastByteField.get(client) as java.util.concurrent.atomic.AtomicLong
        atomicLong.set(System.currentTimeMillis() - 5_000L)

        val ago = client.getLastByteReceivedAgoMs()
        assertTrue("ago should be around 5000ms, got $ago", ago in 4_500L..6_000L)
    }

    // --- helpers ---

    private fun buildTransportListener(): SendSpinTransport.Listener {
        val innerClasses = SendSpin::class.java.declaredClasses
        val listenerClass = innerClasses.find { it.simpleName == "TransportEventListener" }!!
        val constructor = listenerClass.getDeclaredConstructor(SendSpin::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(client) as SendSpinTransport.Listener
    }

    private fun setField(name: String, value: Any?) {
        val f = SendSpin::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(client, value)
    }

    private fun getField(name: String): Any? {
        val f = SendSpin::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(client)
    }

    private fun setHandshakeComplete(value: Boolean) {
        val f = SendSpin::class.java.superclass.getDeclaredField("handshakeComplete")
        f.isAccessible = true
        f.set(client, value)
    }
}
