package com.sendspindroid.discovery

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Picks the address to dial from the ones an mDNS service resolved to, and
 * formats it with the port in the `host:port` / `[ipv6]:port` form that
 * WebSocketUrlBuilder parses.
 *
 * The connection is opened to this one literal, so there is no second address
 * to fall back to. The rule is fixed instead:
 * 1. the first IPv4 address, when one is advertised;
 * 2. otherwise the first IPv6 address that is not link-local;
 * 3. otherwise nothing. A link-local IPv6 address only works with a scope id,
 *    and a URL cannot carry one.
 */
internal object MdnsAddress {

    fun format(addresses: List<InetAddress>, port: Int): String? {
        addresses.firstOrNull { it is Inet4Address }?.let {
            return "${it.hostAddress}:$port"
        }
        val v6 = addresses.firstOrNull { !it.isLinkLocalAddress } ?: return null
        // The brackets keep the port apart from the address; the scope id goes.
        return "[${v6.hostAddress?.substringBefore('%')}]:$port"
    }
}
