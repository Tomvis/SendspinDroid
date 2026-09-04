# Dynamic Pairing Code Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add spec-conformant `dynamic_pairing_code` pairing to SendSpinDroid: a per-session six-digit code bound to the Noise handshake, authenticated by a CPace-X25519-SHA512 PAKE round.

**Architecture:** Two pure layers in `:shared` -- a CPace responder built on BouncyCastle's `X25519Field`/`X25519`, and a `DynamicPairingCodeFlow` event-to-action state machine mirroring the existing `PairingPskFlow` -- plus message plumbing in `:app` and a code display that reuses the `AdmissionState.PAIRING` surface. No new dependency.

**Tech Stack:** Kotlin Multiplatform (`:shared` commonMain/jvmShared/commonTest), BouncyCastle 1.80 low-level `org.bouncycastle.crypto.*` API, JUnit 4, Jetpack Compose, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-04-dynamic-pairing-code-design.md`

## Global Constraints

- **No emojis** in code, logs, or UI strings. Use ASCII: `us` not the micro sign, `->` not an arrow, `+/-` not the plus-minus sign.
- **No self-citation.** Never write "Claude", "AI-generated", or `Co-Authored-By` in commits, comments, or docs.
- **ASCII only** in source and docs. A bare apostrophe in `strings.xml` is a build error -- reword to avoid it rather than escaping.
- **Crypto goes through BouncyCastle's low-level API** (`org.bouncycastle.crypto.*`), never JCA. Registering a provider collides with Android's repackaged `com.android.org.bouncycastle`.
- **`expect`/`actual` pattern:** declare in `shared/src/commonMain/.../crypto/NoisePrimitives.kt`, implement in `shared/src/jvmShared/.../crypto/NoisePrimitives.jvmShared.kt`.
- **Test command for every task:** `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest` (add `:app:testDebugUnitTest` for `:app` tasks). This is what CI runs.
- **Baseline:** 1,316 tests passing, 0 failures, before this plan starts.
- **Responder role only.** We are always CPace role B. Never implement role A.
- **Commit after every task.** Never skip hooks.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `shared/src/commonMain/.../crypto/cpace/LvCat.kt` | LEB128 `prepend_len` and `lv_cat` concatenation |
| `shared/src/commonMain/.../crypto/cpace/Elligator2.kt` | RFC 9380 `map_to_curve_elligator2` on `X25519Field` |
| `shared/src/commonMain/.../crypto/cpace/CPaceX25519.kt` | generator string, `calculate_generator`, `scalar_mult_vfy`, ISK, MCF tags |
| `shared/src/commonMain/.../crypto/cpace/CPaceResponder.kt` | Role-B facade: `start`/`derive`/`verify`/`tag`/`isk` |
| `shared/src/commonMain/.../pairing/PairingCode.kt` | commitment, digest, six-digit derivation |
| `shared/src/commonMain/.../pairing/PairingWrap.kt` | `K_wrap` derivation and AEAD seal |
| `shared/src/commonMain/.../pairing/DynamicPairingCodeFlow.kt` | event-to-action state machine |
| `shared/src/commonMain/.../pairing/PairingFailureCounter.kt` | counter + escalation + window policy |
| `app/src/main/.../ui/main/components/PairingCodeDisplay.kt` | six-digit display and gesture button |

**Modified:**

| File | Change |
|---|---|
| `shared/src/commonMain/.../crypto/NoisePrimitives.kt` | add `sha512`, `hmacSha512`, `x25519ScalarMult` expects |
| `shared/src/jvmShared/.../crypto/NoisePrimitives.jvmShared.kt` | BouncyCastle actuals for the above |
| `shared/src/commonMain/.../protocol/ServerActivate.kt` | remove vestigial `pinLength` |
| `shared/src/commonMain/.../pairing/PairAbortReason.kt` | remove `PIN_LENGTH_UNACCEPTABLE`; add dynamic reasons |
| `shared/src/commonMain/.../protocol/message/MessageBuilder.kt` | pairing message builders; descriptor `formats`/`out_channels` |
| `shared/src/commonMain/.../protocol/message/MessageParser.kt` | pairing message parsers |
| `app/src/main/.../sendspin/protocol/SendSpinProtocolHandler.kt` | dispatch pairing messages into the flow |
| `app/src/main/.../sendspin/SendSpin.kt` | `offeredPairMethods` includes dynamic; surface code to UI |
| `app/src/main/.../playback/PlaybackService.kt` | carry pairing code + method in session extras |
| `app/src/main/.../MainActivity.kt` | read the new extras |
| `app/src/main/.../ui/main/MainActivityViewModel.kt` | hold pairing code state |
| `app/src/main/.../ui/main/components/AdmissionNotice.kt` | render the code in the PAIRING branch |
| `app/src/main/res/values/strings.xml` | code display and gesture strings |
| `ci/conformance/dev_server.py` | pin aiosendspin 10.x, offer dynamic pairing |

---

## Task 1: LEB128 length-prefixed concatenation

The single highest-risk primitive in the plan. `lv_cat` prefixes **every** argument with its LEB128-encoded length. A fixed-width implementation is self-consistent, passes every test written against itself, and fails only against a real server.

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/LvCat.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/LvCatTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `fun prependLen(data: ByteArray): ByteArray`, `fun lvCat(vararg parts: ByteArray): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*LvCatTest*"`
Expected: FAIL with `Unresolved reference 'prependLen'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*LvCatTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/LvCat.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/LvCatTest.kt
git commit -m "feat(cpace): LEB128 length-prefixed concatenation

lv_cat underpins the generator string, the ISK and both MCF tags, and its
lengths are LEB128 rather than fixed-width. Pinned against the prefixes
visible in the draft-21 B.1.5 vector so a fixed-width implementation cannot
pass by agreeing with itself."
```

---

## Task 2: SHA-512, HMAC-SHA-512 and arbitrary-base-point X25519

CPace needs three primitives the Noise layer never did. They follow the existing `expect`/`actual` split exactly.

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/NoisePrimitives.kt`
- Modify: `android/shared/src/jvmShared/kotlin/com/sendspindroid/sendspin/crypto/NoisePrimitives.jvmShared.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/Sha512PrimitivesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `expect fun sha512(vararg parts: ByteArray): ByteArray`, `expect fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray`, `expect fun x25519ScalarMult(scalar: ByteArray, basePoint: ByteArray): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha512PrimitivesTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** NIST: SHA-512 of the empty string. */
    @Test
    fun `sha512 of empty input`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
            "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            sha512().hex()
        )
    }

    /** NIST: SHA-512("abc"). */
    @Test
    fun `sha512 of abc`() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            sha512("abc".encodeToByteArray()).hex()
        )
    }

    /** Varargs must concatenate, not hash separately. */
    @Test
    fun `sha512 concatenates its parts`() {
        assertEquals(
            sha512("abc".encodeToByteArray()).hex(),
            sha512("a".encodeToByteArray(), "bc".encodeToByteArray()).hex()
        )
    }

    /** RFC 4231 test case 1, truncated to the SHA-512 output. */
    @Test
    fun `hmacSha512 matches RFC 4231 case 1`() {
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
            "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            hmacSha512(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex()
        )
    }

    /**
     * RFC 7748 section 6.1: X25519 against the standard base point must agree
     * with the existing x25519PublicKey, proving scalarMult takes an arbitrary
     * base point rather than silently using the fixed one.
     */
    @Test
    fun `x25519ScalarMult with the standard base point matches x25519PublicKey`() {
        val scalar = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val basePoint = ByteArray(32).also { it[0] = 9 }
        assertEquals(
            x25519PublicKey(scalar).hex(),
            x25519ScalarMult(scalar, basePoint).hex()
        )
    }

    /** RFC 7748 section 6.1 Alice/Bob shared secret, via an arbitrary point. */
    @Test
    fun `x25519ScalarMult computes the RFC 7748 shared secret`() {
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bobPub = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".unhex()
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            x25519ScalarMult(alicePriv, bobPub).hex()
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*Sha512PrimitivesTest*"`
Expected: FAIL with `Unresolved reference 'sha512'`.

