package com.sendspindroid.sendspin.crypto.cpace

import com.sendspindroid.sendspin.crypto.hmacSha512
import com.sendspindroid.sendspin.crypto.sha512
import com.sendspindroid.sendspin.crypto.x25519ScalarMult

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

    /**
     * `G_X25519.scalar_mult_vfy`, draft section 10.8.
     *
     * X25519 already maps every low-order point -- on the curve and on the
     * twist -- to the all-zero string, so verification reduces to a zero check
     * on the result. Returning null rather than those zero bytes forces the
     * caller to handle the abort: a peer steering both sides to a known shared
     * secret is the attack this prevents, so the failure must not be
     * representable as a valid K.
     */
    fun scalarMultVfy(scalar: ByteArray, point: ByteArray): ByteArray? {
        require(scalar.size == 32) { "scalar must be 32 bytes, got ${scalar.size}" }
        if (point.size != 32) return null
        val result = x25519ScalarMult(scalar, point)
        var acc = 0
        for (b in result) acc = acc or b.toInt()
        return if (acc == 0) null else result
    }

    /**
     * `transcript_ir(Ya, ADa, Yb, ADb)` = lv_cat(Ya, ADa) || lv_cat(Yb, ADb).
     *
     * Order is the initiator's values first. It is part of the binding, not a
     * formatting choice: swapping them yields a different ISK, which is what
     * stops a reflection attack.
     */
    fun transcriptIr(ya: ByteArray, ada: ByteArray, yb: ByteArray, adb: ByteArray): ByteArray =
        lvCat(ya, ada) + lvCat(yb, adb)

    /** `ISK = H(lv_cat(DSI_ISK, sid, K) || transcript_ir(...))`, section 7.2.3. */
    fun deriveIsk(
        sid: ByteArray,
        k: ByteArray,
        ya: ByteArray,
        ada: ByteArray,
        yb: ByteArray,
        adb: ByteArray,
    ): ByteArray = sha512(
        lvCat(DSI_ISK.encodeToByteArray(), sid, k),
        transcriptIr(ya, ada, yb, adb)
    )

    /** `mac_key = H("CPaceMac" || sid || ISK)`, section 10.4.5. */
    fun macKey(sid: ByteArray, isk: ByteArray): ByteArray =
        sha512("CPaceMac".encodeToByteArray(), sid, isk)

    /** `T = MAC(mac_key, lv_cat(Y, AD))`. Sendspin pins the MAC to HMAC-SHA-512. */
    fun mcfTag(macKey: ByteArray, y: ByteArray, ad: ByteArray): ByteArray =
        hmacSha512(macKey, lvCat(y, ad))
}
