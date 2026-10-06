package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinned against `ci/conformance/cpace_oracle.py`, which drives the same
 * `cpace` package the reference server uses. The script pins its scalars, so
 * it is deterministic and can be re-run to re-verify the constants below.
 * They are the only interop evidence for the MCF tags, which draft-21 does
 * not supply vectors for.
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
        // handshake hash = bytes 0x00..0x1f, pairing_index = 1, round = 1.
        const val ORACLE_SID =
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
            "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000001"
        const val ORACLE_YA =
            "290f6bd1559626855a6b05bb67f1f364356ad16184f0ab898339a45c1fee6601"
        const val ORACLE_YB =
            "23cbc858e3e96f476800600beeefa665a23cae3a58f44be371589385efe71156"
        const val ORACLE_YB_SCALAR =
            "025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"
        const val ORACLE_ISK =
            "3ea260815b47e68b6cc07a22038452efc470e85425bc8485344a2987da473ca9" +
            "5cee9bbd47933370bb4c4626581c47c5f24e55a12aa1f6e0375560014210bf70"
        const val ORACLE_SERVER_KC =
            "97cd8c996a88c39bcd03f6abf26e529c2b848ad2263c791ba13e2544217bd407" +
            "9912b2ecd4e78139e92fb0ead22d137126bf30e237325cbde8b6a839fb1ea6a1"
        const val ORACLE_CLIENT_KC =
            "38fb10d9677e2e27b3113ea371469657a13442654ac7f318423437fd48a5213f" +
            "385002bedc22e4197511087058136038f01cbac0bcee8ae82e71fd89ba7a0a5b"
    }
}
