package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Message fragmentation, `messaging.md#fragmentation`.
 *
 * Noise caps a transport message at 65535 bytes, so any Sendspin message whose
 * payload exceeds [Fragmentation.MAX_UNFRAGMENTED_PAYLOAD] must be split across
 * fragment messages, all of type `1`:
 *
 *     first:      [1][flags][orig_type][data]
 *     subsequent: [1][flags][data]
 *
 * Bit 1 of `flags` marks the first fragment and bit 0 the last.
 *
 * Both halves live here because they are exact inverses and are round-tripped
 * against each other in tests.
 */
object Fragmentation {

    /** Noise's own per-transport-message limit. */
    const val NOISE_MAX_MESSAGE = SendSpinProtocol.NoiseFraming.MAX_TRANSPORT_MESSAGE

    /** Both defined cipher suites use a 16-byte AEAD tag. */
    const val AEAD_TAG = SendSpinProtocol.NoiseFraming.AEAD_TAG

    /** The most plaintext one frame can carry: 65519. */
    const val MAX_PLAINTEXT = SendSpinProtocol.NoiseFraming.MAX_PLAINTEXT

    /** `[type][payload]`, so 65518. */
    const val MAX_UNFRAGMENTED_PAYLOAD = SendSpinProtocol.NoiseFraming.MAX_PAYLOAD

    /** First fragment is `[1][flags][orig_type][data]`, so 65516. */
    const val MAX_FIRST_FRAGMENT_DATA = MAX_PLAINTEXT - 3

    /** Subsequent fragments are `[1][flags][data]`, so 65517. */
    const val MAX_FRAGMENT_DATA = MAX_PLAINTEXT - 2

    /** Set on the last fragment of a message. */
    const val FLAG_LAST = 0x01

    /** Set on the first fragment of a message. */
    const val FLAG_FIRST = 0x02

    /** "Bits 2-7 are reserved and MUST be zero." */
    const val FLAGS_RESERVED = 0xFC

    /**
     * Default ceiling on a single reassembled message.
     *
     * The spec sets no cap. Without one a peer can hold the connection open
     * sending fragments forever and exhaust memory on a phone, so exceeding
     * this is a protocol error rather than a silent truncation.
     */
    const val DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024 * 1024
}

/**
 * Splits an outbound message into frames.
 *
 * Returns AEAD *plaintexts*; the Noise codec encrypts each one. The whole list
 * must go on the wire consecutively, because "a sender MUST finish a fragmented
 * message with a last fragment before sending any other binary message in that
 * direction" - which is why this returns a list rather than streaming.
 */
object FragmentWriter {

    /**
     * @param type the message's real type; MUST NOT be `1`
     * @return one `[type][payload]` frame when it fits, otherwise a first
     *   fragment, zero or more middle fragments, and a last fragment
     */
    fun frames(type: Int, payload: ByteArray): List<ByteArray> {
        require(type in 0..255) { "binary message type is a uint8, got $type" }
        require(type != SendSpinProtocol.BinaryType.FRAGMENT) {
            "the fragment type may never be used as orig_type"
        }

        if (1 + payload.size <= Fragmentation.MAX_PLAINTEXT) {
            return listOf(byteArrayOf(type.toByte()) + payload)
        }

        val fragmentType = SendSpinProtocol.BinaryType.FRAGMENT.toByte()
        val out = mutableListOf<ByteArray>()
        // The first fragment spends one byte on orig_type, so it carries less
        // data than the ones that follow it.
        var offset = Fragmentation.MAX_FIRST_FRAGMENT_DATA
        out += byteArrayOf(fragmentType, Fragmentation.FLAG_FIRST.toByte(), type.toByte()) +
            payload.copyOfRange(0, offset)

        while (offset < payload.size) {
            val end = minOf(offset + Fragmentation.MAX_FRAGMENT_DATA, payload.size)
            val flags = if (end == payload.size) Fragmentation.FLAG_LAST else 0
            out += byteArrayOf(fragmentType, flags.toByte()) + payload.copyOfRange(offset, end)
            offset = end
        }
        return out
    }
}

