package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinned against `ci/conformance/cpace_oracle.py`, which drives the same
 * `cpace` package the reference server uses.
 *
 * Replace the constants below with one captured run of that script. They are
 * the only interop evidence for the MCF tags, which draft-21 does not supply
 * vectors for.
 */
class CPaceResponderTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val prs = "123456".encodeToByteArray()
    private val sid = ORACLE_SID.unhex()
    private val ya = ORACLE_YA.unhex()
    private val scalar = ORACLE_YB_SCALAR.unhex()

    @Test
    fun `public share matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertEquals(ORACLE_YB, r.publicShare.hex())
    }

    @Test
    fun `ISK matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(ORACLE_ISK, r.isk.hex())
    }

    @Test
    fun `client tag matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(ORACLE_CLIENT_KC, r.tag().hex())
    }

    @Test
    fun `verifies the oracle server tag`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertTrue(r.verify(ORACLE_SERVER_KC.unhex()))
    }

    @Test
    fun `rejects a corrupted server tag`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        val bad = ORACLE_SERVER_KC.unhex().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(false, r.verify(bad))
    }

    /** A wrong pairing code must not verify -- this is the whole point. */
    @Test
    fun `a different PRS does not verify`() {
        val r = CPaceResponder("654321".encodeToByteArray(), sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(false, r.verify(ORACLE_SERVER_KC.unhex()))
    }

    /** A low-order share aborts rather than deriving. */
    @Test
    fun `a low-order peer share fails derive`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertEquals(false, r.derive(ByteArray(32)))
    }

    private companion object {
        // Captured from ci/conformance/cpace_oracle.py. PRS = "123456",
        // handshake hash = bytes 0x00..0x1f, pairing_index = 1.
        const val ORACLE_SID =
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
            "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f00000001"
        const val ORACLE_YA =
            "9fe5d4369ccd0299dfeae5e52efb1ab15a28b286dc8dd89b7dd11f4c9f32eb28"
        const val ORACLE_YB =
            "ffc9edf7457e8acf737d5bb2099e50592ec1313dee9658df6a628954cee55135"
        const val ORACLE_YB_SCALAR =
            "025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"
        const val ORACLE_ISK =
            "d8235f0ee9ac764401d2f01474e96c565a5ef225c14c1c01dd35a9073f84bb8c" +
            "a14a8cfdc169e7bddf79e06434270276ee2111f60ee91b9186ef05e1573de1d8"
        const val ORACLE_SERVER_KC =
            "ed2bf3ee3d10ae525e93fafb1189147fd3fc65e53d6399fccc8c95f80fcad235" +
            "d5319d060d01ffe0f0a5b9cdbd85a4f71254af3216e79775dda0095681e8bbfb"
        const val ORACLE_CLIENT_KC =
            "546f758bdebc86771b46a55d042247a75fdd6eb8ea854357c11e135a8bc3429e" +
            "f074c53382b946b8d275da732964a94bc7d93df70745c045d640fb5d2dcaf185"
    }
}
