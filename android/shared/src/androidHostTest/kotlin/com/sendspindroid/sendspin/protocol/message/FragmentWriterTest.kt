package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Sender side of `messaging.md#fragmentation`.
 *
 * The writer emits AEAD *plaintexts* - `[type][data]` - which the Noise codec
 * then encrypts one at a time. Nothing here knows about encryption.
 */
class FragmentWriterTest {

    private val fragment = SendSpinProtocol.BinaryType.FRAGMENT
    private val first = Fragmentation.FLAG_FIRST
    private val last = Fragmentation.FLAG_LAST

    private fun ByteArray.u(index: Int) = this[index].toInt() and 0xFF

    @Test
    fun payloadOf65518BytesProducesExactlyOneUnfragmentedFrame() {
        // "Senders SHOULD NOT fragment messages that fit in a single Noise
        // transport message." 1 + 65518 == 65519 == MAX_PLAINTEXT.
        val frames = FragmentWriter.frames(4, ByteArray(Fragmentation.MAX_UNFRAGMENTED_PAYLOAD))
        assertEquals(1, frames.size)
        assertEquals(Fragmentation.MAX_PLAINTEXT, frames[0].size)
        assertEquals(4, frames[0].u(0))
    }

    @Test
    fun payloadOf65519BytesProducesTwoFrames() {
        // One byte over. The first fragment spends two bytes on flags and
        // orig_type, so it carries 65516 data; the 3-byte remainder rides the
        // last fragment.
        val frames = FragmentWriter.frames(4, ByteArray(Fragmentation.MAX_UNFRAGMENTED_PAYLOAD + 1))
        assertEquals(2, frames.size)
        assertEquals(Fragmentation.MAX_PLAINTEXT, frames[0].size)
        assertEquals(fragment, frames[0].u(0))
        assertEquals(first, frames[0].u(1))
        assertEquals(4, frames[0].u(2))                   // orig_type
        assertEquals(5, frames[1].size)                   // [1][flags] + 3 data bytes
        assertEquals(fragment, frames[1].u(0))
        assertEquals(last, frames[1].u(1))
    }

    @Test
    fun writerNeverProducesPlaintextOverTheNoiseLimit() {
        for (size in listOf(0, 1, 1000, 65517, 65518, 65519, 65520, 131072, 300000)) {
            for (frame in FragmentWriter.frames(0, ByteArray(size))) {
                assertTrue(
                    "payload $size produced a ${frame.size}-byte plaintext",
                    frame.size <= Fragmentation.MAX_PLAINTEXT
                )
            }
        }
    }

