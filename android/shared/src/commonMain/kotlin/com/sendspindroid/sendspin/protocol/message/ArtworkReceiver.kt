package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Receiver side of the artwork transfers in `roles/artwork/v1.md`.
 *
 * An image arrives as an announce followed by the parts of the encoded image,
 * all on its channel (binary type 8-11 for channel 0-3):
 *
 *     announce: [type][flags][timestamp][total_size]
 *     part:     [type][flags][data]
 *     cancel:   [type][flags]
 *
 * Bit 0 of `flags` marks a cancel and bit 1 an announce; a part sets neither.
 *
 * Stateful, one instance per connection. "At most one image transfer is in
 * flight at a time across all of the role's channels", which is why a single
 * buffer suffices. This class only reassembles; deciding when a complete image
 * is shown belongs to the caller.
 */
class ArtworkReceiver {

    sealed class Result {
        /** Nothing to act on: a part of a transfer that is not complete yet. */
        object None : Result()

        /** The channel's pending image, if any, is discarded. */
        data class Discard(val channel: Int) : Result()

        /**
         * A complete image, replacing the channel's pending image. An empty
         * [data] clears the channel. It becomes current at [timestampMicros],
         * which is on the server clock.
         */
        class Image(val channel: Int, val timestampMicros: Long, val data: ByteArray) : Result()

        /** The spec requires closing the connection. */
        data class ProtocolError(val reason: String) : Result()
    }

    private var channel = -1
    private var timestampMicros = 0L
    private var buffer: ByteArray? = null
    private var received = 0

    /** Forget a transfer in flight. Its stream ended or the connection was replaced. */
    fun reset() {
        buffer = null
    }

    /**
     * The "malformed messages" of the spec, which are protocol errors whatever
     * the stream state.
     *
     * @param body the message after its type byte, as
     *   [com.sendspindroid.sendspin.protocol.NoiseWireCodec] delivers it
     * @return why the message is malformed, or null if it is well formed
     */
    fun malformed(body: ByteArray): String? {
        if (body.isEmpty()) return "artwork message shorter than 2 bytes"
        if (1 + body.size > MAX_MESSAGE_SIZE) {
            return "artwork message of ${1 + body.size} bytes exceeds $MAX_MESSAGE_SIZE"
        }
        val flags = body[0].toInt() and 0xFF
        return when {
            (flags and FLAGS_RESERVED) != 0 -> "artwork message with a reserved flag bit set"
            flags == (FLAG_CANCEL or FLAG_ANNOUNCE) -> "artwork message with cancel and announce both set"
            flags == FLAG_ANNOUNCE && body.size != ANNOUNCE_BODY_SIZE ->
                "artwork announce of ${1 + body.size} bytes, not 14"
            flags == FLAG_CANCEL && body.size != 1 -> "artwork cancel longer than 2 bytes"
            else -> null
        }
    }

    /**
     * Take one artwork message of an active artwork stream.
     *
     * @param type the binary message type, 8-11
     * @param body the message after its type byte
     */
    fun accept(type: Int, body: ByteArray): Result {
        malformed(body)?.let { return Result.ProtocolError(it) }
        val messageChannel = type - SendSpinProtocol.BinaryType.ARTWORK_BASE
        val inFlight = buffer

        return when (body[0].toInt()) {
            FLAG_CANCEL -> {
                // "It discards the channel's pending image", which a transfer
                // still in flight on that channel is.
                if (messageChannel == channel) buffer = null
                Result.Discard(messageChannel)
            }

            FLAG_ANNOUNCE -> {
                if (inFlight != null) {
                    return Result.ProtocolError("artwork announce while a transfer is in flight")
                }
                val announcedMicros = readBigEndian(body, 1, 8)
                val totalSize = readBigEndian(body, 9, 4)
                if (totalSize > MAX_IMAGE_BYTES) {
                    return Result.ProtocolError("artwork image of $totalSize bytes exceeds $MAX_IMAGE_BYTES")
                }
                // "An announce with total_size 0 completes immediately, with no parts."
                if (totalSize == 0L) return Result.Image(messageChannel, announcedMicros, ByteArray(0))
                channel = messageChannel
                timestampMicros = announcedMicros
                buffer = ByteArray(totalSize.toInt())
                received = 0
                // "An announce discards that channel's pending image."
                Result.Discard(messageChannel)
            }

            else -> {
                if (inFlight == null) return Result.ProtocolError("artwork part with no transfer in flight")
                if (messageChannel != channel) {
                    return Result.ProtocolError("artwork part on channel $messageChannel during a transfer on channel $channel")
                }
                val dataSize = body.size - 1
                if (dataSize > inFlight.size - received) {
                    return Result.ProtocolError("artwork part extends past total_size")
                }
                body.copyInto(inFlight, received, 1, body.size)
                received += dataSize
                if (received < inFlight.size) return Result.None
                buffer = null
                Result.Image(channel, timestampMicros, inFlight)
            }
        }
    }

    /** Big-endian; eight bytes fill the Long and so read as a signed int64. */
    private fun readBigEndian(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    companion object {
        /** Set on a cancel message. */
        const val FLAG_CANCEL = 0x01

        /** Set on an announce message. */
        const val FLAG_ANNOUNCE = 0x02

        /** "Bits 2-7 are reserved and MUST be zero." */
        const val FLAGS_RESERVED = 0xFC

        /** "An artwork message MUST NOT exceed 65519 bytes." */
        const val MAX_MESSAGE_SIZE = SendSpinProtocol.NoiseFraming.MAX_PLAINTEXT

        /** `[flags][timestamp][total_size]`: the 14-byte announce less its type byte. */
        private const val ANNOUNCE_BODY_SIZE = 13

        /**
         * Ceiling on one image.
         *
         * The spec sets no cap, and `total_size` is a uint32 the buffer is
         * allocated from, so without one a single 14-byte announce could ask a
         * phone for 4 GB.
         */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}
