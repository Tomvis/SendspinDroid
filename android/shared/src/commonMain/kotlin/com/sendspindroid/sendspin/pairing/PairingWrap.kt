package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.aeadSeal
import com.sendspindroid.sendspin.crypto.sha256

/**
 * Sealing for the two values that cross the wire only under the CPace output:
 * the new long-term PSK, and the commitment opening `nonce_B`.
 */
object PairingWrap {

    val PSK_LABEL = "sendspin-pair-psk-wrap-v1".encodeToByteArray()
    val NONCE_LABEL = "sendspin-pair-nonce-wrap-v1".encodeToByteArray()

    /**
     * A 12-byte all-zero nonce.
     *
     * This is safe ONLY because each wrap key is derived per field -- the two
     * labels give different keys -- and each key seals exactly one value once.
     * A zero nonce reused under one key would be catastrophic, so do not
     * generalise this constant to any other AEAD use.
     */
    private val ZERO_NONCE = ByteArray(12)

    /** `K_wrap = SHA-256(label || sid || ISK)`. */
    fun wrapKey(label: ByteArray, sid: ByteArray, isk: ByteArray): ByteArray =
        sha256(label, sid, isk)

    /** Seal a 32-byte value, producing 48 bytes of ciphertext plus tag. */
    fun seal(suite: NoiseCipherSuite, key: ByteArray, plaintext: ByteArray): ByteArray =
        aeadSeal(suite.aead, key, ZERO_NONCE, ByteArray(0), plaintext)
}
