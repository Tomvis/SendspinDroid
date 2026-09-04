package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md: the digest is
 *   SHA-256("sendspin-pairing-code-derive-v1" || h || nonce_A || nonce_B)
 * and the digits are that digest as a big-endian uint256 mod 10^6, zero-padded
 * to exactly six characters.
 *
 * Expected values are computed with the same construction in Python so they
 * are independent of the Kotlin implementation:
 *
 *   import hashlib
 *   d = hashlib.sha256(b"sendspin-pairing-code-derive-v1" + bytes(32)
 *                      + bytes(range(32)) + bytes(range(32,64))).digest()
 *   print(d.hex(), f"{int.from_bytes(d,'big') % 1000000:06d}")
 */
class PairingCodeTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val h = ByteArray(32)
    private val nonceA = ByteArray(32) { it.toByte() }
    private val nonceB = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun `commitment is SHA-256 over the label and nonce`() {
        // sha256(b"sendspin-pair-commit-v1" + bytes(range(32))).hex()
        assertEquals(COMMIT_VECTOR, PairingCode.commit(nonceA).hex())
    }

    @Test
    fun `digest matches the reference construction`() {
        assertEquals(DIGEST_VECTOR, PairingCode.deriveDigest(h, nonceA, nonceB).hex())
    }

    @Test
    fun `digits are the digest mod ten to the six`() {
        assertEquals(DIGITS_VECTOR, PairingCode.deriveDigits(h, nonceA, nonceB))
    }

    @Test
    fun `digits are always exactly six characters`() {
        for (i in 0 until 50) {
            val a = ByteArray(32) { i.toByte() }
            assertEquals(6, PairingCode.deriveDigits(h, a, nonceB).length)
        }
    }

    @Test
    fun `nonce is 32 bytes and not constant`() {
        val first = PairingCode.generateNonce()
        assertEquals(32, first.size)
        assertEquals(false, first.hex() == PairingCode.generateNonce().hex())
    }

    /** Presentation only: grouping never enters derivation or PRS. */
    @Test
    fun `grouping is three and three`() {
        assertEquals("123-456", PairingCode.group("123456"))
    }

    /**
     * This test exercises the zero-padding path in deriveDigits.
     * nonceB reduces to 30310 mod 10^6, so padStart must fire to produce "030310".
     * Without padStart, the result would be "30310" (5 digits), which would fail
     * to authenticate against the server. This assertion must check the full
     * string, not just length, to be load-bearing.
     */
    @Test
    fun `zero-padding produces exactly six digits when needed`() {
        val h = ByteArray(32)
        val nonceA = ByteArray(32) { it.toByte() }
        val nonceB = "8855508aade16ec573d21e6a485dfd0a7624085c1a14b5ecdd6485de0c6839a4"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("030310", PairingCode.deriveDigits(h, nonceA, nonceB))
    }

    private companion object {
        const val COMMIT_VECTOR =
            "ea08c0aee3c421ace702f31591b3d213e8c371a8a8e3b0be3fd405ed841755a3"
        const val DIGEST_VECTOR =
            "960b1b8edc2662d0bedd5f0521313a1c5ceff12168ead500698b1143afbdac0e"
        const val DIGITS_VECTOR = "697742"
    }
}
