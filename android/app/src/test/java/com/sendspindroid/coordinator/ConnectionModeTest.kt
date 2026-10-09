package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * "Clients MUST NOT manually connect to servers while advertising
 * `_sendspin._tcp`", and the reverse (`connection.md`, "Establishing a
 * Connection").
 */
class ConnectionModeTest {

    /**
     * The advertisement, and an outbound connection that checks on it.
     *
     * Taking the advertisement down takes time, as closing the listener
     * does: it stays up until [finishStop], unless [stopsAtOnce].
     */
    private class World(private val stopsAtOnce: Boolean = false) :
        ConnectionMode.Advertising, ConnectionMode.Discovery {
        var advertising = false
        var outboundAlive = false
        var browsing = false
        private var browsingForbidden = false

        override fun forbid() {
            browsingForbidden = true
            browsing = false
        }

        override fun allow() {
            assertFalse("browsing allowed while advertising", advertising)
            browsingForbidden = false
        }

        /** What the activity, the wizard or Android Auto does to look for servers. */
        fun browse() {
            if (browsingForbidden) return
            assertFalse("browsed while advertising", advertising)
            browsing = true
        }
        val log = mutableListOf<String>()
        private var stopped: (() -> Unit)? = null
        val stopInFlight get() = stopped != null

        /** What withdrawing sets off in the service, if anything. */
        var onStop: () -> Unit = {}

        override fun start() {
            assertFalse("advertised while a browse was running", browsing)
            assertFalse("advertised while an outbound connection was alive", outboundAlive)
            assertFalse("advertised again before the last listener was gone", stopInFlight)
            advertising = true
            log += "advertise"
        }

        override fun stop(onStopped: () -> Unit) {
            assertFalse("stopped twice at once", stopInFlight)
            log += "withdraw"
            stopped = onStopped
            onStop()
            if (stopsAtOnce) finishStop()
        }

        /** The listener's port is released. */
        fun finishStop() {
            val report = stopped ?: return
            advertising = false
            stopped = null
            log += "gone"
            report()
        }

        /** What the service does to open a connection. */
        fun connect() {
            assertFalse("dialled while advertising", advertising)
            outboundAlive = true
            log += "dial"
        }
    }

    private val world = World()
    private val mode = ConnectionMode(world, world)

    @Test
    fun `advertises by default`() {
        mode.start(searchForServers = false)

        assertTrue(world.advertising)
        assertTrue(mode.isAdvertising)
    }

    @Test
    fun `does not advertise in search mode`() {
        mode.start(searchForServers = true)

        assertFalse(world.advertising)
        assertEquals(emptyList<String>(), world.log)
    }

    @Test
    fun `a browse under way is stopped before the advertisement goes up`() {
        mode.start(searchForServers = true)
        world.browse()
        assertTrue(world.browsing)

        mode.setSearching(false)

        // World.start fails if the browse is still running when it is called.
        assertTrue(world.advertising)
        assertFalse(world.browsing)
    }

    @Test
    fun `nothing browses while advertising, or while the listener is still closing`() {
        mode.start(searchForServers = false)
        world.browse()
        assertFalse(world.browsing)

        mode.setSearching(true)
        world.browse()
        assertFalse("the advertisement is not gone yet", world.browsing)

        world.finishStop()
        world.browse()
        assertTrue(world.browsing)
    }

    @Test
    fun `choosing to search withdraws the advertisement and choosing back restores it`() {
        mode.start(searchForServers = false)

        mode.setSearching(true)
        world.finishStop()
        assertFalse(world.advertising)

        mode.setSearching(false)
        assertTrue(world.advertising)
        assertEquals(listOf("advertise", "withdraw", "gone", "advertise"), world.log)
    }

    @Test
    fun `a dial waits until the advertisement and the listener are gone`() {
        mode.start(searchForServers = false)

        mode.dial { world.connect() }
        assertEquals("nothing is dialled while the listener is closing", listOf("advertise", "withdraw"), world.log)
        assertFalse(world.outboundAlive)

        world.finishStop()
        assertEquals(listOf("advertise", "withdraw", "gone", "dial"), world.log)
        assertFalse(mode.isAdvertising)
    }

    @Test
    fun `a dial with nothing advertised connects at once`() {
        mode.start(searchForServers = true)

        mode.dial { world.connect() }

        assertEquals(listOf("dial"), world.log)
    }

