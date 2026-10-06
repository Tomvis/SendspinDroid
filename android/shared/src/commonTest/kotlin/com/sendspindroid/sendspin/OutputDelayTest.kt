package com.sendspindroid.sendspin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `output_delay_ms` is delay BEYOND the audio port, and is not the same
 * quantity as the hardware latency the client compensates itself.
 *
 * roles/player/v1.md: it "compensates for additional delay beyond the port
 * (external speakers, amplifiers); it does not cover processing delays before
 * the port (DAC latency, audio buffers), which the client compensates itself".
 *
 * The sign follows from that: audio taking longer to reach the listener must
 * LEAVE the port earlier, so a positive output delay moves the client-side
 * play time EARLIER. That is the opposite direction to the internal signed
 * static delay, which moves it later.
 */
class OutputDelayTest {

    @Test
    fun `defaults to zero`() {
        assertEquals(0.0, SendspinTimeFilter().outputDelayMs)
    }

    @Test
    fun `a positive output delay makes playback earlier`() {
        val f = SendspinTimeFilter()
        val before = f.serverToClient(1_000_000L)
        f.setOutputDelayMs(100.0)
        val after = f.serverToClient(1_000_000L)
        assertTrue(after < before, "100 ms of output delay must move play time earlier")
        assertEquals(100_000L, before - after)
    }

    @Test
    fun `clientToServer inverts serverToClient with an output delay applied`() {
        val f = SendspinTimeFilter()
        f.setOutputDelayMs(250.0)
        val server = 5_000_000L
        assertEquals(server, f.clientToServer(f.serverToClient(server)))
    }

    /** The spec forbids negatives; ours clamps rather than trusting callers. */
    @Test
    fun `negative input clamps to zero`() {
        val f = SendspinTimeFilter()
        f.setOutputDelayMs(-500.0)
        assertEquals(0.0, f.outputDelayMs)
    }

    @Test
    fun `above the spec ceiling clamps to 5000`() {
        val f = SendspinTimeFilter()
        f.setOutputDelayMs(9999.0)
        assertEquals(5000.0, f.outputDelayMs)
    }

    /**
     * The distinction the whole change rests on: setting an output delay must
     * not disturb the internally-compensated static delay, and vice versa.
     */
    @Test
    fun `output delay and static delay are independent`() {
        val f = SendspinTimeFilter()
        f.setUserSyncOffsetMs(40.0)
        f.setOutputDelayMs(100.0)
        assertEquals(40.0, f.staticDelayMs)
        assertEquals(100.0, f.outputDelayMs)
    }
}
