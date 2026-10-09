package com.sendspindroid.sendspin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * The clock-sync round trip is measured between the moment a `client/time`
 * leaves and the moment its `server/time` arrives. Anything the client does on
 * its own side of those two moments (queueing, encrypting, decrypting,
 * parsing) would otherwise be counted as network delay, and half of it as
 * measurement uncertainty.
 */
class TimeSyncWireStampTest {

    private lateinit var handler: TestProtocolHandler

    private val activate = """{"type":"server/activate","payload":{"activities":[],""" +
        """"active_roles":["player@v1"]}}"""

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        handler.setHandshakeCompleteForTest()
        handler.handleTextMessageForTest(activate)
        handler.startTimeSyncForTest()
        handler.sentMessages.clear()
    }

    private fun nowMicros() = System.nanoTime() / 1000

    /** A `server/time` frame as the transport delivers it: `[0][json]` on the plaintext channel. */
    private fun deliverServerTime(id: Long, serverMicros: Long) {
        val json = """{"type":"server/time","payload":{"client_transmitted":$id,""" +
            """"server_received":$serverMicros,"server_transmitted":$serverMicros}}"""
        handler.handleBinaryMessageForTest(byteArrayOf(0) + json.encodeToByteArray())
    }

    /**
     * Starting time sync opens a burst, and a reply inside a burst is only
     * handed to the filter when the burst ends: let it end.
     */
    private fun finishBurst() {
        handler.testScheduler.advanceTimeBy(2_000)
        handler.testScheduler.runCurrent()
    }

    private fun sentClientTimeId(): Long =
        Json.parseToJsonElement(handler.sentMessages.last { "client/time" in it })
            .jsonObject["payload"]!!.jsonObject["client_transmitted"]!!.jsonPrimitive.long

    @Test
    fun `the round trip starts when the frame leaves, not when the message was built`() {
        handler.holdWrites = true
        handler.sendClientTimeForTest()
        assertEquals("the frame is waiting in the send queue", 1, handler.heldWrites.size)

        // The time the message spends queued behind other sends.
        Thread.sleep(60)
        val left = nowMicros()
        handler.heldWrites.single().invoke()

        // A server whose clock reads 0 answers at once, so the offset the
        // filter takes from this one exchange is minus the midpoint of the
        // send and receive times. Measured from when the message was built,
        // that midpoint would be 30 ms earlier.
        deliverServerTime(sentClientTimeId(), serverMicros = 0)
        val arrived = nowMicros()
        finishBurst()

        val filter = handler.exposedTimeFilter()
        assertEquals(1, filter.measurementCountValue)
        val midpoint = (left + arrived) / 2
        val error = abs(-filter.offsetMicros - midpoint)
        assertTrue("offset is ${error}us from the wire midpoint", error < 10_000)
    }

    @Test
    fun `a reply that echoes an id we did not stamp is measured from the echoed value`() {
        // What the round trip was measured from before, and still the right
        // answer for a reply we hold no send time for.
        val echoed = nowMicros()
        deliverServerTime(echoed, serverMicros = 0)
        val arrived = nowMicros()
        finishBurst()

        val filter = handler.exposedTimeFilter()
        assertEquals(1, filter.measurementCountValue)
        val error = abs(-filter.offsetMicros - (echoed + arrived) / 2)
        assertTrue("offset is ${error}us from the midpoint", error < 10_000)
    }
}
