package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.message.BinaryMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * messaging.md, "Receive timestamps": "A receiver's receive time for a message
 * is when the message's last byte arrived at the transport ... This applies to
 * ... the player's `arrival` for audio chunks. Receivers ... MUST NOT take it
 * later than when their WebSocket implementation delivers the WebSocket
 * message carrying that last byte."
 *
 * `min_buffer_ms` is sized from `arrival - compute_client_time(timestamp -
 * send_ahead)` (roles/player/v1.md, "Measuring timing parameters"), so an
 * arrival read after the chunk was decrypted and parsed counts the client's
 * own processing as network delay.
 */
class ChunkArrivalStampTest {

    private lateinit var handler: TestProtocolHandler

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        // A clock filter that has converged on "server time equals ours".
        val filter = handler.exposedTimeFilter()
        val now = System.nanoTime() / 1000
        for (i in 5 downTo 1) filter.addMeasurement(0, 1000, now - i * 1000)
        assertTrue(filter.isConverged)
    }

    private fun setFrameReceivedAt(micros: Long) {
        SendSpinProtocolHandler::class.java.getDeclaredField("frameReceivedAtMicros")
            .apply { isAccessible = true }
            .setLong(handler, micros)
    }

    private fun measure(chunk: BinaryMessageParser.BinaryMessage.Audio) {
        SendSpinProtocolHandler::class.java
            .getDeclaredMethod("measureChunkDelay", BinaryMessageParser.BinaryMessage.Audio::class.java)
            .apply { isAccessible = true }
            .invoke(handler, chunk)
    }

    @Test
    fun `a chunk's delay is measured to when its frame arrived, not to when it is processed`() {
        // Two chunks 20 ms apart whose frames arrived five seconds ago, each
        // 100 ms after the server sent it. Whatever happened to them since
        // (decryption, parsing, a busy thread) is not network delay.
        val arrived = System.nanoTime() / 1000 - 5_000_000
        val sendAhead = 1_100_000L
        for (i in 0..1) {
            val arrival = arrived + i * 20_000
            setFrameReceivedAt(arrival)
            // Sent at `timestamp - send_ahead`, which is 100 ms before it arrived.
            measure(
                BinaryMessageParser.BinaryMessage.Audio(
                    timestampMicros = arrival - 100_000 + sendAhead,
                    sendAheadMicros = sendAhead,
                    payload = ByteArray(4),
                )
            )
        }

        // The 350 ms floor plus the 100 ms the network took. Measured to the
        // time of processing it would be the floor plus about 5100 ms.
        assertEquals(450, handler.protocolStats().minBufferMs)
    }
}
