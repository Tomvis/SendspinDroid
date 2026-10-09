package com.sendspindroid.discovery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No browse for servers runs, or starts, while the app advertises itself. */
class DiscoveryGateTest {

    private val stopped = mutableListOf<String>()

    @After
    fun tearDown() {
        DiscoveryGate.allow()
        DiscoveryGate.leave("list")
        DiscoveryGate.leave("wizard")
    }

    @Test
    fun `a browse may start while nothing is advertised`() {
        assertTrue(DiscoveryGate.allowed.value)
        assertTrue(DiscoveryGate.enter("list") { stopped += "list" })
    }

    @Test
    fun `forbidding stops every browse under way`() {
        DiscoveryGate.enter("list") { stopped += "list" }
        DiscoveryGate.enter("wizard") { stopped += "wizard" }

        DiscoveryGate.forbid()

        assertEquals(listOf("list", "wizard"), stopped)
        assertFalse(DiscoveryGate.allowed.value)
    }

    @Test
    fun `no browse starts while forbidden, and one may again once allowed`() {
        DiscoveryGate.forbid()
        assertFalse(DiscoveryGate.enter("wizard") { stopped += "wizard" })

        DiscoveryGate.allow()
        assertTrue(DiscoveryGate.enter("wizard") { stopped += "wizard" })
    }

    @Test
    fun `a browse that ended by itself is not stopped again`() {
        DiscoveryGate.enter("list") { stopped += "list" }
        DiscoveryGate.leave("list")

        DiscoveryGate.forbid()

        assertEquals(emptyList<String>(), stopped)
    }
}
