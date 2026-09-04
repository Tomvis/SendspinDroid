package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class SendSpinEndpointTest {
    @Test
    fun `Local carries address and path`() {
        val e: SendSpinEndpoint = SendSpinEndpoint.Local("10.0.1.5:8927", "/sendspin")
        assertEquals("10.0.1.5:8927", (e as SendSpinEndpoint.Local).address)
        assertEquals("/sendspin", e.path)
    }

    @Test
    fun `Local default path is the SendSpin endpoint constant`() {
        val e = SendSpinEndpoint.Local("10.0.1.5:8927")
        assertEquals(SendSpinProtocol.ENDPOINT_PATH, e.path)
    }
}
