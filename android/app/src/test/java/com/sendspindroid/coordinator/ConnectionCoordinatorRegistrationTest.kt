package com.sendspindroid.coordinator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On API 31 and up the coordinator follows the best network that is not a
 * VPN, so Wi-Fi under a VPN is still seen. ConnectionCoordinatorNetworkCallbackTest
 * covers the registration below API 31 and what the callback does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionCoordinatorRegistrationTest {

    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val request = slot<NetworkRequest>()
    private val handler = slot<Handler>()
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true) {
        every {
            registerBestMatchingNetworkCallback(capture(request), capture(callback), capture(handler))
        } just runs
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    @Test
    fun `follows the best network with internet that is not a VPN`() = runTest {
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            connectAttempt = { _, _ -> false },
            context = context,
        )

        assertTrue(request.captured.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(request.captured.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertEquals(Looper.getMainLooper(), handler.captured.looper)
        verify(exactly = 0) { connectivityManager.registerDefaultNetworkCallback(any(), any()) }
        verify(exactly = 0) { connectivityManager.registerDefaultNetworkCallback(any()) }

        coordinator.close()
        verify { connectivityManager.unregisterNetworkCallback(callback.captured) }
    }
}
