package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.NoiseCrypto

/**
 * Identity "encryption" for handler tests.
 *
 * Every application message travels through the encrypted channel, so a handler
 * under test needs one installed before it can send or receive anything. With
 * this as its crypto the frames on the wire are the AEAD plaintexts themselves
 * - `[type][body]` - and a test can read and write them directly. The real AEAD
 * is covered by the handshake driver's golden vectors.
 */
object PlaintextCrypto : NoiseCrypto {
    override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
    override fun decrypt(frame: ByteArray): ByteArray = frame
}

/** The JSON text of a [PlaintextCrypto] frame, or null if it is not a type-0 message. */
fun ByteArray.jsonFrameText(): String? =
    if (isNotEmpty() && this[0].toInt() == SendSpinProtocol.BinaryType.JSON) {
        decodeToString(1, size)
    } else {
        null
    }

/** [this] JSON text as the [PlaintextCrypto] frame a server would send it in. */
fun String.asJsonFrame(): ByteArray =
    byteArrayOf(SendSpinProtocol.BinaryType.JSON.toByte()) + encodeToByteArray()