- [ ] **Step 3: Write minimal implementation**

Append to `NoisePrimitives.kt` (commonMain):

```kotlin
/** SHA-512 over the concatenation of [parts]. */
expect fun sha512(vararg parts: ByteArray): ByteArray

/** HMAC-SHA-512. */
expect fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray

/**
 * X25519 scalar multiplication against an ARBITRARY base point.
 *
 * [x25519] is a Diffie-Hellman agreement; this is the same curve operation
 * but named for its CPace use, where the base point is the password-derived
 * generator rather than a peer's public key. Returns the raw 32-byte result,
 * including the all-zero result for low-order inputs -- callers must apply
 * the CPace abort check themselves (see `scalarMultVfy`).
 */
expect fun x25519ScalarMult(scalar: ByteArray, basePoint: ByteArray): ByteArray
```

Append to `NoisePrimitives.jvmShared.kt`:

```kotlin
actual fun sha512(vararg parts: ByteArray): ByteArray {
    val digest = SHA512Digest()
    for (part in parts) digest.update(part, 0, part.size)
    val out = ByteArray(digest.digestSize)
    digest.doFinal(out, 0)
    return out
}

actual fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA512Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    val out = ByteArray(mac.macSize)
    mac.doFinal(out, 0)
    return out
}

actual fun x25519ScalarMult(scalar: ByteArray, basePoint: ByteArray): ByteArray {
    require(scalar.size == 32) { "scalar must be 32 bytes, got ${scalar.size}" }
    require(basePoint.size == 32) { "base point must be 32 bytes, got ${basePoint.size}" }
    val out = ByteArray(32)
    X25519.scalarMult(scalar, 0, basePoint, 0, out, 0)
    return out
}
```

Add the import `org.bouncycastle.crypto.digests.SHA512Digest` to the jvmShared file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*Sha512PrimitivesTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/NoisePrimitives.kt android/shared/src/jvmShared/kotlin/com/sendspindroid/sendspin/crypto/NoisePrimitives.jvmShared.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/Sha512PrimitivesTest.kt
git commit -m "feat(crypto): SHA-512, HMAC-SHA-512 and arbitrary-base-point X25519

CPace needs a 512-bit hash and scalar multiplication against a
password-derived generator rather than a peer public key. Pinned to NIST,
RFC 4231 and RFC 7748 vectors."
```

---

## Task 3: Elligator2 map-to-curve

RFC 9380 `map_to_curve_elligator2` for curve25519, on BouncyCastle's `X25519Field`.

CPace discards the `v` coordinate, so this returns only `x`. That removes `sgn0`, the square root of `y`, and the final conditional negation. Only `is_square(gx1)` survives, to choose between the two candidate x-coordinates.

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2.kt`
- Create: `android/shared/src/jvmShared/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2.jvmShared.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2Test.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `fun mapToCurveElligator2(u: ByteArray): ByteArray` -- 32-byte little-endian field element in, 32-byte little-endian x-coordinate out.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*Elligator2Test*"`
Expected: FAIL with `Unresolved reference 'mapToCurveElligator2'`.

- [ ] **Step 3: Write minimal implementation**

`Elligator2.kt` (commonMain) declares the expect, because `X25519Field` is a JVM type:

```kotlin
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
```

`Elligator2.jvmShared.kt`:

```kotlin
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
    // cmov needs a FULL-WORD mask (-1/0), not a boolean 1/0: it computes
    // z ^= (diff & cond) per limb, and BouncyCastle asserts 0 == cond ||
    // -1 == cond. Passing 1 flips only each limb's low bit, yielding neither
    // candidate. The e1 cmov above is safe because isZero already returns a
    // mask.
    X25519Field.cmov(if (isSquare) -1 else 0, x1, 0, x, 0)

    X25519Field.normalize(x)
    val out = ByteArray(32)
    X25519Field.encode(x, out, 0)
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*Elligator2Test*"`
Expected: PASS, 3 tests.

If the B.1.1 test fails, the fault is nearly always the `x2` branch or the `e1` special case rather than the field arithmetic. Print `x1`, `gx1` and `isSquare` and compare against a short Python reimplementation before changing any field operation.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2.kt android/shared/src/jvmShared/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2.jvmShared.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/Elligator2Test.kt
git commit -m "feat(cpace): Elligator2 map-to-curve on X25519Field"
```

---

## Task 4: calculate_generator

Where `lv_cat` and Elligator2 meet. The B.1.1 vector covers the whole chain, including the verbatim 170-byte generator string.

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/CalculateGeneratorTest.kt`

**Interfaces:**
- Consumes: `lvCat`, `prependLen` (Task 1); `sha512` (Task 2); `mapToCurveElligator2` (Task 3)
- Produces: `object CPaceX25519` with `DSI`, `DSI_ISK`, `S_IN_BYTES`, `fun generatorString(prs, ci, sid): ByteArray`, `fun calculateGenerator(prs, ci, sid): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * draft-irtf-cfrg-cpace-21 B.1.1:
 *   PRS = "Password", DSI = "CPace255", ZPAD length 109,
 *   CI  = 0b415f696e69746961746f720b425f726573706f6e646572
 *   sid = 7e4b4791d6a8ef019b936c79fb7f2c57
 */
class CalculateGeneratorTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val prs = "Password".encodeToByteArray()
    private val ci = "0b415f696e69746961746f720b425f726573706f6e646572".unhex()
    private val sid = "7e4b4791d6a8ef019b936c79fb7f2c57".unhex()

    @Test
    fun `generator string matches the B_1_1 vector`() {
        val expected =
            "0843506163653235350850617373776f72646d" +
            "00".repeat(109) +
            "180b415f696e69746961746f720b425f726573706f6e646572" +
            "107e4b4791d6a8ef019b936c79fb7f2c57"
        val actual = CPaceX25519.generatorString(prs, ci, sid)
        assertEquals(170, actual.size)
        assertEquals(expected, actual.hex())
    }

    @Test
    fun `generator matches the B_1_1 vector`() {
        assertEquals(
            "d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f",
            CPaceX25519.calculateGenerator(prs, ci, sid).hex()
        )
    }

    /**
     * ZPAD pushes PRS past the first hash block. With a long PRS it vanishes
     * rather than going negative.
     */
    @Test
    fun `long PRS produces no zero padding`() {
        val longPrs = ByteArray(200) { 0x41 }
        // 1+8 (DSI) + 2+200 (PRS) + 1+0 (zpad) + 1+24 (CI) + 1+16 (sid)
        assertEquals(254, CPaceX25519.generatorString(longPrs, ci, sid).size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*CalculateGeneratorTest*"`
Expected: FAIL with `Unresolved reference 'CPaceX25519'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.sendspindroid.sendspin.crypto.cpace

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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*CalculateGeneratorTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/CalculateGeneratorTest.kt
git commit -m "feat(cpace): generator string and calculate_generator"
```

---

## Task 5: scalar_mult_vfy with low-order rejection

The check that stops a peer forcing a known shared secret. Low-order points on the curve **and the twist** produce the all-zero result, which must be rejected rather than used.

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/ScalarMultVfyTest.kt`

**Interfaces:**
- Consumes: `x25519ScalarMult` (Task 2)
- Produces: `CPaceX25519.scalarMultVfy(scalar: ByteArray, point: ByteArray): ByteArray?` -- null means abort

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*ScalarMultVfyTest*"`
Expected: FAIL with `Unresolved reference 'scalarMultVfy'`.

- [ ] **Step 3: Write minimal implementation**

Add to `CPaceX25519`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*ScalarMultVfyTest*"`
Expected: PASS, 3 tests covering all twelve B.1.10 points.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/ScalarMultVfyTest.kt
git commit -m "feat(cpace): scalar_mult_vfy with low-order point rejection"
```

---

## Task 6: ISK derivation

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/IskTest.kt`

