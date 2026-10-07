package com.sendspindroid.discovery

import com.sendspindroid.network.WebSocketUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Address choice for an mDNS-resolved service (issue #264).
 */
class MdnsAddressTest {

    private val port = 8927
    private val v4 = InetAddress.getByName("192.168.1.50")
    private val v4Other = InetAddress.getByName("10.0.0.7")
    private val global = InetAddress.getByName("2001:db8::1234")
    private val ula = InetAddress.getByName("fd12:3456:789a::1")
    private val linkLocal = InetAddress.getByName("fe80::1")

    private fun bracketed(address: InetAddress) = "[${address.hostAddress}]:$port"

    @Test
    fun `IPv4 is chosen when it is listed after IPv6`() {
        assertEquals("192.168.1.50:8927", MdnsAddress.format(listOf(linkLocal, global, v4), port))
    }

    @Test
    fun `IPv4 is chosen when it is listed before IPv6`() {
        assertEquals("192.168.1.50:8927", MdnsAddress.format(listOf(v4, global, linkLocal), port))
    }

    @Test
    fun `the first IPv4 address wins among several`() {
        assertEquals("192.168.1.50:8927", MdnsAddress.format(listOf(global, v4, v4Other), port))
    }

    @Test
    fun `an IPv6-only service gets a bracketed address`() {
        assertEquals(bracketed(global), MdnsAddress.format(listOf(global), port))
        assertEquals(bracketed(ula), MdnsAddress.format(listOf(ula, global), port))
    }

    @Test
    fun `the port of an IPv6 address survives the URL builder`() {
        val address = MdnsAddress.format(listOf(global), port)!!
        assertEquals(
            "ws://[${global.hostAddress}]:8927/sendspin",
            WebSocketUrlBuilder.build(address, "/sendspin")
        )
    }

    @Test
    fun `link-local IPv6 is passed over for a routable address`() {
        assertEquals(bracketed(global), MdnsAddress.format(listOf(linkLocal, global), port))
    }

    @Test
    fun `link-local IPv6 alone is not usable`() {
        assertNull(MdnsAddress.format(listOf(linkLocal), port))
    }

    @Test
    fun `a scoped link-local address is not usable either`() {
        val scoped = Inet6Address.getByAddress(null, linkLocal.address, 3)
        assertNull(MdnsAddress.format(listOf(scoped), port))
    }

    @Test
    fun `a scope id is left out of a routable IPv6 address`() {
        val scoped = Inet6Address.getByAddress(null, global.address, 3)
        assertEquals(bracketed(global), MdnsAddress.format(listOf(scoped), port))
    }

    @Test
    fun `an IPv4-mapped IPv6 address counts as IPv4`() {
        val mapped = InetAddress.getByName("::ffff:192.168.1.50")
        assertEquals("192.168.1.50:8927", MdnsAddress.format(listOf(global, mapped), port))
    }

    @Test
    fun `no addresses gives no address`() {
        assertNull(MdnsAddress.format(emptyList(), port))
    }
}
