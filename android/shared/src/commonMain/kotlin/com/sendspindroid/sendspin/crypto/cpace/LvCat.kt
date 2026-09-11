package com.sendspindroid.sendspin.crypto.cpace

/**
 * Length-prefixed concatenation from draft-irtf-cfrg-cpace-21 appendix A.2.
 *
 * Lengths are LEB128, NOT fixed-width. Getting this wrong produces an
 * implementation that agrees with itself in every test and disagrees with
 * every real peer, because a prefix error changes every downstream byte of
 * the generator, the ISK and both MCF tags.
 */

/** LEB128-encode [value] as an unsigned base-128 varint. */
private fun leb128(value: Int): ByteArray {
    require(value >= 0) { "length cannot be negative: $value" }
    var remaining = value
    val out = ArrayList<Byte>(2)
    do {
        var byte = remaining and 0x7f
        remaining = remaining ushr 7
        if (remaining != 0) byte = byte or 0x80
        out.add(byte.toByte())
    } while (remaining != 0)
    return out.toByteArray()
}

/** `prepend_len(data)`: the LEB128 length of [data], then [data]. */
fun prependLen(data: ByteArray): ByteArray = leb128(data.size) + data

/** `lv_cat(a, b, ...)`: every part length-prefixed, concatenated in order. */
fun lvCat(vararg parts: ByteArray): ByteArray {
    var out = ByteArray(0)
    for (part in parts) out += prependLen(part)
    return out
}
