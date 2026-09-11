package com.sendspindroid.sendspin.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha512PrimitivesTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** NIST: SHA-512 of the empty string. */
    @Test
    fun `sha512 of empty input`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
            "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            sha512().hex()
        )
    }

    /** NIST: SHA-512("abc"). */
    @Test
    fun `sha512 of abc`() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            sha512("abc".encodeToByteArray()).hex()
        )
    }

    /** Varargs must concatenate, not hash separately. */
    @Test
    fun `sha512 concatenates its parts`() {
        assertEquals(
            sha512("abc".encodeToByteArray()).hex(),
            sha512("a".encodeToByteArray(), "bc".encodeToByteArray()).hex()
        )
    }

    /** RFC 4231 test case 1, truncated to the SHA-512 output. */
    @Test
    fun `hmacSha512 matches RFC 4231 case 1`() {
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
            "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            hmacSha512(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex()
        )
    }

    /**
     * RFC 7748 section 6.1: X25519 against the standard base point must agree
     * with the existing x25519PublicKey, proving scalarMult takes an arbitrary
     * base point rather than silently using the fixed one.
     */
    @Test
    fun `x25519ScalarMult with the standard base point matches x25519PublicKey`() {
        val scalar = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val basePoint = ByteArray(32).also { it[0] = 9 }
        assertEquals(
            x25519PublicKey(scalar).hex(),
            x25519ScalarMult(scalar, basePoint).hex()
        )
    }

    /** RFC 7748 section 6.1 Alice/Bob shared secret, via an arbitrary point. */
    @Test
    fun `x25519ScalarMult computes the RFC 7748 shared secret`() {
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bobPub = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".unhex()
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            x25519ScalarMult(alicePriv, bobPub).hex()
        )
    }
}
