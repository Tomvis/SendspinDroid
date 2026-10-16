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

    /**
     * pairing.md, "Rounds": each round is "a separate CPace run - fresh
     * scalars, its own sid". The same code and scalar under round 2's sid give
     * a different share and keys, all as the oracle has them.
     */
    @Test
    fun `round 2 matches the oracle`() {
        val r = CPaceResponder(prs, ROUND_2_SID.unhex(), scalar)
        assertEquals(ROUND_2_YB, r.publicShare.hex())
        assertTrue(r.derive(ROUND_2_YA.unhex()))
        assertEquals(ROUND_2_ISK, r.isk.hex())
        assertEquals(ROUND_2_CLIENT_KC, r.tag().hex())
        assertTrue(r.verify(ROUND_2_SERVER_KC.unhex()))
    }

    /**
     * pairing.md, "PAKE": `PRS` is "in the qr_code emission format the raw
     * 24-byte code". A PRS of another length pads the generator string
     * differently, which the six-digit vectors cannot catch.
     */
    @Test
    fun `a 24-byte qr_code PRS matches the oracle`() {
        val r = CPaceResponder(QR_CODE_PRS.unhex(), sid, scalar)
        assertEquals(QR_CODE_YB, r.publicShare.hex())
        assertTrue(r.derive(QR_CODE_YA.unhex()))
        assertEquals(QR_CODE_ISK, r.isk.hex())
        assertEquals(QR_CODE_CLIENT_KC, r.tag().hex())
        assertTrue(r.verify(QR_CODE_SERVER_KC.unhex()))
    }

    /** A low-order share aborts rather than deriving. */
    @Test
    fun `a low-order peer share fails derive`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertEquals(false, r.derive(ByteArray(32)))
    }

    private companion object {
        // Captured from ci/conformance/cpace_oracle.py, its digits_round_1.
        // PRS = "123456", handshake hash = bytes 0x00..0x1f,
        // pairing_index = 1, round = 1.
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

        // The oracle's digits_round_2: as above, but round = 2.
        const val ROUND_2_SID =
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
            "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000002"
        const val ROUND_2_YA =
            "35d619e1d0e6bc3f19df608df23cbb0b55c0603cc94afc8310fcee691765a505"
        const val ROUND_2_YB =
            "5a0cff21905b9ae5a1cabc9790a8e43e7ad24563d715c106432c67da78fa696c"
        const val ROUND_2_ISK =
            "3f32c3225e9dd5db3f9b922d5da502f5d0d3982f5b1d13040ed9d7420a67e840" +
            "86d063325bfbf0ec11971305dad884d73197606e258399abdf27bde117920e67"
        const val ROUND_2_SERVER_KC =
            "6200a2f94432b5934c1af4a3a196fc0b179dcfcc872490eeade3e2c0db8c7c97" +
            "7ddaf801275dd2972b8ec612dd020a89503f918acd188f551bd9521de7b0e063"
        const val ROUND_2_CLIENT_KC =
            "964d56fe6a0d1642a9a824a46e4078480af975236ee51b97005b9634f25b5447" +
            "b09198ca1d01dd51ddfbdd5b6f92316f36d1a09d70e8236ecdee63d258851f51"

        // The oracle's qr_code_round_1: PRS = bytes 0xe0..0xf7, the code of
        // pairing.md's QR-code emission reference vector, under ORACLE_SID.
        const val QR_CODE_PRS = "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7"
        const val QR_CODE_YA =
            "2d798a7e991299e073390aa8cc301a9dd9c053a33630f57fb9fd0a63d75e6f1c"
        const val QR_CODE_YB =
            "7237d3cc63109cc8cbcde36989ab9bbefc6e9032a3c91c995eed51f6334f665f"
        const val QR_CODE_ISK =
            "dc6044c23f4190145ba5a7db8934e9e22b8f600c89743b2431902cee28201055" +
            "d58a45e357be51351600d98fd51477de8bce5c799cb3d6ab3d1cec40fde4db75"
        const val QR_CODE_SERVER_KC =
            "fd74be689e5970ae0fc3b175290c6f3e9a5f545ef6c50387c3dbcc79c8910ebd" +
            "c206d74dc29dd97abef091feaee7b67048e24ac69e4ef1b565cbce450a321856"
        const val QR_CODE_CLIENT_KC =
            "8353acad7d0245e250cea9949d9052d2932cd203849123aad374565cb8416b27" +
            "76e70ee7ea491a96434c6ac8f3370a2f042c159004d8896c5d7b602ae4343042"
    }
}
