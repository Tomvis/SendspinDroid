package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md "Wrapping": K_wrap = SHA-256(label || sid || ISK), then the
 * connection's negotiated AEAD with a 12-byte zero nonce and empty AD. A
 * 32-byte value seals to 48 bytes (ciphertext plus 16-byte tag).
 */
class PairingWrapTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val sid = ByteArray(16) { it.toByte() }
    private val isk = ByteArray(64) { (it + 100).toByte() }

    @Test
    fun `wrap key is 32 bytes`() {
        assertEquals(32, PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).size)
    }

    /** The two labels must produce different keys, or the values are swappable. */
    @Test
    fun `the two labels give different keys`() {
        assertEquals(
            false,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).hex() ==
                PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk).hex()
        )
    }

    @Test
    fun `sealing a 32 byte value yields 48 bytes`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk)
        val sealed = PairingWrap.seal(NoiseCipherSuite.CHACHA_POLY, key, ByteArray(32))
        assertEquals(48, sealed.size)
    }

    @Test
    fun `sealing is deterministic for a fixed key and nonce`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk)
        val a = PairingWrap.seal(NoiseCipherSuite.CHACHA_POLY, key, ByteArray(32) { 7 })
        val b = PairingWrap.seal(NoiseCipherSuite.CHACHA_POLY, key, ByteArray(32) { 7 })
        assertEquals(a.hex(), b.hex())
    }

    @Test
    fun `a different sid gives a different key`() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertEquals(
            false,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).hex() ==
                PairingWrap.wrapKey(PairingWrap.PSK_LABEL, other, isk).hex()
        )
    }
}
