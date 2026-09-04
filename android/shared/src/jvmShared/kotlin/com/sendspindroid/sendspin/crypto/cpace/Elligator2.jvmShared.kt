package com.sendspindroid.sendspin.crypto.cpace

import org.bouncycastle.math.ec.rfc7748.X25519Field

/** Curve25519 A = 486662; B is 1 and folds into the arithmetic. Z = 2. */
private const val CURVE_A = 486662

/**
 * Timing note. `sqrtRatioVar` is variable-time and its input derives from the
 * pairing code. That is acceptable HERE only because the dynamic pairing code
 * is not a long-term secret: it is displayed openly on the device screen for
 * the duration of the attempt, is single-use per session, and attempts are
 * rate-limited by the failure counter's escalation to a gesture gate. Do not
 * carry this reasoning over to the Pairing PSK path, whose secret is
 * long-lived.
 */
actual fun mapToCurveElligator2(u: ByteArray): ByteArray {
    require(u.size == 32) { "field element must be 32 bytes, got ${u.size}" }

    val uf = X25519Field.create()
    X25519Field.decode(u, 0, uf)

    val a = X25519Field.create()
    a[0] = CURVE_A

    val one = X25519Field.create()
    X25519Field.one(one)

    // tv1 = Z * u^2, Z = 2
    val tv1 = X25519Field.create()
    X25519Field.sqr(uf, tv1)
    X25519Field.add(tv1, tv1, tv1)

    // e1 = (tv1 == -1) i.e. tv1 + 1 == 0; then tv1 = 0
    val tv1PlusOne = X25519Field.create()
    X25519Field.add(tv1, one, tv1PlusOne)
    X25519Field.normalize(tv1PlusOne)
    val e1 = X25519Field.isZero(tv1PlusOne)
    val zero = X25519Field.create()
    X25519Field.cmov(e1, zero, 0, tv1, 0)

    // x1 = -A / (1 + tv1)
    val denom = X25519Field.create()
    X25519Field.add(tv1, one, denom)
    val invDenom = X25519Field.create()
    X25519Field.inv(denom, invDenom)
    val x1 = X25519Field.create()
    X25519Field.mul(a, invDenom, x1)
    X25519Field.negate(x1, x1)

    // gx1 = x1^3 + A*x1^2 + x1
    val gx1 = X25519Field.create()
    X25519Field.add(x1, a, gx1)
    X25519Field.mul(gx1, x1, gx1)
    X25519Field.add(gx1, one, gx1)
    X25519Field.mul(gx1, x1, gx1)

    // x2 = -x1 - A
    val x2 = X25519Field.create()
    X25519Field.negate(x1, x2)
    X25519Field.sub(x2, a, x2)

    // e2 = is_square(gx1)
    val sqrtOut = X25519Field.create()
    val isSquare = X25519Field.sqrtRatioVar(gx1, one, sqrtOut)

    // x = e2 ? x1 : x2
    val x = X25519Field.create()
    X25519Field.copy(x2, 0, x, 0)
    X25519Field.cmov(if (isSquare) -1 else 0, x1, 0, x, 0)

    X25519Field.normalize(x)
    val out = ByteArray(32)
    X25519Field.encode(x, out, 0)
    return out
}
