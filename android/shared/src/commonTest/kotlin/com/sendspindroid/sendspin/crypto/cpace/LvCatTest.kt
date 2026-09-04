package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * draft-irtf-cfrg-cpace-21 appendix A.2: every argument is prefixed with its
 * LEB128-encoded length. The prefixes are visible in the B.1.5 vector --
 * `0c` for the 12-byte DSI, `10` for the 16-byte sid, `20` for the 32-byte K,
 * `03` for a 3-byte AD -- which is what these assertions pin.
 */
class LvCatTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `prepend_len puts the length first`() {
        assertEquals("03616263", prependLen("abc".encodeToByteArray()).hex())
    }

    @Test
    fun `empty input still carries a zero length`() {
        assertEquals("00", prependLen(ByteArray(0)).hex())
    }

    /** 127 is the largest single-byte LEB128 value. */
    @Test
    fun `127 bytes uses one length byte`() {
        val out = prependLen(ByteArray(127))
        assertEquals(128, out.size)
        assertEquals(0x7f.toByte(), out[0])
    }

    /** 128 rolls over to two bytes: 0x80 0x01. */
    @Test
    fun `128 bytes uses two length bytes`() {
        val out = prependLen(ByteArray(128))
        assertEquals(130, out.size)
        assertEquals(0x80.toByte(), out[0])
        assertEquals(0x01.toByte(), out[1])
    }

    /** 200 = 0xc8 0x01 in LEB128. */
    @Test
    fun `200 bytes encodes as c8 01`() {
        val out = prependLen(ByteArray(200))
        assertEquals(0xc8.toByte(), out[0])
        assertEquals(0x01.toByte(), out[1])
    }

    /**
     * The exact ISK preamble from draft-21 B.1.5:
     * lv_cat(DSI, sid, K) where DSI = "CPace255_ISK".
     */
    @Test
    fun `matches the B_1_5 ISK preamble vector`() {
        val dsi = "CPace255_ISK".encodeToByteArray()
        val sid = "7e4b4791d6a8ef019b936c79fb7f2c57".unhex()
        val k = "5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b".unhex()

        val expected =
            "0c43506163653235355f49534b" +
            "107e4b4791d6a8ef019b936c79fb7f2c57" +
            "205b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b"

        assertEquals(expected, lvCat(dsi, sid, k).hex())
    }

    /**
     * transcript_ir(Ya, ADa, Yb, ADb) = lv_cat(Ya, ADa) || lv_cat(Yb, ADb),
     * 74 bytes, from B.1.5.
     */
    @Test
    fun `matches the B_1_5 transcript vector`() {
        val ya = "1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32".unhex()
        val yb = "248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623".unhex()
        val ada = "ADa".encodeToByteArray()
        val adb = "ADb".encodeToByteArray()

        val expected =
            "201d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32" +
            "03414461" + "20248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea3218562" +
            "303414462"

        val actual = (lvCat(ya, ada) + lvCat(yb, adb)).hex()
        assertEquals(74, lvCat(ya, ada).size + lvCat(yb, adb).size)
        assertEquals(expected, actual)
    }
}
