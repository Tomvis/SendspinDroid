package com.sendspindroid.sendspin.crypto.cpace

/**
 * RFC 9380 `map_to_curve_elligator2` for curve25519, x-coordinate only.
 *
 * CPace discards the v coordinate (draft-irtf-cfrg-cpace-21 section 8.2.9),
 * so this never computes y. That removes sqrt(y2), sgn0 and the final
 * conditional negation; all that remains of them is the is_square test that
 * picks between the two candidate x values.
 *
 * @param u 32-byte little-endian field element, already reduced per RFC 7748
 *   decodeUCoordinate (bit 255 cleared).
 * @return the 32-byte little-endian x-coordinate of the mapped point.
 */
expect fun mapToCurveElligator2(u: ByteArray): ByteArray
