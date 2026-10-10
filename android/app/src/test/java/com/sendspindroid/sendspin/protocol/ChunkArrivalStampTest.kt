package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.NoiseCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

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

    /** A channel on which decrypting a frame takes 200 ms. */
    private val slowDecrypt = object : NoiseCrypto {
        override fun encrypt(plaintext: ByteArray) = plaintext
        override fun decrypt(frame: ByteArray): ByteArray {
            Thread.sleep(200)
            return frame
        }
    }

    @Before
    fun setUp() {
        handler = TestProtocolHandler()
        handler.setHandshakeCompleteForTest()
        handler.handleTextMessageForTest(
            """{"type":"server/activate","payload":{"activities":["playback"],"active_roles":["player@v1"]}}"""
        )
        handler.handleTextMessageForTest(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,""" +
                """"channels":2,"bit_depth":16}}}"""
        )
        // A clock filter that has converged on "server time equals ours".
        val filter = handler.exposedTimeFilter()
        val now = System.nanoTime() / 1000
        for (i in 5 downTo 1) filter.addMeasurement(0, 1000, now - i * 1000)
        assertTrue(filter.isConverged)
        handler.installEncryptedChannel(slowDecrypt)
    }

    /** An audio chunk frame the server sent this instant: `[4][timestamp][send_ahead][audio]`. */
    private fun chunkSentNow(): ByteArray {
        val sendAhead = 1_100_000
        return ByteBuffer.allocate(17)
            .put(4)
            .putLong(System.nanoTime() / 1000 + sendAhead)
            .putInt(sendAhead)
            .array()
    }

    @Test
    fun `a chunk's delay is measured to when its frame arrived, not to when it was decrypted`() {
        // Each frame arrives the instant the server sent it and then takes
        // 200 ms to decrypt. That is the client's time, not the network's.
        repeat(3) { handler.handleBinaryMessageForTest(chunkSentNow()) }

        assertEquals(3, handler.audioChunks.size)
        // The floor alone: no network delay to add. Measured to the time of
        // processing it would be the floor plus 200 ms.
        assertEquals(350, handler.protocolStats().minBufferMs)
    }
}
