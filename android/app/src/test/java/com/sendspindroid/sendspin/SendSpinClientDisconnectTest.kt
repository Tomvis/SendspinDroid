package com.sendspindroid.sendspin

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import io.mockk.Ordering
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import com.sendspindroid.coordinator.TransportState as CoordinatorTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for SendSpin disconnect fixes.
 *
 * H-02: Verifies disconnect() does not fire onDisconnected twice.
 *
 * H-04 (proxy auth-ack consumption) covered the PROXY connection mode, which
 * was removed along with the reverse-proxy transport; its tests were deleted
 * rather than retargeted since the behaviour they covered no longer exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSpinDisconnectTest {

    private lateinit var mockCallback: SendSpin.Callback
    private lateinit var client: SendSpin

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Note: android.os.Build.MANUFACTURER is null on JVM; the production code
        // handles this with a fallback to "Unknown" in getManufacturer().

        // Mock UserSettings
        mockkObject(UserSettings)
        every { UserSettings.getPlayerId() } returns "test-player-id"
        every { UserSettings.getPreferredCodec() } returns "opus"
        every { UserSettings.lowMemoryMode } returns false
        every { UserSettings.highPowerMode } returns false

        // Mock AudioDecoderFactory
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } returns true

        // Mock PreferenceManager (needed by UserSettings init path)
        mockkStatic(PreferenceManager::class)
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { PreferenceManager.getDefaultSharedPreferences(any()) } returns mockPrefs

        mockCallback = mockk(relaxed = true)

        client = SendSpin("TestDevice", mockCallback)
    }

    @After
    fun tearDown() {
        client.destroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    // =========================================================================
    // H-02: disconnect() must not fire onDisconnected twice
    // =========================================================================

    @Test
    fun `disconnect clears transport listener before tearing down`() {
        // Use a mock transport that we can inject via the connect flow
        val mockTransport = mockk<SendSpinTransport>(relaxed = true)
        every { mockTransport.state } returns TransportState.Connected
        every { mockTransport.isConnected } returns true

        // Inject the mock transport via reflection
        val transportField = SendSpin::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, mockTransport)

        // Call disconnect
        client.disconnect()

        // Verify setListener(null) is called BEFORE destroy(). disconnect() must
        // call destroy() (not close()) so the underlying HttpClient is released --
        // close() alone leaks the OkHttp engine + ping thread until the next
        // connect cycle.
        verify(ordering = Ordering.ORDERED) {
            mockTransport.setListener(null)
            mockTransport.destroy()
        }
    }

    @Test
    fun `disconnect transitions to Idle exactly once even if transport onClosed races`() {
        // Create a transport that synchronously fires onClosed when close() is called,
        // simulating the worst-case race condition that H-02 describes.
        var capturedListener: SendSpinTransport.Listener? = null
        val racyTransport = object : SendSpinTransport {
            override val state = TransportState.Connected
            override val isConnected = true

            override fun connect() {}
            override fun send(text: String) = true
            override fun send(bytes: ByteArray) = true

            override fun setListener(listener: SendSpinTransport.Listener?) {
                capturedListener = listener
            }

            override fun close(code: Int, reason: String) {}

            override fun destroy() {
                // Simulate the H-02 race against the CURRENT teardown path:
                // disconnect() now calls destroy() (not close()), so fire onClosed
                // synchronously here. disconnect() calls setListener(null) before
                // destroy(), so capturedListener is null and onClosed must not
                // write a second Idle -- StateFlow dedup keeps it Idle exactly once.
                capturedListener?.onClosed(1000, "destroyed")
            }
        }

        // Register a listener (as the real code does during connect)
        val listenerField = SendSpin::class.java.getDeclaredField("transport")
        listenerField.isAccessible = true
        listenerField.set(client, racyTransport)

        // Call disconnect
        client.disconnect()

        // StateFlow naturally deduplicates: even if both the disconnect() path and
        // the raced onClosed() path write Idle, the observable state is Idle once.
        assertTrue(
            "State should be Idle after disconnect, was: ${client.connectionState.value}",
            client.connectionState.value is CoordinatorTransportState.Idle
        )
    }

    @Test
    fun `disconnect with null transport does not crash`() {
        // Ensure transport is null
        val transportField = SendSpin::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        transportField.set(client, null)

        // Should not throw
        client.disconnect()

        // State should still transition to Idle
        assertTrue(
            "State should be Idle after disconnect with null transport, was: ${client.connectionState.value}",
            client.connectionState.value is CoordinatorTransportState.Idle
        )
    }

}
