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
        // handshake hash = bytes 0x00..0x1f, pairing_index = 1.
        const val ORACLE_SID =
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
            "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f00000001"
        const val ORACLE_YA =
            "9e9481280468a6ef3bb3c6b962d564f96b523ca957c41420319b0b2408de5861"
        const val ORACLE_YB =
            "ffc9edf7457e8acf737d5bb2099e50592ec1313dee9658df6a628954cee55135"
        const val ORACLE_YB_SCALAR =
            "025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"
        const val ORACLE_ISK =
            "727136d942eec1e10f9cfc1cdb2426391f217e98348143b978045c747b4f25ee" +
            "fc9f45864558c4329c2dc1a30424d84ec3965cb1da5863ea8e1239237f559f06"
        const val ORACLE_SERVER_KC =
            "110fcfde4aa2b30072787f30d78eeefc4dbfe93b4310d594b79e91ed8f9c0a9e" +
            "426040bf012f84fa68705f00e5b745c91c4d27a70cbb9e12b5e41c5db5fe5eb1"
        const val ORACLE_CLIENT_KC =
            "b3192b30dcf4f3d0fdbfb125dc17fb071984894762e0d0d0ad6b13272802194f" +
            "e4aee13a7bd1bc6bbca50b6592a1fafc4e44c0258dfda4815b524ddee0acea0c"
    }
}
