package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Receiver side of `messaging.md#fragmentation`.
 *
 * Pure byte manipulation, so no Log mocking is needed here - the reassembler
 * reports problems by returning [FragmentReassembler.Result.ProtocolError],
 * never by logging and continuing. A malformed sequence MUST close the
 * connection, so silently swallowing one would be a spec violation.
 *
 * Bodies below are what follows the type byte: `[flags][orig_type][data]` on a
 * first fragment, `[flags][data]` on any other.
 */
class FragmentReassemblerTest {

    private val fragment = SendSpinProtocol.BinaryType.FRAGMENT
    private val first = Fragmentation.FLAG_FIRST   // 0x02
    private val last = Fragmentation.FLAG_LAST     // 0x01
    private val middle = 0

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assertError(result: FragmentReassembler.Result) =
        assertTrue("expected a protocol error, got $result", result is FragmentReassembler.Result.ProtocolError)

    @Test
    fun threeFragmentSequenceReassemblesJsonBody() {
        val r = FragmentReassembler()
        assertEquals(
            FragmentReassembler.Result.Buffered,
            r.accept(fragment, bytes(first, 0, 0x7B, 0x22))  // orig_type 0 (JSON), then {"
        )
        assertEquals(
            FragmentReassembler.Result.Buffered,
            r.accept(fragment, bytes(middle, 0x61, 0x22))    // a"
        )
        val result = r.accept(fragment, bytes(last, 0x7D))   // }
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(0, result.type)
        assertArrayEquals(bytes(0x7B, 0x22, 0x61, 0x22, 0x7D), result.body)
    }