    @Test
    fun `a dial made while a stop is in flight waits for that stop`() {
        mode.start(searchForServers = false)
        mode.setSearching(true)

        mode.dial { world.connect() }
        assertFalse(world.outboundAlive)

        world.finishStop()
        assertEquals(listOf("advertise", "withdraw", "gone", "dial"), world.log)
    }

    @Test
    fun `advertising again waits for the stop in flight`() {
        mode.start(searchForServers = false)
        mode.setSearching(true)

        // Back before the listener is gone: 8928 is still bound.
        mode.setSearching(false)
        assertEquals(listOf("advertise", "withdraw"), world.log)

        world.finishStop()
        assertEquals(listOf("advertise", "withdraw", "gone", "advertise"), world.log)
        assertTrue(world.advertising)
    }

    @Test
    fun `the server that was connected going away does not end the dial that displaced it`() {
        mode.start(searchForServers = false)
        // Withdrawing ends the connection a server opened to us. The
        // service sees a connection end and reports that nothing it dialled
        // is alive, which is true: the dial has not opened anything yet.
        world.onStop = { mode.dialEnded() }

        mode.dial { world.connect() }
        mode.dialEnded()
        world.finishStop()

        assertEquals(listOf("advertise", "withdraw", "gone", "dial"), world.log)
        assertFalse(world.advertising)
    }

    @Test
    fun `stays withdrawn for as long as the dialled connection lives`() {
        mode.start(searchForServers = false)
        mode.dial { world.connect() }
        world.finishStop()

        // The reconnect loop dials again; the user toggles the mode.
        mode.dial { world.connect() }
        mode.setSearching(true)
        mode.setSearching(false)

        assertFalse(world.advertising)
        assertFalse(world.stopInFlight)
    }

    @Test
    fun `returns to advertising when the dialled connection ends for good`() {
        mode.start(searchForServers = false)
        mode.dial { world.connect() }
        world.finishStop()

        world.outboundAlive = false
        mode.dialEnded()

        assertTrue(world.advertising)
    }

    @Test
    fun `returns to searching when that is the selected mode`() {
        mode.start(searchForServers = true)
        mode.dial { world.connect() }

        world.outboundAlive = false
        mode.dialEnded()

        assertFalse(world.advertising)
        assertEquals(listOf("dial"), world.log)
    }

    @Test
    fun `a mode chosen while dialled out takes effect when the connection ends`() {
        mode.start(searchForServers = true)
        mode.dial { world.connect() }
        mode.setSearching(false)
        assertFalse(world.advertising)

        world.outboundAlive = false
        mode.dialEnded()

        assertTrue(world.advertising)
    }

    @Test
    fun `stopping withdraws the advertisement and nothing brings it back`() {
        mode.start(searchForServers = false)

        mode.stop()
        world.finishStop()
        mode.setSearching(false)
        mode.dialEnded()

        assertFalse(world.advertising)
        assertEquals(listOf("advertise", "withdraw", "gone"), world.log)
    }

    @Test
    fun `stopping forgets a dial that has not connected yet`() {
        mode.start(searchForServers = false)
        mode.dial { world.connect() }

        mode.stop()
        world.finishStop()

        assertFalse(world.outboundAlive)
    }

    @Test
    fun `the advertisement and an outbound connection are never alive together`() {
        // Every order the service can call these in, with the listener
        // taking its time to close and with it closing at once. World.start
        // and World.connect each fail the moment the other side is alive.
        val random = Random(221)
        repeat(400) { round ->
            val world = World(stopsAtOnce = round % 2 == 0)
            val mode = ConnectionMode(world, world)
            // The service reports "nothing dialled is alive" whenever any
            // connection ends, including the ones withdrawing ends.
            world.onStop = { if (!world.outboundAlive) mode.dialEnded() }
            repeat(80) {
                when (random.nextInt(8)) {
                    7 -> world.browse()
                    0 -> mode.start(searchForServers = random.nextBoolean())
                    1 -> mode.setSearching(random.nextBoolean())
                    2, 3 -> mode.dial { world.connect() }
                    4 -> if (world.outboundAlive) {
                        world.outboundAlive = false
                        mode.dialEnded()
                    }
                    5 -> world.finishStop()
                    6 -> {
                        // The service going away takes its connection with it.
                        world.outboundAlive = false
                        mode.stop()
                    }
                }
                assertFalse(world.advertising && world.outboundAlive)
                assertFalse(world.advertising && world.browsing)
                if (!world.stopInFlight) assertEquals(world.advertising, mode.isAdvertising)
            }
        }
    }
}
