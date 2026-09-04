package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * draft-irtf-cfrg-cpace-21 B.1.1 gives both the decoded field element and the
 * generator it maps to, so the map can be pinned on its own, without the
 * generator string wrapped around it.
 */
class Elligator2Test {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `maps the B_1_1 field element to the B_1_1 generator`() {
        val u = "03998087bdb1a2617bbe25ef5a7c18cd4f84f902328701790958755ee4aed153".unhex()
        assertEquals(
            "d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f",
            mapToCurveElligator2(u).hex()
        )
    }

    @Test
    fun `output is always 32 bytes`() {
        val u = "0100000000000000000000000000000000000000000000000000000000000000".unhex()
        assertEquals(32, mapToCurveElligator2(u).size)
    }

    @Test
    fun `rejects a wrong-sized input`() {
        var threw = false
        try {
            mapToCurveElligator2(ByteArray(31))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }
}