/**
 * Reassembles one direction of a connection.
 *
 * Stateful and single-direction, so one instance per connection per direction.
 * "Only one fragmented message may be in flight at a time per direction", which
 * is why a single buffer and a single [origType] suffice.
 *
 * Chunks are accumulated and concatenated once on completion rather than
 * regrown per frame: a 300 KB message arrives as five frames, and repeated
 * array copying would make reassembly quadratic in message size.
 */
class FragmentReassembler(
    private val maxMessageBytes: Int = Fragmentation.DEFAULT_MAX_MESSAGE_BYTES,
) {

    sealed class Result {
        /** Dispatch this as a whole message of [type]. */
        data class Complete(val type: Int, val body: ByteArray) : Result() {
            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is Complete && type == other.type && body.contentEquals(other.body))

            override fun hashCode(): Int = 31 * type + body.contentHashCode()
        }

        /** Fragment consumed; nothing to dispatch yet. */
        object Buffered : Result()

        /** Not a fragment and nothing in flight: dispatch normally. */
        object Passthrough : Result()

        /** MUST close the connection. */
        data class ProtocolError(val reason: String) : Result()
    }

    private var origType: Int? = null
    private val chunks = mutableListOf<ByteArray>()
    private var buffered = 0

    /** Whether a fragmented message is currently being reassembled. */
    val inFlight: Boolean get() = origType != null

    /**
     * Feed one decrypted frame, type byte already stripped.
     *
     * The malformed sequences are the five the spec lists: "a first fragment
     * received while a fragmented message is in flight, a non-first fragment
     * received with none in flight, a non-fragment binary message received
     * while a fragmented message is in flight, a nonzero reserved flag bit, and
     * an `orig_type` of `1`". A fragment too short to carry its own header
     * cannot be parsed at all and is rejected the same way.
     *
     * @param type the frame's own type byte
     * @param body everything after it
     */
    fun accept(type: Int, body: ByteArray): Result {
        if (type != SendSpinProtocol.BinaryType.FRAGMENT) {
            if (inFlight) {
                return error(
                    "non-fragment message (type $type) while a fragmented message is in flight"
                )
            }
            return Result.Passthrough
        }

        if (body.isEmpty()) return error("fragment carries no flags byte")
        val flags = body[0].toInt() and 0xFF
        if (flags and Fragmentation.FLAGS_RESERVED != 0) {
            return error("fragment flags $flags set a reserved bit")
        }

        val dataOffset: Int
        if (flags and Fragmentation.FLAG_FIRST != 0) {
            if (inFlight) return error("first fragment while a fragmented message is in flight")
            if (body.size < 2) return error("first fragment carries no orig_type")
            val orig = body[1].toInt() and 0xFF
            if (orig == SendSpinProtocol.BinaryType.FRAGMENT) {
                return error("first fragment has an orig_type of 1")
            }
            origType = orig
            dataOffset = 2
        } else {
            if (!inFlight) return error("non-first fragment with no fragmented message in flight")
            dataOffset = 1
        }

        if (buffered + body.size - dataOffset > maxMessageBytes) {
            return error("fragmented message exceeds the ${maxMessageBytes}-byte reassembly cap")
        }
        chunks.add(body.copyOfRange(dataOffset, body.size))
        buffered += body.size - dataOffset

        if (flags and Fragmentation.FLAG_LAST == 0) return Result.Buffered
        val complete = Result.Complete(origType!!, concatenate())
        reset()
        return complete
    }

    /** Drop any in-flight message. Call on reconnect and after a re-handshake. */
    fun reset() {
        origType = null
        chunks.clear()
        buffered = 0
    }

    private fun error(reason: String): Result {
        reset()
        return Result.ProtocolError(reason)
    }

    private fun concatenate(): ByteArray {
        val out = ByteArray(buffered)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}
