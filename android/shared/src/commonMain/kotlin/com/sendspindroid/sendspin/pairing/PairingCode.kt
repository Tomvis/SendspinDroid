package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.secureRandomBytes
import com.sendspindroid.sendspin.crypto.sha256

/**
 * Dynamic pairing code derivation and the commitment that binds it.
 *
 * The code is derived from the Noise handshake hash and BOTH sides' nonces, so
 * neither peer alone chooses it. The client commits to its nonce before seeing
 * the server's, which is what stops a server steering the code to a value it
 * already knows.
 */
object PairingCode {

    const val NONCE_SIZE = 32
    const val DIGITS = 6

    private val COMMIT_LABEL = "sendspin-pair-commit-v1".encodeToByteArray()
    private val DERIVE_LABEL = "sendspin-pairing-code-derive-v1".encodeToByteArray()

    fun generateNonce(): ByteArray = secureRandomBytes(NONCE_SIZE)

    /** `commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B)`. */
    fun commit(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        return sha256(COMMIT_LABEL, nonce)
    }

    /** `SHA-256(label || h || nonce_A || nonce_B)`. */
    fun deriveDigest(handshakeHash: ByteArray, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        require(handshakeHash.size == 32) { "handshake hash must be 32 bytes" }
        require(nonceA.size == NONCE_SIZE) { "nonce_A must be $NONCE_SIZE bytes" }
        require(nonceB.size == NONCE_SIZE) { "nonce_B must be $NONCE_SIZE bytes" }
        return sha256(DERIVE_LABEL, handshakeHash, nonceA, nonceB)
    }

    /**
     * The six-digit code: the digest as an unsigned big-endian 256-bit integer
     * reduced mod 10^6, zero-padded on the left.
     *
     * Done with longs over the digest bytes rather than a BigInteger so it
     * stays in commonMain: reducing mod 10^6 byte by byte is exact because
     * 256 * 10^6 fits in a Long.
     */
    fun deriveDigits(handshakeHash: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String {
        var remainder = 0L
        for (b in deriveDigest(handshakeHash, nonceA, nonceB)) {
            remainder = (remainder * 256 + (b.toInt() and 0xff)) % 1_000_000L
        }
        return remainder.toString().padStart(DIGITS, '0')
    }

    /** Presentation grouping only; separators never enter derivation or PRS. */
    fun group(code: String): String =
        if (code.length == DIGITS) code.substring(0, 3) + "-" + code.substring(3) else code
}