    @Test
    fun everyFragmentIsTypeOneAndOnlyTheFirstAndLastAreFlagged() {
        // "First fragment: [1][flags][orig_type][data]. Subsequent fragments:
        // [1][flags][data]. Bit 1 is set on the first fragment of a message and
        // bit 0 on the last. Bits 2-7 are reserved and MUST be zero."
        val frames = FragmentWriter.frames(8, ByteArray(200_000))
        assertTrue("should have fragmented", frames.size > 2)
        for (frame in frames) {
            assertEquals(fragment, frame.u(0))
            assertEquals(0, frame.u(1) and Fragmentation.FLAGS_RESERVED)
        }
        assertEquals(first, frames.first().u(1))
        assertEquals(8, frames.first().u(2))  // orig_type, first fragment only
        for (middle in frames.subList(1, frames.size - 1)) {
            assertEquals(0, middle.u(1))
        }
        assertEquals(last, frames.last().u(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writerRejectsTheFragmentTypeAsOrigType() {
        // "A sender MUST NOT use 1 as orig_type."
        FragmentWriter.frames(fragment, ByteArray(10))
    }

    @Test
    fun theReservedTypesTwoAndThreeAreOrdinaryTypesToTheWriter() {
        // 2 and 3 were the fragment types before 1.0.0-rc1. They are now just
        // reserved IDs with no framing meaning.
        for (type in listOf(2, 3)) {
            val frames = FragmentWriter.frames(type, ByteArray(10))
            assertEquals(1, frames.size)
            assertEquals(type, frames[0].u(0))
        }
    }

    /** One frame the reference produced: its length, header bytes and SHA-256. */
    private class Frame(val size: Int, val headerHex: String, val sha256: String)

    private class Vector(val origType: Int, val payloadSize: Int, val frames: List<Frame>)

    /**
     * Output of `aiosendspin.noise.wire._fragment` (aiosendspin 10.0.0) for the
     * payload `byte[i] = (i * 31 + orig_type) and 0xFF`. The two type-8 sizes
     * straddle the point where the data exactly fills two fragments.
     */
    private val referenceVectors = listOf(
        Vector(4, 65519, listOf(
            Frame(65519, "010204", "a708dda68c86246a4a867b8cad40fba25549fe135645faeb049155194f05257c"),
            Frame(5, "0101", "0902a5e6549a35963817455e7a59bd61a90745b7a76269a9b14d5a608e815618"),
        )),
        Vector(0, 131072, listOf(
            Frame(65519, "010200", "fd80752a2ef3d4d2933c9fa8616408e59104ba58b7b6deb840ccde4165286d77"),
            Frame(65519, "0100", "69e39e6ef00a4a6ef5146e096db55874ec1cb3d9c1236d844659edab6e835db4"),
            Frame(41, "0101", "69946489d2c8e558ceb49db60b325fc07e6553c3cfc1c5a492e6389c05b93c4b"),
        )),
        Vector(4, 200000, listOf(
            Frame(65519, "010204", "a708dda68c86246a4a867b8cad40fba25549fe135645faeb049155194f05257c"),
            Frame(65519, "0100", "95341d21a9e6e29744f05a16f41a2c94a5c1460127b832e0a1361b9be460356d"),
            Frame(65519, "0100", "447dceabad2020755cddcc4d1b89cb33aac9e9c5adb01af8a4e4fff8daba5f46"),
            Frame(3452, "0101", "f92893e487f94a5fddf155d944f1b2ba2d80f0836741520371e6eefe91c74eec"),
        )),
        Vector(8, 131033, listOf(
            Frame(65519, "010208", "1b07234bebfdc0d10f7fe6c073850bcbfad61d1f74a1ca573e6be5f197efe2c3"),
            Frame(65519, "0101", "fe888e67e37a05d04dda8c3d07a47732deb7b5d97425da96fddc2a2d31dd01d3"),
        )),
        Vector(8, 131034, listOf(
            Frame(65519, "010208", "1b07234bebfdc0d10f7fe6c073850bcbfad61d1f74a1ca573e6be5f197efe2c3"),
            Frame(65519, "0100", "d3eef3b36f2071e023c9b61d46464c2b539bae4e9f5086a1b435632a4288a1e2"),
            Frame(3, "0101", "b1eb1353aa152336e87b939fe90cbd3c428e0c641238b5ab9d3efb49c5f5e7ab"),
        )),
    )

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun writerMatchesTheReferenceImplementationByteForByte() {
        for (vector in referenceVectors) {
            val label = "orig_type ${vector.origType} size ${vector.payloadSize}"
            val payload = ByteArray(vector.payloadSize) { (it * 31 + vector.origType).toByte() }
            val frames = FragmentWriter.frames(vector.origType, payload)
            assertEquals(label, vector.frames.size, frames.size)
            for ((index, expected) in vector.frames.withIndex()) {
                val frame = frames[index]
                assertEquals("$label frame $index size", expected.size, frame.size)
                assertEquals(
                    "$label frame $index header",
                    expected.headerHex,
                    frame.copyOfRange(0, expected.headerHex.length / 2).hex(),
                )
                assertEquals(
                    "$label frame $index bytes",
                    expected.sha256,
                    MessageDigest.getInstance("SHA-256").digest(frame).hex(),
                )
            }
        }
    }

    @Test
    fun roundTripWriterThenReassembler() {
        val sizes = listOf(0, 1, 1000, 65517, 65518, 65519, 131033, 131034, 131072, 300000)
        val types = listOf(0, 4, 8, 16)
        for (type in types) {
            for (size in sizes) {
                val payload = ByteArray(size) { (it * 31 + type).toByte() }
                val frames = FragmentWriter.frames(type, payload)
                val r = FragmentReassembler()
                var delivered: Pair<Int, ByteArray>? = null
                for (frame in frames) {
                    val frameType = frame[0].toInt() and 0xFF
                    val body = frame.copyOfRange(1, frame.size)
                    when (val result = r.accept(frameType, body)) {
                        is FragmentReassembler.Result.Complete ->
                            delivered = result.type to result.body
                        // A message small enough not to need fragmenting comes
                        // straight back out as itself.
                        FragmentReassembler.Result.Passthrough ->
                            delivered = frameType to body
                        FragmentReassembler.Result.Buffered -> {}
                        is FragmentReassembler.Result.ProtocolError ->
                            fail("type $type size $size: ${result.reason}")
                    }
                }
                assertNotNull("type $type size $size never completed", delivered)
                assertEquals("type $type size $size", type, delivered!!.first)
                assertArrayEquals("type $type size $size", payload, delivered.second)
            }
        }
    }
}
