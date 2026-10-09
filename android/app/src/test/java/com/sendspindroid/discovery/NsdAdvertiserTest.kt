package com.sendspindroid.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the client announces for servers to connect to (`connection.md`,
 * "Server Initiated Connections"), and that all of it is withdrawn again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsdAdvertiserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nsdManager = mockk<NsdManager>(relaxed = true)
    private val advertiser = NsdAdvertiser(context, nsdManager)

    private fun activeMulticastLocks(): Int =
        shadowOf(context.getSystemService(Context.WIFI_SERVICE) as WifiManager).activeLockCount

    private fun registered(): NsdServiceInfo {
        val info = slot<NsdServiceInfo>()
        verify { nsdManager.registerService(capture(info), NsdManager.PROTOCOL_DNS_SD, any<NsdManager.RegistrationListener>()) }
        return info.captured
    }

    @Test
    fun `advertises the sendspin client service type`() {
        advertiser.start("Kitchen", 8928, "/sendspin")

        assertEquals("_sendspin._tcp.", NsdAdvertiser.SERVICE_TYPE)
        assertEquals("_sendspin._tcp.", registered().serviceType)
    }

    @Test
    fun `advertises the port it was given`() {
        advertiser.start("Kitchen", 41237, "/sendspin")

        assertEquals(41237, registered().port)
    }

    @Test
    fun `the TXT records carry the path and the player name`() {
        advertiser.start("Kitchen Tablet", 8928, "/sendspin")

        val txt = registered().attributes.mapValues { String(it.value, Charsets.UTF_8) }
        assertEquals(mapOf("path" to "/sendspin", "name" to "Kitchen Tablet"), txt)
        assertEquals("Kitchen Tablet", registered().serviceName)
    }

    @Test
    fun `a long name is cut to a DNS label but sent whole in TXT`() {
        val name = "é".repeat(40) // 80 bytes of UTF-8

        val info = NsdAdvertiser.serviceInfo(name, 8928, "/sendspin")

        assertEquals("é".repeat(31), info.serviceName)
        assertEquals(name, String(info.attributes.getValue("name"), Charsets.UTF_8))
    }

    @Test
    fun `holds a multicast lock only while advertising`() {
        assertEquals(0, activeMulticastLocks())

        advertiser.start("Kitchen", 8928, "/sendspin")
        assertTrue(advertiser.isAdvertising)
        assertEquals(1, activeMulticastLocks())

        advertiser.stop()
        assertFalse(advertiser.isAdvertising)
        assertEquals(0, activeMulticastLocks())
    }

    @Test
    fun `stop withdraws the registration it made`() {
        advertiser.start("Kitchen", 8928, "/sendspin")
        val listener = slot<NsdManager.RegistrationListener>()
        verify { nsdManager.registerService(any(), any(), capture(listener)) }

        advertiser.stop()
        advertiser.stop()

        verify(exactly = 1) { nsdManager.unregisterService(listener.captured) }
    }

    @Test
    fun `a network change withdraws and registers again`() {
        advertiser.start("Kitchen", 8928, "/sendspin")

        advertiser.refresh()

        verifyOrder {
            nsdManager.registerService(any(), any(), any<NsdManager.RegistrationListener>())
            nsdManager.unregisterService(any())
            nsdManager.registerService(any(), any(), any<NsdManager.RegistrationListener>())
        }
        assertEquals(1, activeMulticastLocks())
    }

    @Test
    fun `a network change while not advertising does nothing`() {
        advertiser.refresh()

        verify(exactly = 0) { nsdManager.registerService(any(), any(), any<NsdManager.RegistrationListener>()) }
        assertEquals(0, activeMulticastLocks())
    }
}
