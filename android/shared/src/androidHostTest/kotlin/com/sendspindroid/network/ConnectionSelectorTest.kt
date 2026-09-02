package com.sendspindroid.network

import com.sendspindroid.model.*
import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionSelectorTest {

    private val localConn = LocalConnection("192.168.1.100", "/sendspin")
    private val remoteConn = RemoteConnection("abc123")
    private val proxyConn = ProxyConnection("https://proxy.example.com", "token123")

    private fun server(
        local: LocalConnection? = null,
        remote: RemoteConnection? = null,
        proxy: ProxyConnection? = null,
        preference: ConnectionPreference = ConnectionPreference.AUTO
    ) = UnifiedServer(
        id = "test-server",
        name = "Test Server",
        local = local,
        remote = remote,
        proxy = proxy,
        connectionPreference = preference
    )

    private fun networkState(transport: TransportType) = NetworkState(
        transportType = transport,
        isConnected = true
    )

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- Priority order: local only, regardless of network type ---

    @Test
    fun getPriorityOrder_wifi_localOnly() {
        assertEquals(listOf(ConnectionType.LOCAL), ConnectionSelector.getPriorityOrder(TransportType.WIFI))
    }

    @Test
    fun getPriorityOrder_ethernet_localOnly() {
        assertEquals(listOf(ConnectionType.LOCAL), ConnectionSelector.getPriorityOrder(TransportType.ETHERNET))
    }

    @Test
    fun getPriorityOrder_cellular_localOnly() {
        assertEquals(listOf(ConnectionType.LOCAL), ConnectionSelector.getPriorityOrder(TransportType.CELLULAR))
    }

    @Test
    fun getPriorityOrder_vpn_localOnly() {
        assertEquals(listOf(ConnectionType.LOCAL), ConnectionSelector.getPriorityOrder(TransportType.VPN))
    }

    @Test
    fun getPriorityOrder_unknown_localOnly() {
        assertEquals(listOf(ConnectionType.LOCAL), ConnectionSelector.getPriorityOrder(TransportType.UNKNOWN))
    }

    // --- Auto selection ---

    @Test
    fun selectConnection_wifiWithAllMethods_selectsLocal() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.WIFI)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Local)
        assertEquals("192.168.1.100", (result as ConnectionSelector.SelectedConnection.Local).address)
    }

    @Test
    fun selectConnection_cellularWithAllMethods_selectsLocal() {
        // Local is the only connection method left, so it is attempted on every
        // network type, including cellular.
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.CELLULAR)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Local)
    }

    @Test
    fun selectConnection_cellularOnlyLocal_selectsLocal() {
        // Regression guard for #115: LOCAL must be attempted on cellular
        // when it is the only configured connection (publicly-routable
        // hostname case - e.g. user configured an AAAA-only DNS name).
        val result = ConnectionSelector.selectConnection(
            server(local = localConn),
            networkState(TransportType.CELLULAR)
        )
        assertTrue(
            "Expected Local selection but got $result",
            result is ConnectionSelector.SelectedConnection.Local
        )
    }

    @Test
    fun selectConnection_noLocal_returnsNull() {
        // A server with only remote/proxy configured (legacy data) has nothing
        // this app can connect to any more.
        val result = ConnectionSelector.selectConnection(
            server(remote = remoteConn, proxy = proxyConn),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    @Test
    fun selectConnection_noMethodsConfigured_returnsNull() {
        val result = ConnectionSelector.selectConnection(
            server(),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    // --- Preference overrides ---

    @Test
    fun selectConnection_localOnlyPreference_selectsLocal() {
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.LOCAL_ONLY),
            networkState(TransportType.CELLULAR)
        )
        assertTrue(result is ConnectionSelector.SelectedConnection.Local)
    }

    @Test
    fun selectConnection_localOnlyPreference_noLocal_returnsNull() {
        val result = ConnectionSelector.selectConnection(
            server(remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.LOCAL_ONLY),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    @Test
    fun selectConnection_remoteOnlyPreference_returnsNull() {
        // Remote access no longer exists; REMOTE_ONLY can never be satisfied,
        // even when a local address is also configured.
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.REMOTE_ONLY),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    @Test
    fun selectConnection_proxyOnlyPreference_returnsNull() {
        // Proxy access no longer exists; PROXY_ONLY can never be satisfied,
        // even when a local address is also configured.
        val result = ConnectionSelector.selectConnection(
            server(local = localConn, remote = remoteConn, proxy = proxyConn,
                preference = ConnectionPreference.PROXY_ONLY),
            networkState(TransportType.WIFI)
        )
        assertNull(result)
    }

    // --- getConnectionDescription ---

    @Test
    fun getConnectionDescription_local_containsAddress() {
        val desc = ConnectionSelector.getConnectionDescription(
            ConnectionSelector.SelectedConnection.Local("192.168.1.50", "/sendspin")
        )
        assertTrue(desc.contains("192.168.1.50"))
        assertTrue(desc.contains("Local"))
    }
}
