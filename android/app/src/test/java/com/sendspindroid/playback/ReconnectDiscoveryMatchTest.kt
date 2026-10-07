package com.sendspindroid.playback

import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mDNS announcements make the reconnect loop retry at once: the ones
 * for the server it lost, and no others on the network.
 */
class ReconnectDiscoveryMatchTest {

    private val lost = UnifiedServer(
        id = "saved-1",
        name = "Living Room",
        local = LocalConnection(address = "10.0.0.5:8927"),
    )

    @Test
    fun `the lost server is recognised by its friendly name, even at a new address`() {
        assertTrue(PlaybackService.isSameServer(lost, "sendspin-1a2b", "Living Room", "10.0.0.77:8927"))
    }

    @Test
    fun `the lost server is recognised by its service name`() {
        assertTrue(PlaybackService.isSameServer(lost, "Living Room", "", "10.0.0.77:8927"))
    }

    @Test
    fun `the lost server is recognised by its address under another name`() {
        assertTrue(PlaybackService.isSameServer(lost, "sendspin-1a2b", "Renamed", "10.0.0.5:8927"))
    }

    @Test
    fun `another server on the network is not the lost one`() {
        assertFalse(PlaybackService.isSameServer(lost, "sendspin-9f9f", "Kitchen", "10.0.0.6:8927"))
    }
}
