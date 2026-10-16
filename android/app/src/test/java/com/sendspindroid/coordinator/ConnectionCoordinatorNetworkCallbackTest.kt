package com.sendspindroid.coordinator

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's network callback, driven as ConnectivityManager would:
 * which networks it follows and which link changes it reports.
 *
 * Build.VERSION.SDK_INT is 0 in a local unit test, so the coordinator takes
 * its below-API-31 registration here; ConnectionCoordinatorRegistrationTest
 * covers the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorNetworkCallbackTest {

    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true) {
        every { registerDefaultNetworkCallback(capture(callback), any()) } just runs
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    private fun network(handle: Long): Network = mockk(relaxed = true) {
        every { networkHandle } returns handle
    }

    private fun capabilities(vpn: Boolean = false): NetworkCapabilities = mockk(relaxed = true) {
        every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns vpn
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
    }

    /** A fresh LinkAddress object each call, as each callback brings new ones. */
    private fun address(ip: String, prefix: Int = 64): LinkAddress = mockk {
        every { address } returns InetAddress.getByName(ip)
        every { prefixLength } returns prefix
    }

    private fun properties(vararg addresses: LinkAddress): LinkProperties = mockk {
        every { linkAddresses } returns addresses.toList()
    }

    /** What the callback is sent when [network] becomes the one it tracks. */
    private fun becomesBest(network: Network, caps: NetworkCapabilities, props: LinkProperties = properties()) {
        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, caps)
        callback.captured.onLinkPropertiesChanged(network, props)
    }

    /** Whether [network] still exists, as ConnectivityManager answers it. */
    private fun stillUp(network: Network, caps: NetworkCapabilities) {
        every { connectivityManager.getNetworkCapabilities(network) } returns caps
    }

    private fun gone(network: Network) {
        every { connectivityManager.getNetworkCapabilities(network) } returns null
    }

    private val sendSpinState = MutableStateFlow<TransportState>(TransportState.Idle)

    /** The connection is up, on whatever network is followed now. */
    private fun TestScope.connected() {
        sendSpinState.value = TransportState.Ready
        testScheduler.runCurrent()
    }

    private fun TestScope.disconnected() {
        sendSpinState.value = TransportState.Idle
        testScheduler.runCurrent()
    }

    private fun TestScope.coordinator(): ConnectionCoordinator = ConnectionCoordinator(
        currentServerFlow = MutableStateFlow(null),
        sendSpinStateFlow = sendSpinState,
        scope = TestScope(StandardTestDispatcher(testScheduler)),
        connectAttempt = { _, _ -> false },
        context = context,
    )

    private fun TestScope.coordinatorEvents(): MutableList<NetworkEvent> {
        val coordinator = coordinator()
        val events = mutableListOf<NetworkEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            coordinator.networkEvents.collect { events.add(it) }
        }
        testScheduler.runCurrent()
        return events
    }

    /** The addresses of each event, which must all be address changes. */
    private fun addressChanges(events: List<NetworkEvent>): List<Set<InetAddress>> = events.map { event ->
        assertTrue("not an address change: $event", event is NetworkEvent.LinkAddressesChanged)
        (event as NetworkEvent.LinkAddressesChanged).addresses.map { it.address }.toSet()
    }

    private fun ips(vararg ips: String): Set<InetAddress> = ips.map { InetAddress.getByName(it) }.toSet()

    @Test
    fun `below API 31 follows the default network`() = runTest {
        val coordinator = coordinator()

        verify { connectivityManager.registerDefaultNetworkCallback(callback.captured, any<Handler>()) }
        verify(exactly = 0) {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
        verify(exactly = 0) { connectivityManager.registerBestMatchingNetworkCallback(any(), any(), any()) }

        coordinator.close()
        verify { connectivityManager.unregisterNetworkCallback(callback.captured) }
    }

    @Test
    fun `a refresh of the same addresses is not an address change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        becomesBest(wifi, capabilities(), properties(address("2001:db8::1"), address("10.0.1.6", 22)))

        // The same addresses again, as new objects: a router advertisement
        // refreshes lifetimes, which LinkAddress equality would count.
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1"), address("10.0.1.6", 22)))
        testScheduler.runCurrent()

        assertTrue("no event for a refresh: $events", events.isEmpty())
    }

    @Test
    fun `an added address is an address change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        becomesBest(wifi, capabilities(), properties(address("2001:db8::1")))

        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1"), address("2001:db8::2")))
        testScheduler.runCurrent()

        assertEquals(1, events.size)
        assertTrue(events.single() is NetworkEvent.LinkAddressesChanged)
    }

    @Test
    fun `a new network after the old one is gone is an identity change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        becomesBest(wifi, capabilities(), properties(address("10.0.1.6", 22)))

        // Cellular's addresses are a new baseline, not a change from Wi-Fi's.
        gone(wifi)
        becomesBest(network(2L), capabilities(), properties(address("10.201.7.9", 30)))
        testScheduler.runCurrent()

        assertEquals(listOf<NetworkEvent>(NetworkEvent.IdentityChanged(2L)), events)
    }

    @Test
    fun `a new network while the old one is still up is not an identity change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))

        // Wi-Fi lost validation (an ISP outage): cellular takes over while
        // Wi-Fi stays connected, and so does a LAN connection open on it.
        // Cellular's addresses are reported, to refresh what depends on them.
        stillUp(wifi, wifiCaps)
        becomesBest(network(2L), capabilities(), properties(address("10.201.7.9", 30)))
        testScheduler.runCurrent()

        assertEquals(listOf(ips("10.201.7.9")), addressChanges(events))
    }

    @Test
    fun `Wi-Fi back while cellular is still up is an address change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        becomesBest(wifi, capabilities(), properties(address("10.0.1.6", 22)))
        gone(wifi)
        val cell = network(2L)
        val cellCaps = capabilities()
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))

        // The router is back: Wi-Fi rejoins as a new network, while cellular
        // lingers. The advertisement was last refreshed on cellular alone.
        // Only its first addresses are reported, not every refresh of them.
        stillUp(cell, cellCaps)
        val wifi2 = network(3L)
        becomesBest(wifi2, capabilities(), properties(address("10.0.1.7", 22)))
        callback.captured.onLinkPropertiesChanged(wifi2, properties(address("10.0.1.7", 22)))
        testScheduler.runCurrent()

        assertEquals(NetworkEvent.IdentityChanged(2L), events.first())
        assertEquals(listOf(ips("10.0.1.7")), addressChanges(events.drop(1)))
    }

    @Test
    fun `a return to the network left while it was up is not an identity change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        connected()

        // Cellular takes over while Wi-Fi stays up, then goes: Wi-Fi is the
        // network a LAN connection has been on all along. Each network's
        // addresses are reported as it is followed.
        stillUp(wifi, wifiCaps)
        val cell = network(2L)
        becomesBest(cell, capabilities(), properties(address("10.201.7.9", 30)))
        gone(cell)
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        testScheduler.runCurrent()

        assertEquals(listOf(ips("10.201.7.9"), ips("10.0.1.6")), addressChanges(events))
    }

    @Test
    fun `a return after several switches is to the network the connection is on`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        connected()

        // Wi-Fi -> cellular -> Ethernet, each left still up, then Ethernet
        // goes and Wi-Fi is best again.
        stillUp(wifi, wifiCaps)
        val cell = network(2L)
        val cellCaps = capabilities()
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))
        stillUp(cell, cellCaps)
        val ethernet = network(3L)
        becomesBest(ethernet, capabilities(), properties(address("10.0.1.8", 22)))
        gone(ethernet)
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        testScheduler.runCurrent()

        assertTrue("no identity change back on Wi-Fi: $events", events.none { it is NetworkEvent.IdentityChanged })
    }

    @Test
    fun `a handover is to the network the connection was made on, not the one last left`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        becomesBest(wifi, capabilities(), properties(address("10.0.1.6", 22)))
        connected()

        // Leaving home: Wi-Fi goes, cellular is a handover
        gone(wifi)
        val cell = network(2L)
        val cellCaps = capabilities()
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))
        disconnected()

        // Home again: Wi-Fi rejoins while cellular stays up, and the
        // connection is made on it
        stillUp(cell, cellCaps)
        val wifi2 = network(3L)
        becomesBest(wifi2, capabilities(), properties(address("10.0.1.7", 22)))
        connected()

        // Leaving again: the connection's Wi-Fi is gone
        gone(wifi2)
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))
        testScheduler.runCurrent()

        assertEquals(
            listOf<NetworkEvent>(NetworkEvent.IdentityChanged(2L), NetworkEvent.IdentityChanged(2L)),
            events.filterIsInstance<NetworkEvent.IdentityChanged>(),
        )
    }

    @Test
    fun `a connection made after a network went is followed, not the network`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        connected()

        // A WAN outage: cellular takes over while Wi-Fi stays up
        stillUp(wifi, wifiCaps)
        val cell = network(2L)
        val cellCaps = capabilities()
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))

        // The router reboots: Wi-Fi goes with no callback (it is not the best
        // network) and the connection with it, then Wi-Fi rejoins as a new
        // network while cellular is up, and the connection is made on it
        gone(wifi)
        disconnected()
        stillUp(cell, cellCaps)
        val wifi2 = network(3L)
        val wifi2Caps = capabilities()
        becomesBest(wifi2, wifi2Caps, properties(address("10.0.1.7", 22)))
        connected()

        // Another outage, and back
        stillUp(wifi2, wifi2Caps)
        becomesBest(cell, cellCaps, properties(address("10.201.7.9", 30)))
        gone(cell)
        becomesBest(wifi2, wifi2Caps, properties(address("10.0.1.7", 22)))
        testScheduler.runCurrent()

        assertTrue("no handover for a connection still up: $events", events.none { it is NetworkEvent.IdentityChanged })
    }

    /**
     * Signal strength and bandwidth estimates resend the followed network's
     * capabilities; an address added after that is still a change from the
     * baseline, and the only event.
     */
    private fun TestScope.capabilitiesAgainThenAnAddress(goingAway: Boolean): List<NetworkEvent> {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        if (goingAway) gone(wifi)

        callback.captured.onCapabilitiesChanged(wifi, wifiCaps)
        // Nothing to report for the same addresses: the repeat was no switch
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("10.0.1.6", 22)))
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("10.0.1.6", 22), address("2001:db8::1")))
        testScheduler.runCurrent()
        return events
    }

    @Test
    fun `the followed network's capabilities coming again is not a switch`() = runTest {
        val events = capabilitiesAgainThenAnAddress(goingAway = false)

        assertEquals(listOf(ips("10.0.1.6", "2001:db8::1")), addressChanges(events))
    }

    @Test
    fun `the followed network's capabilities coming again as it goes is not an identity change`() = runTest {
        val events = capabilitiesAgainThenAnAddress(goingAway = true)

        assertEquals(listOf(ips("10.0.1.6", "2001:db8::1")), addressChanges(events))
    }

    @Test
    fun `a VPN coming up and going down is not a network change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))

        stillUp(wifi, wifiCaps)
        val vpn = network(2L)
        becomesBest(vpn, capabilities(vpn = true), properties(address("100.64.0.1", 32)))
        gone(vpn)
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))
        testScheduler.runCurrent()

        assertTrue("no event for a VPN toggle: $events", events.isEmpty())
    }

    @Test
    fun `a VPN up at registration is not the network identity`() = runTest {
        val events = coordinatorEvents()
        val vpn = network(2L)
        becomesBest(vpn, capabilities(vpn = true))

        // The VPN is turned off, and Wi-Fi is the default again.
        gone(vpn)
        becomesBest(network(1L), capabilities())
        testScheduler.runCurrent()

        assertTrue("no event for the first physical network: $events", events.isEmpty())
    }

    @Test
    fun `a VPN's address changes are not reported`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        val wifiCaps = capabilities()
        becomesBest(wifi, wifiCaps, properties(address("10.0.1.6", 22)))

        stillUp(wifi, wifiCaps)
        val vpn = network(2L)
        becomesBest(vpn, capabilities(vpn = true), properties(address("100.64.0.1", 32)))
        callback.captured.onLinkPropertiesChanged(vpn, properties(address("100.64.0.1", 32), address("fd7a:115c::1", 128)))
        testScheduler.runCurrent()

        assertTrue("no event for the tun interface: $events", events.isEmpty())
    }
}