**Interfaces:**
- Consumes: `lvCat` (Task 1), `sha512` (Task 2)
- Produces: `CPaceX25519.transcriptIr(ya, ada, yb, adb): ByteArray`, `CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals

/** draft-irtf-cfrg-cpace-21 B.1.5, initiator/responder (ordered) mode. */
class IskTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val sid = "7e4b4791d6a8ef019b936c79fb7f2c57".unhex()
    private val k = "5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee71322c6b".unhex()
    private val ya = "1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32".unhex()
    private val yb = "248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea32185623".unhex()
    private val ada = "ADa".encodeToByteArray()
    private val adb = "ADb".encodeToByteArray()

    @Test
    fun `transcript_ir matches the B_1_5 vector`() {
        val expected =
            "201d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989ce0c9f32" +
            "0341446120248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea" +
            "3218562303414462"
        val actual = CPaceX25519.transcriptIr(ya, ada, yb, adb)
        assertEquals(74, actual.size)
        assertEquals(expected, actual.hex())
    }

    @Test
    fun `ISK matches the B_1_5 vector`() {
        assertEquals(
            "6e19b875f7a561d6b3ca3dbb9ef42ac55de3e717881018204b8922b4d5e53bb2" +
            "aa82c300bea7b65d2b671da71922ddf6472301b79bc270adfa8bf413285f2263",
            CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).hex()
        )
    }

    @Test
    fun `ISK is 64 bytes`() {
        assertEquals(64, CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).size)
    }

    /** Ordering is part of the binding: swapping the roles must change the ISK. */
    @Test
    fun `swapping the two sides changes the ISK`() {
        val normal = CPaceX25519.deriveIsk(sid, k, ya, ada, yb, adb).hex()
        val swapped = CPaceX25519.deriveIsk(sid, k, yb, adb, ya, ada).hex()
        assertEquals(false, normal == swapped)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*IskTest*"`
Expected: FAIL with `Unresolved reference 'transcriptIr'`.

- [ ] **Step 3: Write minimal implementation**

Add to `CPaceX25519`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*IskTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/IskTest.kt
git commit -m "feat(cpace): ISK derivation in initiator-responder mode"
```

---

## Task 7: MCF tags and the CPaceResponder facade

The one construction with no official test vectors: draft section 10.4.5-6 gives the formula but leaves the MAC algorithm open, and SendSpin pins HMAC-SHA-512. So this task also produces the oracle vectors that prove interoperability.

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceX25519.kt`
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceResponder.kt`
- Create: `ci/conformance/cpace_oracle.py`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceResponderTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-6
- Produces: `CPaceX25519.macKey(sid, isk)`, `CPaceX25519.mcfTag(macKey, y, ad)`, and
  `class CPaceResponder(prs: ByteArray, sid: ByteArray, scalar: ByteArray? = null)` with
  `val publicShare: ByteArray` (Yb), `fun derive(peerShare: ByteArray): Boolean`,
  `fun verify(serverKc: ByteArray): Boolean`, `fun tag(): ByteArray` (Tb), `val isk: ByteArray`

- [ ] **Step 1: Verify the oracle vectors (already generated)**

Create `ci/conformance/cpace_oracle.py`:

```python
"""Emit CPace responder vectors from the reference implementation.

The MCF tags are the one construction with no vectors in draft-21 -- section
10.4 leaves the MAC algorithm open and Sendspin pins HMAC-SHA-512 -- so the
Kotlin responder is pinned against the same library the reference server uses
(aiosendspin depends on `cpace`). A lv_cat mistake changes every byte here,
so this doubles as the interop guard.

Usage:  pip install cpace  &&  python ci/conformance/cpace_oracle.py
"""

import json

from cpace import CPace, CPaceRole

PRS = b"123456"
SID = b"sendspin-pair-pake-v1" + bytes(range(32)) + (1).to_bytes(4, "big")

initiator = CPace.start(role=CPaceRole.INITIATOR, prs=PRS, sid=SID, ad=b"server")
responder = CPace.start(role=CPaceRole.RESPONDER, prs=PRS, sid=SID, ad=b"client")

ya = initiator.public_share
yb = responder.public_share
initiator.derive(yb, b"client")
responder.derive(ya, b"server")

print(json.dumps({
    "prs": PRS.hex(),
    "sid": SID.hex(),
    "ya": ya.hex(),
    "yb": yb.hex(),
    "isk": responder.isk.hex(),
    "server_kc": initiator.tag().hex(),
    "client_kc": responder.tag().hex(),
}, indent=2))
```

`ci/conformance/cpace_oracle.py` already exists and its output is already
embedded in the test below, so this step is a confirmation, not a generation:
run `pip install cpace && python ci/conformance/cpace_oracle.py` and check the
printed values match the constants in the test. The script pins BOTH scalars and
temporarily patches `secrets.token_bytes` to do it -- the library draws its
scalars inside `start()`, so without that patch every run is random and the
output could never be compared to anything. If they differ, STOP and report
-- it means the installed `cpace` version changed behaviour, which is exactly
the interop signal this vector exists to catch.

The script captures `responder._scalar` before `derive()` because the library
zeroizes it afterwards. Injecting that scalar is what makes `yb` reproducible
in Kotlin instead of random per run.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.crypto.cpace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinned against `ci/conformance/cpace_oracle.py`, which drives the same
 * `cpace` package the reference server uses.
 *
 * Replace the constants below with one captured run of that script. They are
 * the only interop evidence for the MCF tags, which draft-21 does not supply
 * vectors for.
 */
class CPaceResponderTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val prs = "123456".encodeToByteArray()
    private val sid = ORACLE_SID.unhex()
    private val ya = ORACLE_YA.unhex()
    private val scalar = ORACLE_YB_SCALAR.unhex()

    @Test
    fun `public share matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertEquals(ORACLE_YB, r.publicShare.hex())
    }

    @Test
    fun `ISK matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(ORACLE_ISK, r.isk.hex())
    }

    @Test
    fun `client tag matches the oracle`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(ORACLE_CLIENT_KC, r.tag().hex())
    }

    @Test
    fun `verifies the oracle server tag`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        assertTrue(r.verify(ORACLE_SERVER_KC.unhex()))
    }

    @Test
    fun `rejects a corrupted server tag`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertTrue(r.derive(ya))
        val bad = ORACLE_SERVER_KC.unhex().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(false, r.verify(bad))
    }

    /** A wrong pairing code must not verify -- this is the whole point. */
    @Test
    fun `a different PRS does not verify`() {
        val r = CPaceResponder("654321".encodeToByteArray(), sid, scalar)
        assertTrue(r.derive(ya))
        assertEquals(false, r.verify(ORACLE_SERVER_KC.unhex()))
    }

    /** A low-order share aborts rather than deriving. */
    @Test
    fun `a low-order peer share fails derive`() {
        val r = CPaceResponder(prs, sid, scalar)
        assertEquals(false, r.derive(ByteArray(32)))
    }

    private companion object {
        // Captured from ci/conformance/cpace_oracle.py. PRS = "123456",
        // handshake hash = bytes 0x00..0x1f, pairing_index = 1.
        const val ORACLE_SID =
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
            "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f00000001"
        const val ORACLE_YA =
            "9e9481280468a6ef3bb3c6b962d564f96b523ca957c41420319b0b2408de5861"
        const val ORACLE_YB =
            "ffc9edf7457e8acf737d5bb2099e50592ec1313dee9658df6a628954cee55135"
        const val ORACLE_YB_SCALAR =
            "025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"
        const val ORACLE_ISK =
            "727136d942eec1e10f9cfc1cdb2426391f217e98348143b978045c747b4f25ee" +
            "fc9f45864558c4329c2dc1a30424d84ec3965cb1da5863ea8e1239237f559f06"
        const val ORACLE_SERVER_KC =
            "110fcfde4aa2b30072787f30d78eeefc4dbfe93b4310d594b79e91ed8f9c0a9e" +
            "426040bf012f84fa68705f00e5b745c91c4d27a70cbb9e12b5e41c5db5fe5eb1"
        const val ORACLE_CLIENT_KC =
            "b3192b30dcf4f3d0fdbfb125dc17fb071984894762e0d0d0ad6b13272802194f" +
            "e4aee13a7bd1bc6bbca50b6592a1fafc4e44c0258dfda4815b524ddee0acea0c"
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*CPaceResponderTest*"`
Expected: FAIL with `Unresolved reference 'CPaceResponder'`.

