package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import com.sendspindroid.shared.log.Log

object BinaryMessageParser {
    private const val TAG = "BinaryMessageParser"

    /** Audio header after the type byte: int64 timestamp + uint32 send_ahead. */
    private const val AUDIO_BODY_HEADER_SIZE = SendSpinProtocol.AUDIO_HEADER_SIZE_BYTES - 1

    sealed class BinaryMessage {
        /**
         * @param sendAheadMicros "microseconds from the server's transmission of
         *   this message to `timestamp`". Carries no scheduling meaning; `0` and
         *   `4294967295` both mean no lead was measured.
         */
        data class Audio(
            val timestampMicros: Long,
            val sendAheadMicros: Long,
            val payload: ByteArray
        ) : BinaryMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Audio) return false
                if (timestampMicros != other.timestampMicros) return false
                if (sendAheadMicros != other.sendAheadMicros) return false
                if (!payload.contentEquals(other.payload)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = timestampMicros.hashCode()
                result = 31 * result + sendAheadMicros.hashCode()
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }
    }

    /**
     * Parse a binary message whose type byte has already been stripped by
     * [com.sendspindroid.sendspin.protocol.NoiseWireCodec].
     *
     * Only audio chunks are implemented: [body] is then
     * `[8-byte big-endian timestamp][4-byte big-endian send_ahead][frame]`.
     *
     * @return null for a malformed audio chunk and for every other ID, which
     *   the spec says to ignore ("binary messages whose ID they do not
     *   implement"). Artwork (8-11) does not come here: its announce/part/cancel
     *   transfers are stateful and belong to [ArtworkReceiver].
     */
    fun parse(type: Int, body: ByteArray): BinaryMessage? {
        if (type != SendSpinProtocol.BinaryType.AUDIO) {
            Log.v(TAG, "Ignoring binary message type $type")
            return null
        }
        if (body.size < AUDIO_BODY_HEADER_SIZE) {
            Log.w(TAG, "Audio chunk too short: ${body.size} bytes")
            return null
        }
        return BinaryMessage.Audio(
            timestampMicros = readBigEndian(body, 0, 8),
            sendAheadMicros = readBigEndian(body, 8, 4),
            payload = body.copyOfRange(AUDIO_BODY_HEADER_SIZE, body.size),
        )
    }

    /** Big-endian; eight bytes fill the Long and so read as a signed int64. */
    private fun readBigEndian(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
