package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.message.FragmentWriter
import com.sendspindroid.sendspin.protocol.message.Fragmentation
import org.junit.Assert.*
import org.junit.Test

/**
 * Fragmentation as seen through [NoiseWireCodec].
 *
 * The codec is the only place that sees decrypted plaintexts, so it is where
 * reassembly has to happen. These tests use identity "crypto" so the framing is
 * the only thing under test - the real AEAD is covered by the handshake driver's
 * golden vectors.
 */
class NoiseWireCodecFragmentationTest {

    /** A codec whose encrypt/decrypt are the identity, so plaintext == frame. */
    private fun plaintextCodec() = NoiseWireCodec(
        object : com.sendspindroid.sendspin.crypto.NoiseCrypto {
            override fun encrypt(plaintext: ByteArray) = plaintext
            override fun decrypt(frame: ByteArray) = frame
        }
    )

    @Test
    fun aFragmentedJsonMessageIsReassembledIntoJson() {
        // orig_type 0 must come back out as Json, not as a Typed frame of type 0.
        val codec = plaintextCodec()
        // Padded with spaces past the single-message limit, so it is still
        // valid JSON and has to be fragmented.
        val json = """{"type":"server/state","payload":{}}""" + " ".repeat(70_000)
        val frames = FragmentWriter.frames(
            SendSpinProtocol.BinaryType.JSON,
            json.encodeToByteArray(),
        )
        assertTrue("precondition: should have fragmented", frames.size > 1)

        var last: NoiseWireCodec.Decoded? = null
        for (frame in frames) last = codec.decode(frame)
        assertTrue(last is NoiseWireCodec.Decoded.Json)
        assertEquals(json, (last as NoiseWireCodec.Decoded.Json).text)
    }

    @Test
    fun aFragmentedAudioMessageIsReassembledAsTyped() {
        val codec = plaintextCodec()
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val frames = FragmentWriter.frames(SendSpinProtocol.BinaryType.AUDIO, payload)
        assertTrue("precondition: should have fragmented", frames.size > 2)

        val results = frames.map { codec.decode(it) }
        // Every frame but the last buffers silently.
        for (r in results.dropLast(1)) {
            assertTrue("expected Buffered, got $r", r is NoiseWireCodec.Decoded.Buffered)
        }
        val complete = results.last()
        assertTrue(complete is NoiseWireCodec.Decoded.Typed)
        complete as NoiseWireCodec.Decoded.Typed
        assertEquals(SendSpinProtocol.BinaryType.AUDIO, complete.type)
        assertArrayEquals(payload, complete.body)
    }

    @Test
    fun anUnfragmentedFrameStillDecodesNormally() {
        val codec = plaintextCodec()
        val decoded = codec.decode(
            byteArrayOf(SendSpinProtocol.BinaryType.AUDIO.toByte(), 1, 2, 3)
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.Typed)
        assertArrayEquals(byteArrayOf(1, 2, 3), (decoded as NoiseWireCodec.Decoded.Typed).body)
    }

    @Test
    fun aMalformedFragmentSequenceIsAProtocolError() {
        // A last fragment with nothing in flight. The caller closes the socket
        // on this, so it must not be swallowed as an ignorable unknown frame.
        val codec = plaintextCodec()
        val decoded = codec.decode(
            byteArrayOf(
                SendSpinProtocol.BinaryType.FRAGMENT.toByte(),
                Fragmentation.FLAG_LAST.toByte(),
                0xA,
            )
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.ProtocolError)
    }

    @Test
    fun theReservedTypesTwoAndThreeAreDeliveredAsUnknownTypes() {
        // The pre-rc1 fragment IDs. Now reserved: they reach the caller as
        // ordinary typed messages, which it ignores like any unknown ID, and
        // they neither start nor disturb a reassembly.
        val codec = plaintextCodec()
        for (type in listOf(2, 3)) {
            val decoded = codec.decode(byteArrayOf(type.toByte(), 0, 0xA))
            assertTrue("type $type: got $decoded", decoded is NoiseWireCodec.Decoded.Typed)
            assertEquals(type, (decoded as NoiseWireCodec.Decoded.Typed).type)
        }
    }

    @Test
    fun aNonFragmentFrameArrivingMidSequenceIsAProtocolError() {
        val codec = plaintextCodec()
        codec.decode(
            byteArrayOf(
                SendSpinProtocol.BinaryType.FRAGMENT.toByte(),
                Fragmentation.FLAG_FIRST.toByte(),
                0,
                0xA,
            )
        )
        val decoded = codec.decode(
            byteArrayOf(SendSpinProtocol.BinaryType.AUDIO.toByte(), 1, 2, 3)
        )
        assertTrue(decoded is NoiseWireCodec.Decoded.ProtocolError)
    }

    @Test
    fun encodeSplitsAnOversizePayloadAndKeepsEveryFrameUnderTheNoiseLimit() {
        val codec = plaintextCodec()
        val frames = kotlinx.coroutines.runBlocking {
            codec.encode(SendSpinProtocol.BinaryType.AUDIO, ByteArray(200_000))
        }
        assertTrue("should have fragmented", frames.size > 2)
        for (f in frames) {
            assertTrue("frame too big", f.size <= SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE)
        }
    }

    @Test
    fun whatEncodeFragmentsDecodeReassembles() {
        val payload = ByteArray(300_000) { (it % 253).toByte() }
        val frames = kotlinx.coroutines.runBlocking {
            plaintextCodec().encode(SendSpinProtocol.BinaryType.AUDIO, payload)
        }
        val receiver = plaintextCodec()
        val complete = frames.map { receiver.decode(it) }.last()
        assertArrayEquals(payload, (complete as NoiseWireCodec.Decoded.Typed).body)
    }
}
