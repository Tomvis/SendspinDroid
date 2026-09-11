package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/** draft-irtf-cfrg-cpace-21 B.1.5, initiator/responder (ordered) mode. */
class IskTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val sid = "7e4b4791d6a8ef019b936c79fb7f2c57".unhex()
    private val k = "5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b".unhex()
    private val ya = "1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32".unhex()
    private val yb = "248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623".unhex()
    private val ada = "ADa".encodeToByteArray()
    private val adb = "ADb".encodeToByteArray()

    @Test
    fun `transcript_ir matches the B_1_5 vector`() {
        val expected =
            "201d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32" +
            "0341446120248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea" +
            "3218562303414462"
        val actual = CPaceX25519.transcriptIr(ya, ada, yb, adb)
        assertEquals(74, actual.size)
        assertEquals(expected, actual.hex())
    }

    @Test
    fun `ISK matches the B_1_5 vector`() {
        assertEquals(
            "6e19b875f7a561d6b3ca3dbb9ef42ac55de3e717881018204b8922b4d5e53bb2" +
            "aa82c300bea7b65d2b671da71922ddf6472301b79bc270adfa8bf413285f2263",
            CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).hex()
        )
    }

    @Test
    fun `ISK is 64 bytes`() {
        assertEquals(64, CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).size)
    }

    /** Ordering is part of the binding: swapping the roles must change the ISK. */
    @Test
    fun `swapping the two sides changes the ISK`() {
        val normal = CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).hex()
        val swapped = CPaceX25519.deriveIsk(sid, k, yb, adb, ya, ada).hex()
        assertEquals(false, normal == swapped)
    }
}