    @Test
    fun firstThenLastCompletes() {
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 4, 0xAA))
        val result = r.accept(fragment, bytes(last, 0xBB))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(4, result.type)
        assertArrayEquals(bytes(0xAA, 0xBB), result.body)
    }

    @Test
    fun aSingleFragmentThatIsBothFirstAndLastCompletes() {
        // Nothing forbids setting both bits: "On a first fragment, read
        // orig_type ... When bit 0 is set, dispatch the buffer."
        val r = FragmentReassembler()
        val result = r.accept(fragment, bytes(first or last, 4, 0xAA, 0xBB))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(4, result.type)
        assertArrayEquals(bytes(0xAA, 0xBB), result.body)
        assertFalse(r.inFlight)
    }

    @Test
    fun emptyLastFragmentCompletes() {
        // A last fragment carrying no data is legal: it terminates the message.
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 8, 0x01, 0x02))
        val result = r.accept(fragment, bytes(last))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(8, result.type)
        assertArrayEquals(bytes(0x01, 0x02), result.body)
    }

    @Test
    fun aNonFirstFragmentCarriesOnlyDataAfterItsFlags() {
        // The subtle one: only a first fragment has an orig_type byte, so a
        // leading 0x01 after the flags of a later fragment is payload.
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        r.accept(fragment, bytes(middle, 0x01))
        val result = r.accept(fragment, bytes(last))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(0, result.type)
        assertArrayEquals(bytes(0xAA, 0x01), result.body)
    }

    // ---- The five malformed sequences messaging.md lists ----

    @Test
    fun aFirstFragmentWhileOneIsInFlightIsProtocolError() {
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        assertError(r.accept(fragment, bytes(first, 0, 0xBB)))
    }

    @Test
    fun aNonFirstFragmentWithNoneInFlightIsProtocolError() {
        assertError(FragmentReassembler().accept(fragment, bytes(middle, 0xAA)))
        assertError(FragmentReassembler().accept(fragment, bytes(last, 0xAA)))
    }

    @Test
    fun aNonFragmentMessageWhileOneIsInFlightIsProtocolError() {
        // Type 4 is audio.
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        assertError(r.accept(4, bytes(0xBB)))
    }

    @Test
    fun aNonzeroReservedFlagBitIsProtocolError() {
        // "Bits 2-7 are reserved and MUST be zero." Each one, on a first
        // fragment and on a later one.
        for (bit in 2..7) {
            val reserved = 1 shl bit
            assertError(FragmentReassembler().accept(fragment, bytes(first or reserved, 0, 0xAA)))

            val r = FragmentReassembler()
            r.accept(fragment, bytes(first, 0, 0xAA))
            assertError(r.accept(fragment, bytes(last or reserved, 0xBB)))
        }
    }

    @Test
    fun anOrigTypeOfOneIsProtocolError() {
        // "A sender MUST NOT use 1 as orig_type."
        assertError(FragmentReassembler().accept(fragment, bytes(first, 1, 0xAA)))
    }

    // ---- Around them ----

    @Test
    fun aProtocolErrorDropsTheInFlightMessage() {
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        assertError(r.accept(4, bytes(0xBB)))
        assertFalse(r.inFlight)
    }

    @Test
    fun aFragmentTooShortForItsHeaderIsProtocolError() {
        // No flags byte at all, and a first fragment with no room for orig_type.
        assertError(FragmentReassembler().accept(fragment, ByteArray(0)))
        assertError(FragmentReassembler().accept(fragment, bytes(first)))
    }

    @Test
    fun theReservedTypesTwoAndThreePassThroughLikeAnyOtherType() {
        // "IDs 2-3: Reserved for future use." They were the fragment types
        // before 1.0.0-rc1; now they are unknown IDs the caller ignores.
        val r = FragmentReassembler()
        assertEquals(FragmentReassembler.Result.Passthrough, r.accept(2, bytes(0, 0xAA)))
        assertEquals(FragmentReassembler.Result.Passthrough, r.accept(3, bytes(0xAA)))
        assertFalse(r.inFlight)
    }

    @Test
    fun anOrigTypeOfTwoOrThreeIsAccepted() {
        // Only 1 is forbidden as orig_type.
        for (orig in listOf(2, 3)) {
            val result = FragmentReassembler().accept(fragment, bytes(first or last, orig, 0xAA))
            assertEquals(orig, (result as FragmentReassembler.Result.Complete).type)
        }
    }

    @Test
    fun nonFragmentMessageWithNothingInFlightPassesThrough() {
        // Lets the codec hand every frame to the reassembler rather than
        // duplicating the "is this fragment-related?" test at the call site.
        val r = FragmentReassembler()
        assertEquals(FragmentReassembler.Result.Passthrough, r.accept(4, bytes(0xAA)))
    }

    @Test
    fun exceedingSizeCapIsProtocolErrorNotOom() {
        // The spec sets no cap; an unbounded reassembly buffer is a remote
        // memory-exhaustion vector on a phone. Exceeding it must be an error,
        // never a silent truncation.
        val r = FragmentReassembler(maxMessageBytes = 1024)
        r.accept(fragment, bytes(first, 0) + ByteArray(600))
        assertError(r.accept(fragment, bytes(middle) + ByteArray(600)))
    }

    @Test
    fun resetClearsInFlightState() {
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        r.reset()
        // With nothing in flight a first fragment is accepted again.
        assertEquals(FragmentReassembler.Result.Buffered, r.accept(fragment, bytes(first, 4, 0xBB)))
        val result = r.accept(fragment, bytes(last))
        assertTrue(result is FragmentReassembler.Result.Complete)
        assertEquals(4, (result as FragmentReassembler.Result.Complete).type)
    }

    @Test
    fun completingAMessageClearsStateForTheNext() {
        val r = FragmentReassembler()
        r.accept(fragment, bytes(first, 0, 0xAA))
        r.accept(fragment, bytes(last))
        // A second, independent message must reassemble cleanly.
        r.accept(fragment, bytes(first, 16, 0xCC))
        val result = r.accept(fragment, bytes(last, 0xDD))
        assertTrue(result is FragmentReassembler.Result.Complete)
        result as FragmentReassembler.Result.Complete
        assertEquals(16, result.type)
        assertArrayEquals(bytes(0xCC, 0xDD), result.body)
    }
}
