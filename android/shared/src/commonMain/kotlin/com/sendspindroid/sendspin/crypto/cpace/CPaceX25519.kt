package com.sendspindroid.sendspin.crypto.cpace

import com.sendspindroid.sendspin.crypto.sha512

/**
 * CPACE-X25519-SHA512 primitives from draft-irtf-cfrg-cpace-21.
 *
 * Responder side only: SendSpin makes the server CPace initiator (role A) and
 * the client responder (role B), so nothing here computes an initiator value.
 */
object CPaceX25519 {

    const val DSI = "CPace255"
    const val DSI_ISK = "CPace255_ISK"

    /** SHA-512 input block size, which sizes the zero padding. */
    const val S_IN_BYTES = 128

    /**
     * `generator_string(DSI, PRS, CI, sid, s_in_bytes)`, appendix A.2.
     *
     * The padding length uses the LENGTH-PREFIXED sizes of DSI and PRS, not
     * the raw sizes. That is the easy thing to get wrong, and it changes every
     * downstream byte.
     */
    fun generatorString(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val dsi = DSI.encodeToByteArray()
        val zpadLen = maxOf(
            0,
            S_IN_BYTES - prependLen(prs).size - prependLen(dsi).size - 1
        )
        return lvCat(dsi, prs, ByteArray(zpadLen), ci, sid)
    }

    /** Hash the generator string to a field element and map it to the curve. */
    fun calculateGenerator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val u = sha512(generatorString(prs, ci, sid)).copyOf(32)
        // RFC 7748 decodeUCoordinate for 255 bits: clear the top bit.
        u[31] = (u[31].toInt() and 0x7f).toByte()
        return mapToCurveElligator2(u)
    }
}