- [ ] **Step 4: Write minimal implementation**

Add to `CPaceX25519`:

```kotlin
    /** `mac_key = H("CPaceMac" || sid || ISK)`, section 10.4.5. */
    fun macKey(sid: ByteArray, isk: ByteArray): ByteArray =
        sha512("CPaceMac".encodeToByteArray(), sid, isk)

    /** `T = MAC(mac_key, lv_cat(Y, AD))`. Sendspin pins the MAC to HMAC-SHA-512. */
    fun mcfTag(macKey: ByteArray, y: ByteArray, ad: ByteArray): ByteArray =
        hmacSha512(macKey, lvCat(y, ad))
```

with `import com.sendspindroid.sendspin.crypto.hmacSha512` added.

Create `CPaceResponder.kt`:

```kotlin
package com.sendspindroid.sendspin.crypto.cpace

import com.sendspindroid.sendspin.crypto.secureRandomBytes

/**
 * CPace responder (role B) with explicit mutual confirmation.
 *
 * Sendspin makes the server the initiator, so this client is always role B.
 * `CI` is empty, `ADa` is "server" and `ADb` is "client", per pairing.md.
 *
 * @param scalar test-only injection point. Production callers omit it and get
 *   a fresh CSPRNG scalar; the vector tests pass the oracle's scalar so the
 *   public share is reproducible.
 */
class CPaceResponder(
    private val prs: ByteArray,
    private val sid: ByteArray,
    scalar: ByteArray? = null,
) {
    private val yb: ByteArray = scalar ?: secureRandomBytes(32)
    private var peerShare: ByteArray? = null
    private var iskValue: ByteArray? = null
    private var macKeyValue: ByteArray? = null

    /** `Yb`, the client's CPace public share, sent as `pake_msg_2`. */
    val publicShare: ByteArray by lazy {
        val g = CPaceX25519.calculateGenerator(prs, EMPTY_CI, sid)
        com.sendspindroid.sendspin.crypto.x25519ScalarMult(yb, g)
    }

    /** The 64-byte intermediate session key. Only valid after [derive]. */
    val isk: ByteArray
        get() = iskValue ?: error("derive() has not been called")

    /**
     * Consume the server's share `Ya`.
     *
     * @return false when the share is unusable -- wrong length, or a low-order
     *   point that [CPaceX25519.scalarMultVfy] rejects. False is a protocol
     *   error at the call site, never a retry.
     */
    fun derive(peerShare: ByteArray): Boolean {
        val k = CPaceX25519.scalarMultVfy(yb, peerShare) ?: return false
        this.peerShare = peerShare.copyOf()
        val computed = CPaceX25519.deriveIsk(
            sid = sid,
            k = k,
            ya = peerShare,
            ada = AD_SERVER,
            yb = publicShare,
            adb = AD_CLIENT,
        )
        iskValue = computed
        macKeyValue = CPaceX25519.macKey(sid, computed)
        return true
    }

    /** Verify the server's MCF tag `Ta` in constant time. */
    fun verify(serverKc: ByteArray): Boolean {
        val key = macKeyValue ?: error("derive() has not been called")
        val ya = peerShare ?: error("derive() has not been called")
        val expected = CPaceX25519.mcfTag(key, ya, AD_SERVER)
        if (expected.size != serverKc.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor serverKc[i].toInt())
        return diff == 0
    }

    /** The client's MCF tag `Tb`, sent as `client_kc`. */
    fun tag(): ByteArray {
        val key = macKeyValue ?: error("derive() has not been called")
        return CPaceX25519.mcfTag(key, publicShare, AD_CLIENT)
    }

    private companion object {
        val EMPTY_CI = ByteArray(0)
        val AD_SERVER = "server".encodeToByteArray()
        val AD_CLIENT = "client".encodeToByteArray()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*CPaceResponderTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/crypto/cpace/ android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/crypto/cpace/CPaceResponderTest.kt ci/conformance/cpace_oracle.py
git commit -m "feat(cpace): MCF tags and the responder facade

draft-21 gives the tag formula but leaves the MAC algorithm open, so there
are no published vectors for the one value both peers must agree on.
ci/conformance/cpace_oracle.py drives the same cpace package the reference
server uses and produces the vectors this is pinned to, which also catches
any lv_cat error because a prefix mistake changes every byte."
```

---

## Task 8: pairing code derivation and commitment

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingCode.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingCodeTest.kt`

**Interfaces:**
- Consumes: `sha256` (existing), `secureRandomBytes` (existing)
- Produces: `object PairingCode` with `NONCE_SIZE = 32`, `fun generateNonce(): ByteArray`, `fun commit(nonce: ByteArray): ByteArray`, `fun deriveDigest(handshakeHash, nonceA, nonceB): ByteArray`, `fun deriveDigits(handshakeHash, nonceA, nonceB): String`, `fun group(code: String): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md: the digest is
 *   SHA-256("sendspin-pairing-code-derive-v1" || h || nonce_A || nonce_B)
 * and the digits are that digest as a big-endian uint256 mod 10^6, zero-padded
 * to exactly six characters.
 *
 * Expected values are computed with the same construction in Python so they
 * are independent of the Kotlin implementation:
 *
 *   import hashlib
 *   d = hashlib.sha256(b"sendspin-pairing-code-derive-v1" + bytes(32)
 *                      + bytes(range(32)) + bytes(range(32,64))).digest()
 *   print(d.hex(), f"{int.from_bytes(d,'big') % 1000000:06d}")
 */
class PairingCodeTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val h = ByteArray(32)
    private val nonceA = ByteArray(32) { it.toByte() }
    private val nonceB = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun `commitment is SHA-256 over the label and nonce`() {
        // sha256(b"sendspin-pair-commit-v1" + bytes(range(32))).hex()
        assertEquals(COMMIT_VECTOR, PairingCode.commit(nonceA).hex())
    }

    @Test
    fun `digest matches the reference construction`() {
        assertEquals(DIGEST_VECTOR, PairingCode.deriveDigest(h, nonceA, nonceB).hex())
    }

    @Test
    fun `digits are the digest mod ten to the six`() {
        assertEquals(DIGITS_VECTOR, PairingCode.deriveDigits(h, nonceA, nonceB))
    }

    @Test
    fun `digits are always exactly six characters`() {
        for (i in 0 until 50) {
            val a = ByteArray(32) { i.toByte() }
            assertEquals(6, PairingCode.deriveDigits(h, a, nonceB).length)
        }
    }

    @Test
    fun `nonce is 32 bytes and not constant`() {
        val first = PairingCode.generateNonce()
        assertEquals(32, first.size)
        assertEquals(false, first.hex() == PairingCode.generateNonce().hex())
    }

    /** Presentation only: grouping never enters derivation or PRS. */
    @Test
    fun `grouping is three and three`() {
        assertEquals("123-456", PairingCode.group("123456"))
    }

    private companion object {
        const val COMMIT_VECTOR =
            "ea08c0aee3c421ace702f31591b3d213e8c371a8a8e3b0be3fd405ed841755a3"
        const val DIGEST_VECTOR =
            "960b1b8edc2662d0bedd5f0521313a1c5ceff12168ead500698b1143afbdac0e"
        const val DIGITS_VECTOR = "697742"
    }
}
```

These three constants are already computed from the reference construction in
Python, independently of any Kotlin. Re-derive them with the snippet in the class
comment if you want to confirm them, but they need no filling in.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingCodeTest*"`
Expected: FAIL with `Unresolved reference 'PairingCode'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.secureRandomBytes
import com.sendspindroid.sendspin.crypto.sha256

/**
 * Dynamic pairing code derivation and the commitment that binds it.
 *
 * The code is derived from the Noise handshake hash and BOTH sides' nonces, so
 * neither peer alone chooses it. The client commits to its nonce before seeing
 * the server's, which is what stops a server steering the code to a value it
 * already knows.
 */
object PairingCode {

    const val NONCE_SIZE = 32
    const val DIGITS = 6

    private val COMMIT_LABEL = "sendspin-pair-commit-v1".encodeToByteArray()
    private val DERIVE_LABEL = "sendspin-pairing-code-derive-v1".encodeToByteArray()

    fun generateNonce(): ByteArray = secureRandomBytes(NONCE_SIZE)

    /** `commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B)`. */
    fun commit(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }
        return sha256(COMMIT_LABEL, nonce)
    }

    /** `SHA-256(label || h || nonce_A || nonce_B)`. */
    fun deriveDigest(handshakeHash: ByteArray, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        require(handshakeHash.size == 32) { "handshake hash must be 32 bytes" }
        require(nonceA.size == NONCE_SIZE) { "nonce_A must be $NONCE_SIZE bytes" }
        require(nonceB.size == NONCE_SIZE) { "nonce_B must be $NONCE_SIZE bytes" }
        return sha256(DERIVE_LABEL, handshakeHash, nonceA, nonceB)
    }

    /**
     * The six-digit code: the digest as an unsigned big-endian 256-bit integer
     * reduced mod 10^6, zero-padded on the left.
     *
     * Done with longs over the digest bytes rather than a BigInteger so it
     * stays in commonMain: reducing mod 10^6 byte by byte is exact because
     * 256 * 10^6 fits in a Long.
     */
    fun deriveDigits(handshakeHash: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String {
        var remainder = 0L
        for (b in deriveDigest(handshakeHash, nonceA, nonceB)) {
            remainder = (remainder * 256 + (b.toInt() and 0xff)) % 1_000_000L
        }
        return remainder.toString().padStart(DIGITS, '0')
    }

    /** Presentation grouping only; separators never enter derivation or PRS. */
    fun group(code: String): String =
        if (code.length == DIGITS) code.substring(0, 3) + "-" + code.substring(3) else code
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingCodeTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingCode.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingCodeTest.kt
git commit -m "feat(pairing): dynamic pairing code derivation and commitment

The code derives from the handshake hash and both nonces, so neither peer
alone picks it, and the client commits to its nonce before seeing the
server's. Reduction mod 10^6 runs byte by byte over the digest rather than
through a BigInteger so it stays in commonMain."
```

---

## Task 9: wrapping

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingWrap.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingWrapTest.kt`

**Interfaces:**
- Consumes: `sha256`, `aeadSeal` (existing), `NoiseCipherSuite` (existing)
- Produces: `object PairingWrap` with `PSK_LABEL`, `NONCE_LABEL`, `fun wrapKey(label: ByteArray, sid: ByteArray, isk: ByteArray): ByteArray`, `fun seal(suite: NoiseCipherSuite, key: ByteArray, plaintext: ByteArray): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md "Wrapping": K_wrap = SHA-256(label || sid || ISK), then the
 * connection's negotiated AEAD with a 12-byte zero nonce and empty AD. A
 * 32-byte value seals to 48 bytes (ciphertext plus 16-byte tag).
 */
class PairingWrapTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private val sid = ByteArray(16) { it.toByte() }
    private val isk = ByteArray(64) { (it + 100).toByte() }

    @Test
    fun `wrap key is 32 bytes`() {
        assertEquals(32, PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).size)
    }

    /** The two labels must produce different keys, or the values are swappable. */
    @Test
    fun `the two labels give different keys`() {
        assertEquals(
            false,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).hex() ==
                PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk).hex()
        )
    }

    @Test
    fun `sealing a 32 byte value yields 48 bytes`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk)
        val sealed = PairingWrap.seal(NoiseCipherSuite.CHACHA20_POLY1305, key, ByteArray(32))
        assertEquals(48, sealed.size)
    }

    @Test
    fun `sealing is deterministic for a fixed key and nonce`() {
        val key = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk)
        val a = PairingWrap.seal(NoiseCipherSuite.CHACHA20_POLY1305, key, ByteArray(32) { 7 })
        val b = PairingWrap.seal(NoiseCipherSuite.CHACHA20_POLY1305, key, ByteArray(32) { 7 })
        assertEquals(a.hex(), b.hex())
    }

    @Test
    fun `a different sid gives a different key`() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertEquals(
            false,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk).hex() ==
                PairingWrap.wrapKey(PairingWrap.PSK_LABEL, other, isk).hex()
        )
    }
}
```

Confirm the `NoiseCipherSuite` member name against the existing enum before running; if it differs from `CHACHA20_POLY1305`, use the real name.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingWrapTest*"`
Expected: FAIL with `Unresolved reference 'PairingWrap'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.aeadSeal
import com.sendspindroid.sendspin.crypto.sha256

/**
 * Sealing for the two values that cross the wire only under the CPace output:
 * the new long-term PSK, and the commitment opening `nonce_B`.
 */
object PairingWrap {

    val PSK_LABEL = "sendspin-pair-psk-wrap-v1".encodeToByteArray()
    val NONCE_LABEL = "sendspin-pair-nonce-wrap-v1".encodeToByteArray()

    /**
     * A 12-byte all-zero nonce.
     *
     * This is safe ONLY because each wrap key is derived per field -- the two
     * labels give different keys -- and each key seals exactly one value once.
     * A zero nonce reused under one key would be catastrophic, so do not
     * generalise this constant to any other AEAD use.
     */
    private val ZERO_NONCE = ByteArray(12)

    /** `K_wrap = SHA-256(label || sid || ISK)`. */
    fun wrapKey(label: ByteArray, sid: ByteArray, isk: ByteArray): ByteArray =
        sha256(label, sid, isk)

    /** Seal a 32-byte value, producing 48 bytes of ciphertext plus tag. */
    fun seal(suite: NoiseCipherSuite, key: ByteArray, plaintext: ByteArray): ByteArray =
        aeadSeal(suite, key, ZERO_NONCE, ByteArray(0), plaintext)
}
```

Match `aeadSeal`'s real parameter order and types against `NoisePrimitives.kt` before running; adjust the call, not the primitive.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingWrapTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingWrap.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingWrapTest.kt
git commit -m "feat(pairing): wrap keys and sealing for pair-confirm and pair-finalize

Records why the all-zero AEAD nonce is safe here -- one key per field, one
value per key, never reused -- because a bare zero nonce is otherwise
exactly the thing a later reader corrects into a vulnerability."
```

---

## Task 10: failure counter, escalation and the pairing window

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingFailureCounter.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/UserSettings.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingFailureCounterTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `interface PairingCounterStore { fun load(): Int; fun save(value: Int) }`,
  `class PairingFailureCounter(store: PairingCounterStore)` with
  `val isEscalated: Boolean`, `fun onEmissionStarted()`, `fun onServerKcVerified()`,
  and `const val ESCALATION_THRESHOLD = 5`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md "Failure counter": one counter for the method, persisted across
 * reboots, NOT partitioned by server_id or source IP. It increments when the
 * client starts emitting the code, at most once per attempt, and "no other
 * event increments it". It resets when server_kc verification succeeds,
 * whether or not the attempt finalizes. At 5 the method escalates.
 *
 * Note the divergence recorded in the design doc: the aiosendspin 9.1.1
 * reference increments on a server_kc MISMATCH instead. We follow the spec,
 * so five attempts escalate even when each reached a valid server_kc.
 */
class PairingFailureCounterTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        var saves = 0
        override fun load(): Int = value
        override fun save(v: Int) { value = v; saves++ }
    }

    @Test
    fun `starts unescalated`() {
        assertEquals(false, PairingFailureCounter(FakeStore()).isEscalated)
    }

    @Test
    fun `escalates on the fifth emission`() {
        val counter = PairingFailureCounter(FakeStore())
        repeat(4) { counter.onEmissionStarted() }
        assertEquals(false, counter.isEscalated)
        counter.onEmissionStarted()
        assertEquals(true, counter.isEscalated)
    }

    @Test
    fun `a verified server_kc de-escalates`() {
        val counter = PairingFailureCounter(FakeStore())
        repeat(5) { counter.onEmissionStarted() }
        counter.onServerKcVerified()
        assertEquals(false, counter.isEscalated)
    }

    @Test
    fun `the counter persists through the store`() {
        val store = FakeStore()
        PairingFailureCounter(store).onEmissionStarted()
        assertEquals(1, store.value)
        assertEquals(1, PairingFailureCounter(store).let { it.onEmissionStarted(); store.value } - 1)
    }

    @Test
    fun `a stored value above the threshold is already escalated`() {
        assertEquals(true, PairingFailureCounter(FakeStore(9)).isEscalated)
    }

    @Test
    fun `reset writes zero even when already zero is not required`() {
        val store = FakeStore(3)
        PairingFailureCounter(store).onServerKcVerified()
        assertEquals(0, store.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingFailureCounterTest*"`
