package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * draft-irtf-cfrg-cpace-21 B.1.1:
 *   PRS = "Password", DSI = "CPace255", ZPAD length 109,
 *   CI  = 0b415f696e69746961746f720b425f726573706f6e646572
 *   sid = 7e4b4791d6a8ef019b936c79fb7f2c57
 */
class CalculateGeneratorTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val prs = "Password".encodeToByteArray()
    private val ci = "0b415f696e69746961746f720b425f726573706f6e646572".unhex()
    private val sid = "7e4b4791d6a8ef019b936c79fb7f2c57".unhex()

    @Test
    fun `generator string matches the B_1_1 vector`() {
        val expected =
            "0843506163653235350850617373776f72646d" +
            "00".repeat(109) +
            "180b415f696e69746961746f720b425f726573706f6e646572" +
            "107e4b4791d6a8ef019b936c79fb7f2c57"
        val actual = CPaceX25519.generatorString(prs, ci, sid)
        assertEquals(170, actual.size)
        assertEquals(expected, actual.hex())
    }

    @Test
    fun `generator matches the B_1_1 vector`() {
        assertEquals(
            "d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f",
            CPaceX25519.calculateGenerator(prs, ci, sid).hex()
        )
    }

    /**
     * ZPAD pushes PRS past the first hash block. With a long PRS it vanishes
     * rather than going negative.
     */
    @Test
    fun `long PRS produces no zero padding`() {
        val longPrs = ByteArray(200) { 0x41 }
        // 1+8 (DSI) + 2+200 (PRS) + 1+0 (zpad) + 1+24 (CI) + 1+16 (sid)
        assertEquals(254, CPaceX25519.generatorString(longPrs, ci, sid).size)
    }
}
