package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * draft-irtf-cfrg-cpace-21 B.1.10. The draft states that
 * "u0,u1,u2,u3,u4,u5 and u7 MUST trigger the abort case when included in
 * message from A or B", while u6, u8, u9, ua and ub are legitimate points
 * that must still be processed.
 */
class ScalarMultVfyTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val s = "af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff".unhex()

    @Test
    fun `low order points abort`() {
        val mustAbort = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
        for (hex in mustAbort) {
            assertNull(CPaceX25519.scalarMultVfy(s, hex.unhex()), "u=$hex must abort")
        }
    }

    @Test
    fun `valid points produce their vectors`() {
        val cases = listOf(
            "daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
                "d8e2c776bbacd510d09fd9278b7edcd25fc5ae9adfba3b6e040e8d3b71b21806",
            "dbffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
                "c85c655ebe8be44ba9c0ffde69f2fe10194458d137f09bbff725ce58803cdb38",
            "d9ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
                "db64dafa9b8fdd136914e61461935fe92aa372cb056314e1231bc4ec12417456",
            "cdeb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880" to
                "e062dcd5376d58297be2618c7498f55baa07d7e03184e8aada20bca28888bf7a",
            "4c9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7" to
                "993c6ad11c4c29da9a56f7691fd0ff8d732e49de6250b6c2e80003ff4629a175",
        )
        for ((u, expected) in cases) {
            val q = CPaceX25519.scalarMultVfy(s, u.unhex())
            assertNotNull(q, "u=$u should not abort")
            assertEquals(expected, q.hex(), "u=$u")
        }
    }

    @Test
    fun `a wrong-sized point aborts rather than throwing`() {
        assertNull(CPaceX25519.scalarMultVfy(s, ByteArray(31)))
    }
}
