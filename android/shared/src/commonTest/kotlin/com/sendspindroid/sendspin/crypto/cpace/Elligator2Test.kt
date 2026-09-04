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
    fun `maps both the x1 and x2 branches correctly`() {
        // Generated from a reference implementation that reproduces draft-21 B.1.1.
        // The B.1.1 vector alone takes the x2 branch; these cover both.
        val vectors = listOf(
            "0100000000000000000000000000000000000000000000000000000000000000" to
                "9cdb525555555555555555555555555555555555555555555555555555555555", // x1
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a" to
                "6eef32b95a770e95407a618fc861544b7e2b39b872056a166e6f98d7d529a754", // x1
            "dbc1b4c900ffe48d575b5da5c638040125f65db0fe3e24494b76ea986457d986" to
                "611de1bfe17ca7e1388a347fb7ceabccf2471eb87ad015356882ac46da4dcb6f", // x1
            "084fed08b978af4d7d196a7446a86b58009e636b611db16211b65a9aadff29c5" to
                "4f863f7837154c2a4c31543e2053de4b47691f70848cbcdc0171422833724531", // x2
            "e52d9c508c502347344d8c07ad91cbd6068afc75ff6292f062a09ca381c89e71" to
                "1008de80438d6c2a5b6cf8c45f9bd0b9db92432211f8e8241e5ea092ff17d663", // x1
        )
        for ((u, expected) in vectors) {
            assertEquals(expected, mapToCurveElligator2(u.unhex()).hex(), "u = $u")
        }
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