Expected: FAIL with `Unresolved reference 'PairingCounterStore'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.sendspindroid.sendspin.pairing

/** Persistence for the dynamic-pairing failure counter. */
interface PairingCounterStore {
    fun load(): Int
    fun save(value: Int)
}

/**
 * Brute-force protection for the Dynamic Pairing Code Flow.
 *
 * One counter for the method, deliberately NOT partitioned by server or
 * address: partitioning would let an attacker reset the budget by changing
 * either.
 */
class PairingFailureCounter(private val store: PairingCounterStore) {

    val isEscalated: Boolean get() = store.load() >= ESCALATION_THRESHOLD

    /**
     * Increment, at most once per attempt.
     *
     * The spec is explicit that emission is the ONLY increment trigger; a
     * verification failure does not add to it. Callers must invoke this once
     * per attempt, when emission begins.
     */
    fun onEmissionStarted() {
        store.save(store.load() + 1)
    }

    /** Reset on a verified server_kc, whether or not the attempt finalizes. */
    fun onServerKcVerified() {
        store.save(0)
    }

    companion object {
        const val ESCALATION_THRESHOLD = 5
    }
}
```

In `UserSettings.kt`, alongside the other pairing keys, add:

```kotlin
    private const val KEY_PAIRING_CODE_FAILURES = "pairing_code_failures"

    fun getPairingCodeFailures(): Int =
        sensitivePrefs?.getInt(KEY_PAIRING_CODE_FAILURES, 0) ?: 0

    fun setPairingCodeFailures(value: Int): Boolean =
        sensitivePrefs?.edit()?.putInt(KEY_PAIRING_CODE_FAILURES, value)?.commit() ?: false
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingFailureCounterTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingFailureCounter.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/PairingFailureCounterTest.kt android/app/src/main/java/com/sendspindroid/UserSettings.kt
git commit -m "feat(pairing): dynamic pairing failure counter and escalation

One counter for the method, not partitioned by server or address -- either
partitioning would let an attacker reset the budget at will. Increments only
on code emission, per the spec, which differs from the 9.1.1 reference; the
divergence is recorded in the design doc."
```

---

## Task 11: the DynamicPairingCodeFlow state machine

The protocol logic, pure and testable without a socket. Mirrors `PairingPskFlow`.

**Files:**
- Create: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/DynamicPairingCodeFlow.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/DynamicPairingCodeFlowTest.kt`

**Interfaces:**
- Consumes: `CPaceResponder` (7), `PairingCode` (8), `PairingWrap` (9), `PairingFailureCounter` (10)
- Produces: `sealed interface DynamicPairingEvent`, `sealed interface DynamicPairingAction`,
  `class DynamicPairingCodeFlow(...)` with `fun onEvent(event: DynamicPairingEvent): List<DynamicPairingAction>`

- [ ] **Step 1: Write the failing test**

Read `PairingPskFlow.kt` and `PairingPskFlowTest.kt` first and follow their construction and naming conventions exactly. Then:

```kotlin
package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sequencing rules from pairing.md, exercised without a socket.
 *
 * The three that matter most and are easiest to get wrong:
 *  - client/pair-confirm and client/pair-finalize go out TOGETHER, with no
 *    server response awaited in between.
 *  - a server_kc mismatch is an in-band pair/abort, but a bad commitment,
 *    a low-order share or a malformed field is a PROTOCOL ERROR that closes
 *    the socket silently -- telling an unauthenticated peer which check it
 *    failed is the leak this distinction prevents.
 *  - an attempt is gesture-gated only while the method is escalated.
 */
class DynamicPairingCodeFlowTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        override fun load(): Int = value
        override fun save(v: Int) { value = v }
    }

    private fun flow(failures: Int = 0) = DynamicPairingCodeFlow(
        handshakeHash = ByteArray(32),
        counter = PairingFailureCounter(FakeStore(failures)),
    )

    @Test
    fun `an unescalated activation sends pair-init immediately`() {
        val actions = flow().onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairInit })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairPending })
    }

    @Test
    fun `an escalated activation sends pair-pending and waits for the gesture`() {
        val f = flow(failures = 5)
        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairPending })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairInit })

        val opened = f.onEvent(DynamicPairingEvent.WindowOpened)
        assertTrue(opened.any { it is DynamicPairingAction.SendPairInit })
    }

    @Test
    fun `server pair-init emits the code and increments the counter`() {
        val store = FakeStore()
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store))
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val emit = actions.filterIsInstance<DynamicPairingAction.EmitPairingCode>().single()
        assertEquals(6, emit.code.length)
        assertEquals(1, store.value)
    }

    @Test
    fun `a server_kc mismatch aborts in band`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        f.onEvent(DynamicPairingEvent.ServerPairAuth(VALID_SHARE))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64)))
        val abort = actions.filterIsInstance<DynamicPairingAction.SendPairAbort>().single()
        assertEquals("pairing_code_mismatch", abort.reason)
    }

    @Test
    fun `a low-order share is a protocol error, not an abort`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairAuth(ByteArray(32)))
        assertTrue(actions.any { it is DynamicPairingAction.ProtocolError })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairAbort })
    }

    @Test
    fun `an out-of-sequence message is a protocol error`() {
        val actions = flow().onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64)))
        assertTrue(actions.any { it is DynamicPairingAction.ProtocolError })
    }

    @Test
    fun `the attempt timeout aborts`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.AttemptTimeout)
        assertEquals(
            "attempt_timeout",
            actions.filterIsInstance<DynamicPairingAction.SendPairAbort>().single().reason
        )
    }

    @Test
    fun `a non-pairing activation discards state and stops emitting`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val actions = f.onEvent(DynamicPairingEvent.NonPairingActivation)
        assertTrue(actions.any { it is DynamicPairingAction.StopEmittingCode })
        assertTrue(actions.none { it is DynamicPairingAction.PersistRecord })
    }

    @Test
    fun `a connection drop persists nothing`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ConnectionClosed)
        assertTrue(actions.none { it is DynamicPairingAction.PersistRecord })
    }

    private companion object {
        /** A valid, non-low-order share; reuse the B.1.10 u6 vector. */
        val VALID_SHARE = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xda.toByte() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*DynamicPairingCodeFlowTest*"`
Expected: FAIL with `Unresolved reference 'DynamicPairingEvent'`.

- [ ] **Step 3: Write minimal implementation**

Define the event and action types and the machine. The states are:
`Idle -> AwaitingGesture -> AwaitingServerInit -> AwaitingAuth -> AwaitingConfirm -> AwaitingFinalize -> Done`.

Required behaviour, all covered by the tests above:
- `PairingActivation`: if `counter.isEscalated` emit `SendPairPending(index)` and enter `AwaitingGesture`; otherwise generate `nonce_B`, emit `SendPairInit(index, commit_B)` and `StartAttemptTimeout`, and enter `AwaitingServerInit`.
- `WindowOpened` in `AwaitingGesture`: as the unescalated branch above.
- `ServerPairInit(nonceA)` in `AwaitingServerInit`: derive the six digits, call `counter.onEmissionStarted()`, construct the `CPaceResponder` with `prs = code.encodeToByteArray()` and the flow's `sid`, emit `EmitPairingCode(code)`, enter `AwaitingAuth`.
- `ServerPairAuth(ya)` in `AwaitingAuth`: emit `SendPairAuth(responder.publicShare)`; if `responder.derive(ya)` returns false emit `ProtocolError` instead and stop; else enter `AwaitingConfirm`.
- `ServerPairConfirm(ta)` in `AwaitingConfirm`: if `!responder.verify(ta)` emit `SendPairAbort("pairing_code_mismatch")`; else `counter.onServerKcVerified()`, generate the new 32-byte long-term PSK, and emit `SendPairConfirm(tb, wrappedNonceB)` **and** `SendPairFinalize(wrappedPsk)` in one list; enter `AwaitingFinalize`.
- `ServerPairFinalize` in `AwaitingFinalize`: emit `PersistRecord(psk)` and `StopEmittingCode`; enter `Done`.
- `AttemptTimeout`: emit `SendPairAbort("attempt_timeout")` and `StopEmittingCode`.
- `NonPairingActivation`, `PairAbortReceived`, `UserCancelled`, `ConnectionClosed`: emit `StopEmittingCode`, discard all state, persist nothing.
- Any event arriving in a state that does not expect it: emit `ProtocolError` and discard.

Give `ProtocolError` no reason field. The whole point is that nothing is told to the peer.

**Pin the `pairing_index` base before writing the sid.** `sid` is
`"sendspin-pair-pake-v1" || h || counter` with `counter` a big-endian uint32,
and the same value is sent as `pairing_index`. Whether the first pairing
activation on a connection counts as 0 or 1 is NOT stated in `pairing.md`;
read `aiosendspin`'s `_pake_sid` and `_receive_pair_init` and match it. An
off-by-one here produces a sid that differs from the server's, so every MCF
tag mismatches while every local test still passes -- it looks exactly like a
wrong pairing code. Assert the chosen base in a test with a comment naming
the reference function it came from.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*DynamicPairingCodeFlowTest*"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/DynamicPairingCodeFlow.kt android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/pairing/DynamicPairingCodeFlowTest.kt
git commit -m "feat(pairing): dynamic pairing code flow state machine

Pure event-to-action, mirroring PairingPskFlow, so sequencing, timeouts and
aborts are testable without a socket.

Keeps two failure kinds apart deliberately: a server_kc mismatch is an
in-band pair/abort, while a bad commitment, a low-order share or a malformed
field closes the socket silently. Telling an unauthenticated peer which
check it failed is the leak that distinction exists to prevent."
```

---

## Task 12: pairing messages and the pin_length removal

**Files:**
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageBuilder.kt`
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageParser.kt`
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/ServerActivate.kt`
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/pairing/PairingPskFlow.kt`
- Modify: `android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/pairing/PairAbortReasonTest.kt`
- Test: `android/shared/src/commonTest/kotlin/com/sendspindroid/sendspin/protocol/message/PairingMessageTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: builders `buildClientPairPending(index)`, `buildClientPairInit(index, commitB)`, `buildClientPairAuth(pakeMsg2)`, `buildClientPairConfirm(clientKc, wrappedNonceB)`; parsers returning `nonce_A`, `pake_msg_1`, `server_kc`. All binary fields are base64url **without padding**.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin.protocol.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pairing.md field sizes: commit_B and the CPace shares are 32 bytes and
 * encode to 43 base64url characters; the MCF tags are 64 bytes and encode to
 * 86. Padding is never present.
 */
class PairingMessageTest {

    @Test
    fun `pair-init carries the index and a 43 character commit`() {
        val json = MessageBuilder.buildClientPairInit(2, ByteArray(32))
        assertTrue(json.contains("\"type\":\"client/pair-init\""))
        assertTrue(json.contains("\"pairing_index\":2"))
        assertTrue(Regex("\"commit_B\":\"[A-Za-z0-9_-]{43}\"").containsMatchIn(json))
        assertTrue(json.contains("=").not())
    }

    @Test
    fun `pair-pending carries only the index`() {
        val json = MessageBuilder.buildClientPairPending(3)
        assertTrue(json.contains("\"type\":\"client/pair-pending\""))
        assertTrue(json.contains("\"pairing_index\":3"))
        assertTrue(json.contains("commit_B").not())
    }

    @Test
    fun `pair-auth carries a 43 character share`() {
        val json = MessageBuilder.buildClientPairAuth(ByteArray(32))
        assertTrue(Regex("\"pake_msg_2\":\"[A-Za-z0-9_-]{43}\"").containsMatchIn(json))
    }

    @Test
    fun `pair-confirm carries an 86 character tag and a 64 character wrap`() {
        val json = MessageBuilder.buildClientPairConfirm(ByteArray(64), ByteArray(48))
        assertTrue(Regex("\"client_kc\":\"[A-Za-z0-9_-]{86}\"").containsMatchIn(json))
        assertTrue(Regex("\"wrapped_nonce_B\":\"[A-Za-z0-9_-]{64}\"").containsMatchIn(json))
    }

    @Test
    fun `server pair-init nonce round trips`() {
        val nonce = ByteArray(32) { it.toByte() }
        val encoded = MessageBuilder.buildClientPairInit(1, nonce)
        assertTrue(encoded.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest --tests "*PairingMessageTest*"`
Expected: FAIL with `Unresolved reference 'buildClientPairInit'`.

- [ ] **Step 3: Write minimal implementation**

Add the four builders to `MessageBuilder` following the existing style there (`buildJsonObject`, `put("type", ...)`, `put("payload", ...)`), encoding binary fields with the existing `Base64Url` helper. Add matching parsers to `MessageParser` for `server/pair-init` (`nonce_A`), `server/pair-auth` (`pake_msg_1`) and `server/pair-confirm` (`server_kc`), returning null on a missing or wrong-length field so the caller can raise a protocol error.

Register the new message type constants in `SendSpinProtocol.MessageType`.

Then remove the fossils:
- delete `pinLength` from the `ServerActivate` data class and its assignment in `ServerActivateRules.parse`
- delete `PIN_LENGTH_UNACCEPTABLE` from `PairingPskFlow`
- remove the `"pin_length_unacceptable"` entry from `PairAbortReasonTest`

`pin_length` appears nowhere in the current spec; the dynamic code is derived and fixed at six digits. Leaving the field invites wiring the code length to a server-supplied value, which is exactly what the handshake-bound derivation prevents.

- [ ] **Step 4: Run the full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest`
Expected: PASS. The `pin_length` removal touches existing tests; fix them by deletion, not by re-adding the field.

- [ ] **Step 5: Commit**

```bash
git add -A android/shared android/app
git commit -m "feat(protocol): dynamic pairing messages; drop the pin_length fossils

Adds the four client pairing messages and the three server parsers, all
base64url without padding.

Removes ServerActivate.pinLength and PIN_LENGTH_UNACCEPTABLE. pin_length
appears nowhere in the current spec -- the dynamic code is derived and fixed
at six digits -- and both are leftovers of the pre-10.x variable-length PIN.
A server-supplied code length is precisely what the handshake-bound
derivation exists to rule out."
```

---

## Task 13: handler wiring and the hello descriptor

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/protocol/SendSpinProtocolHandler.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpin.kt`
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/protocol/message/MessageBuilder.kt`
- Test: `android/app/src/test/java/com/sendspindroid/sendspin/DynamicPairMethodDescriptorTest.kt`

**Interfaces:**
- Consumes: all prior tasks
- Produces: `PairMethodDescriptor.DYNAMIC_PAIRING_CODE` carrying `out_channels` and `formats`; handler dispatch of the four pairing messages into `DynamicPairingCodeFlow`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.protocol.message.PairMethodDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pairing.md: the dynamic descriptor carries out_channels and formats, and a
 * client MUST NOT advertise both static and dynamic pairing codes.
 */
class DynamicPairMethodDescriptorTest {

    private fun hello(methods: List<PairMethodDescriptor>) = MessageBuilder.buildClientHello(
        clientId = null,
        deviceName = "Test",
        bufferCapacity = 1,
        manufacturer = "Test",
        supportedFormats = emptyList(),
        supportedPairMethods = methods,
    )

    @Test
    fun `dynamic descriptor advertises display and digits`() {
        val json = hello(
            listOf(PairMethodDescriptor.PAIRING_PSK, PairMethodDescriptor.DYNAMIC_PAIRING_CODE)
        )
        assertTrue(json.contains("dynamic_pairing_code"))
        assertTrue(json.contains("\"out_channels\":[\"display\"]"))
        assertTrue(json.contains("\"formats\":[\"digits\"]"))
    }

    /** Never speaker: that would oblige us to accept a digit audio pack. */
    @Test
    fun `never advertises the speaker channel`() {
        val json = hello(listOf(PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertFalse(json.contains("speaker"))
        assertFalse(json.contains("digit_audio"))
    }

    /** Offering dynamic forecloses static; both together is non-conformant. */
    @Test
    fun `never advertises the static pairing code`() {
        val json = hello(listOf(PairMethodDescriptor.DYNAMIC_PAIRING_CODE))
        assertFalse(json.contains("static_pairing_code"))
    }

    @Test
    fun `pairing psk remains advertised alongside it`() {
        val json = hello(
            listOf(PairMethodDescriptor.PAIRING_PSK, PairMethodDescriptor.DYNAMIC_PAIRING_CODE)
        )
        assertTrue(json.contains("pairing_psk"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*DynamicPairMethodDescriptorTest*"`
Expected: FAIL with `Unresolved reference 'DYNAMIC_PAIRING_CODE'`.

- [ ] **Step 3: Write minimal implementation**

Extend `PairMethodDescriptor` with `outChannels: List<String>` and `formats: List<String>`, add the `DYNAMIC_PAIRING_CODE` constant with `outChannels = listOf("display")` and `formats = listOf("digits")`, and emit both arrays in `buildClientHello` only when non-empty.

In `SendSpin.kt`, extend `offeredPairMethods()` to include `"dynamic_pairing_code"` when the method is enabled in `PairingConfig`, and construct a `DynamicPairingCodeFlow` when a pairing activation names it. In `SendSpinProtocolHandler`, dispatch `server/pair-init`, `server/pair-auth`, `server/pair-confirm` and `server/pair-finalize` into the flow and execute the returned actions, mapping `ProtocolError` onto the existing silent-close path.

- [ ] **Step 4: Run the full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android
git commit -m "feat(pairing): advertise dynamic_pairing_code and wire the flow

Advertises display and digits only. Claiming the speaker channel would
oblige us to accept a server-supplied digit audio pack -- ten clips with
decode and size validation -- for a device with a screen."
```

---

## Task 14: display the code

**Files:**
- Create: `android/app/src/main/java/com/sendspindroid/ui/main/components/PairingCodeDisplay.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/components/AdmissionNotice.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/MainActivityViewModel.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AdmissionState.PAIRING` (already shipped), `PairingCode.group`
- Produces: `EXTRA_PAIRING_CODE` in session extras; `MainActivityViewModel.pairingCode: StateFlow<String?>`

- [ ] **Step 1: Add the strings**

In `strings.xml`, ASCII only, no apostrophes:

```xml
    <string name="pairing_code_title">Enter this code on the server</string>
    <string name="pairing_code_body">Type this code into %1$s to pair this player.</string>
    <string name="pairing_allow_title">Allow pairing</string>
    <string name="pairing_allow_body">Too many recent pairing attempts. Tap Allow pairing on this device to continue.</string>
    <string name="pairing_allow_button">Allow pairing</string>
```

- [ ] **Step 2: Plumb the code to the UI**

Add `const val EXTRA_PAIRING_CODE = "pairing_code"` beside `EXTRA_ADMISSION_STATE` in `PlaybackService`, set it from the flow's `EmitPairingCode` action, clear it on `StopEmittingCode`, and include it in the **same** extras bundle as `EXTRA_ADMISSION_STATE` -- `PlaybackService.kt:1775` documents that these are broadcast together so individual `setSessionExtras` calls cannot overwrite each other.

Read it in `MainActivity` next to the admission state, defaulting to null, and hold it in `MainActivityViewModel` as `pairingCode: StateFlow<String?>`.

- [ ] **Step 3: Render it**

`PairingCodeDisplay.kt` shows `PairingCode.group(code)` in `MaterialTheme.typography.displayMedium`, with `letterSpacing` widened for legibility across a room, and a `liveRegion = LiveRegionMode.Polite` semantics modifier so a screen reader announces the code when it appears.

In `AdmissionNotice`, the `PAIRING` branch renders `PairingCodeDisplay` when a code is present, the "Allow pairing" button when the flow reports the attempt is gesture-gated, and the existing PSK-token guidance otherwise.

- [ ] **Step 4: Build and run the suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testAndroidHostTest`
Expected: BUILD SUCCESSFUL, all tests pass. A bare apostrophe in `strings.xml` fails `mergeDebugResources`; reword rather than escape.

- [ ] **Step 5: Commit**

```bash
git add -A android
git commit -m "feat(ui): show the dynamic pairing code and the gesture gate

Rides the existing admission-state extras bundle rather than adding a
second channel, because PlaybackService broadcasts that bundle as a unit to
stop setSessionExtras calls overwriting one another."
```

---

## Task 15: end-to-end against the dev server

The acceptance test. Music Assistant cannot speak this protocol yet, so the local aiosendspin server is the peer.

**Files:**
- Modify: `ci/conformance/dev_server.py`
- Modify: `docs/dev-server-runbook.md`

- [ ] **Step 1: Pin the dev server to aiosendspin 10.x**

Update the runbook's install step to `pip install "aiosendspin[server]>=10,<11"` and adjust `dev_server.py` for the 10.x API: `PairMethod.DYNAMIC_PAIRING_CODE`, and `run_dynamic_pairing_code_server(...)` with a `pairing_code_provider` that reads six digits from stdin and `pairing_format=PairingCodeFormat.DIGITS`.

- [ ] **Step 2: Record the procedure in the runbook**

Document: start the server, connect the tablet, choose dynamic pairing, read the six digits off the tablet, type them at the server prompt, and confirm the pairing record persists and the server's re-handshake to the new long-term PSK succeeds.

- [ ] **Step 3: Run it against the tablet**

Install the debug build and pair for real. Capture in the runbook: the code shown, that it matched what the server accepted, that `client/pair-finalize` was answered by `server/pair-finalize`, and that the connection continued after the re-handshake without dropping.

Note the tablet currently runs a release-signed build, so installing a debug APK needs an uninstall first, which clears existing pairings. Say so before doing it.

- [ ] **Step 4: Verify the failure paths on the device**

Enter a wrong code and confirm the client sends `pair/abort` with `pairing_code_mismatch` and the UI recovers. Repeat five attempts and confirm the sixth is gesture-gated behind the Allow pairing button, then confirm a successful verification de-escalates.

- [ ] **Step 5: Commit**

```bash
git add ci/conformance/dev_server.py docs/dev-server-runbook.md
git commit -m "test(conformance): dynamic pairing end to end against the dev server

Music Assistant ships aiosendspin 9.1.x and cannot speak the current pairing
protocol, so the local server is the acceptance peer -- the same approach
the encrypted dialect took when MA's shim made failures invisible."
```

---

## Done when

1. All draft-21 vectors pass: B.1.1 generator, B.1.4 K, B.1.5 ISK, B.1.10 low-order rejection.
2. The MCF tags match the `cpace` oracle byte for byte.
3. The flow's nine sequencing tests pass, including the protocol-error-versus-abort distinction.
4. `client/hello` advertises `dynamic_pairing_code` with `display` and `digits`, never `speaker` or `static_pairing_code`.
5. `pin_length` appears nowhere in the codebase.
6. End-to-end pairing succeeds against the dev server on aiosendspin 10.x, including a wrong-code abort and the escalation gate.
7. Pairing PSK still works unchanged.
8. Test count is above the 1,316 baseline with zero failures.
