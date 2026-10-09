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

    /** The advertisement, and an outbound connection that checks on it. */
    private class World : ConnectionMode.Advertising {
        var advertising = false
        var outboundAlive = false
        val log = mutableListOf<String>()

        override fun start() {
            assertFalse("advertised while an outbound connection was alive", outboundAlive)
            advertising = true
            log += "advertise"
        }

        /** What withdrawing sets off in the service, if anything. */
        var onStop: () -> Unit = {}

        override fun stop() {
            advertising = false
            log += "withdraw"
            onStop()
        }

        /** What the service does to open a connection. */
        fun connect() {
            assertFalse("dialled while advertising", advertising)
            outboundAlive = true
            log += "dial"
        }
    }

    private val world = World()
    private val mode = ConnectionMode(world)

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
    fun `choosing to search withdraws the advertisement and choosing back restores it`() {
        mode.start(searchForServers = false)

        mode.setSearching(true)
        assertFalse(world.advertising)

        mode.setSearching(false)
        assertTrue(world.advertising)
        assertEquals(listOf("advertise", "withdraw", "advertise"), world.log)
    }

    @Test
    fun `dialling withdraws the advertisement before the connection is opened`() {
        mode.start(searchForServers = false)

        mode.dial { world.connect() }

        assertEquals(listOf("advertise", "withdraw", "dial"), world.log)
        assertFalse(mode.isAdvertising)
    }

    @Test
    fun `the server that was connected going away does not end the dial that displaced it`() {
        mode.start(searchForServers = false)
        // Withdrawing ends the connection a server opened to us. The
        // service sees a connection end and reports that nothing it dialled
        // is alive, which is true: the dial has not opened anything yet.
        world.onStop = { mode.dialEnded() }

        mode.dial { world.connect() }

        assertEquals(listOf("advertise", "withdraw", "dial"), world.log)
        assertFalse(world.advertising)
    }

    @Test
    fun `stays withdrawn for as long as the dialled connection lives`() {
        mode.start(searchForServers = false)
        mode.dial { world.connect() }

        // The reconnect loop dials again; the user toggles the mode.
        mode.dial { world.connect() }
        mode.setSearching(true)
        mode.setSearching(false)

        assertFalse(world.advertising)
    }

    @Test
    fun `returns to advertising when the dialled connection ends for good`() {
        mode.start(searchForServers = false)
        mode.dial { world.connect() }

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
        mode.setSearching(false)
        mode.dialEnded()

        assertFalse(world.advertising)
        assertEquals(listOf("advertise", "withdraw"), world.log)
    }

    @Test
    fun `the advertisement and an outbound connection are never alive together`() {
        // Every order the service can call these in. World.start and
        // World.connect each fail the moment the other side is alive.
        val random = Random(221)
        repeat(200) {
            val world = World()
            val mode = ConnectionMode(world)
            // The service reports "nothing dialled is alive" whenever any
            // connection ends, including the ones withdrawing ends.
            world.onStop = { if (!world.outboundAlive) mode.dialEnded() }
            repeat(60) {
                when (random.nextInt(6)) {
                    0 -> mode.start(searchForServers = random.nextBoolean())
                    1 -> mode.setSearching(random.nextBoolean())
                    2 -> mode.dial { world.connect() }
                    3 -> if (world.outboundAlive) {
                        world.outboundAlive = false
                        mode.dialEnded()
                    }
                    4 -> mode.dial { world.connect() }
                    5 -> {
                        // The service going away takes its connection with it.
                        world.outboundAlive = false
                        mode.dialEnded()
                        mode.stop()
                    }
                }
                assertFalse(world.advertising && world.outboundAlive)
                assertEquals(world.advertising, mode.isAdvertising)
            }
        }
    }
}
