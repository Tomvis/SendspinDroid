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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinDisconnectForReselectionTest {

    private lateinit var mockCallback: SendSpin.Callback
    private lateinit var client: SendSpin
    private lateinit var fakeTransport: FakeTransport

    private class FakeTransport : SendSpinTransport {
        var closeCalled = false
        var closeCode: Int = -1
        var destroyCalled = false
        var listenerCleared = false
        override val state = TransportState.Connected
        override val isConnected = true
        override fun connect() {}
        override fun send(text: String) = true
        override fun send(bytes: ByteArray) = true
        override fun setListener(listener: SendSpinTransport.Listener?) {
            if (listener == null) listenerCleared = true
        }
        override fun close(code: Int, reason: String) {
            closeCalled = true
            closeCode = code
        }
        override fun destroy() {
            destroyCalled = true
        }
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

        // Seed connected-state so disconnectForReselection has something to tear down.
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
    fun `disconnectForReselection transitions to Idle state`() {
        client.disconnectForReselection()

        assertTrue(
            "State should be Idle after reselection disconnect, was: ${client.connectionState.value}",
            client.connectionState.value is com.sendspindroid.coordinator.TransportState.Idle
        )
    }

    @Test
    fun `disconnectForReselection tears down the transport`() {
        client.disconnectForReselection()

        // destroy() (not close()) is the correct teardown path: it releases the
        // underlying HttpClient as well, preventing the OkHttp engine leak that
        // would otherwise persist until the next connect cycle.
        assertTrue("Transport destroy should be called", fakeTransport.destroyCalled)
    }

    @Test
    fun `disconnectForReselection clears transport listener before closing`() {
        client.disconnectForReselection()

        assertTrue("Transport listener should be cleared", fakeTransport.listenerCleared)
    }

    @Test
    fun `disconnectForReselection asks the owner to reconnect`() {
        client.disconnectForReselection()

        verify(exactly = 1) { mockCallback.onDisconnected(true) }
    }
}
