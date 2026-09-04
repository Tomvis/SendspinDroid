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
        // SHA-256 of a single byte (1, 2, 3, 4, 11), with bit 255 cleared per
        // RFC 7748 decodeUCoordinate. The B.1.1 vector alone takes the x2
        // branch; these cover both (3x x1, 2x x2).
        val vectors = listOf(
            "084fed08b978af4d7d196a7446a86b58009e636b611db16211b65a9aadff2945" to
                "f0f03e43ca8c9c98f913ffa45a6c6fcac82e57bd370f2f6c552212f556b52722", // x1
            "e52d9c508c502347344d8c07ad91cbd6068afc75ff6292f062a09ca381c89e71" to
                "1008de80438d6c2a5b6cf8c45f9bd0b9db92432211f8e8241e5ea092ff17d663", // x1
            "e7cf46a078fed4fafd0b5e3aff144802b853f8ae459a4f0c14add3314b7cc326" to
                "e2d805709e4cdcf815e34a8709ed956df45a8f4905ab7e0e537ea6118259e761", // x1
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785451a" to
                "23b305f79d025b3cb633fbe7ee41fbaf832a96b935186eaf31db1e06aa89b84c", // x2
            "dbc1b4c900ffe48d575b5da5c638040125f65db0fe3e24494b76ea986457d906" to
                "074ef62b9225b194fd56c260857cc36e88a771ec7a2df68f22e97eb90b073974", // x2
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
