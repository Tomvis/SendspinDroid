package com.sendspindroid.sendspin.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sentinel PSK and its `psk_id` are the only PSK values the spec publishes,
 * which makes them the one place `psk_id` derivation can be checked against an
 * external authority rather than against itself. Both were independently
 * confirmed to match aiosendspin 9.1.0's `psk_id_for(SENTINEL_PSK)`.
 */
class PskIdTest {

    private fun ByteArray.hex(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    @Test
    fun sentinelPskMatchesThePublishedConstant() {
        assertEquals(SentinelPsk.EXPECTED_HEX, SentinelPsk.bytes.hex())
        assertEquals(32, SentinelPsk.bytes.size)
    }

    @Test
    fun sentinelPskIdMatchesThePublishedConstant() {
        // This is the value a real server puts in Noise message 1 before any
        // pairing exists, so a mismatch here means every unpaired connection
        // fails as a lookup miss - and the spec's failure handling makes that a
        // silent close with nothing in any log.
        assertEquals(SentinelPsk.EXPECTED_PSK_ID, PskId.derive(SentinelPsk.bytes))
        assertEquals(SentinelPsk.EXPECTED_PSK_ID, SentinelPsk.psk.pskId)
    }

    @Test
    fun pskIdIs43Base64UrlCharacters() {
        val id = SentinelPsk.psk.pskId
        assertEquals(PskId.LENGTH, id.length)
        assertTrue(id.none { it == '=' }, "psk_id is unpadded")
        assertTrue(id.none { it == '+' || it == '/' }, "psk_id is base64URL, not base64")
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun derivationIsOverLabelThenPskInThatOrder() {
        // Guards against concatenating the other way round, which produces a
        // plausible-looking 43-character id that matches nothing.
        val psk = ByteArray(32) { it.toByte() }
        val expected = Base64Url.encode(
            sha256(PskId.LABEL.encodeToByteArray() + psk)
        )
        assertEquals(expected, PskId.derive(psk))

        val reversed = Base64Url.encode(sha256(psk + PskId.LABEL.encodeToByteArray()))
        assertTrue(reversed != PskId.derive(psk), "order must matter")
    }

    @Test
    fun labelCarriesNoNulTerminatorOrQuotes() {
        assertEquals("sendspin-psk-id-v1", PskId.LABEL)
        assertEquals(18, PskId.LABEL.encodeToByteArray().size)
        assertEquals("sendspin-sentinel-psk-v1", SentinelPsk.SEED_LABEL)
    }

    @Test
    fun differentPsksDeriveDifferentIds() {
        val a = PskId.derive(ByteArray(32) { 1 })
        val b = PskId.derive(ByteArray(32) { 2 })
        assertTrue(a != b)
    }

    @Test
    fun derivationRejectsAWrongLengthPsk() {
        for (size in listOf(0, 31, 33, 64)) {
            assertFailsWith<IllegalArgumentException>("size=$size") {
                PskId.derive(ByteArray(size))
            }
        }
    }
}

class Base64UrlTest {

    @Test
    fun roundTripsRawBytes() {
        val input = ByteArray(32) { (it * 7).toByte() }
        val encoded = Base64Url.encode(input)
        assertContentEquals(input, Base64Url.decodeOrNull(encoded))
    }

    @Test
    fun encodesWithoutPadding() {
        // 32 bytes is not a multiple of 3, so a padded encoder would append '='.
        assertTrue(Base64Url.encode(ByteArray(32)).none { it == '=' })
        assertEquals(43, Base64Url.encode(ByteArray(32)).length)
    }

    @Test
    fun usesTheUrlSafeAlphabet() {
        // 0xfb 0xff produces '+' and '/' under standard base64.
        val encoded = Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte()))
        assertTrue(encoded.none { it == '+' || it == '/' }, "got $encoded")
    }

    @Test
    fun decodeReturnsNullOnGarbageRatherThanThrowing() {
        // These come off the wire from an untrusted peer.
        for (bad in listOf("!!!!", "a b c", "éé")) {
            assertNull(Base64Url.decodeOrNull(bad), "input=$bad")
        }
    }

    @Test
    fun decodeToleratesPaddingIfAPeerSendsIt() {
        val padded = "AAAA===="
        // Either accepted or rejected cleanly - what must not happen is a throw.
        Base64Url.decodeOrNull(padded)
        assertNotNull(Base64Url.decodeOrNull("AAAA"))
    }
}

class PskCandidateSetTest {

    private fun psk(fill: Byte, category: PskCategory, serverId: String? = null) =
        Psk(ByteArray(32) { fill }, category, serverId)

    @Test
    fun sentinelOnlyResolvesThePublishedId() {
        val set = PskCandidateSet.sentinelOnly()
        val matched = set.resolve(SentinelPsk.EXPECTED_PSK_ID, PskCategory.SENTINEL)
        assertNotNull(matched)
        assertEquals(PskCategory.SENTINEL, matched.category)
    }

    @Test
    fun resolveReturnsNullOnAMissRatherThanThrowing() {
        // A miss is the caller's decision to escalate, not this layer's.
        assertNull(PskCandidateSet.sentinelOnly().resolve("nope", PskCategory.SENTINEL))
        assertNull(PskCandidateSet.sentinelOnly().resolve("", PskCategory.SENTINEL))
    }

    @Test
    fun resolveIsBoundToTheDeclaredCategory() {
        // connection.md#pre-shared-key: the client compares psk_id against
        // "each candidate PSK of the declared psk_category". The same bytes
        // held as a record and as the pairing PSK are two candidates, and the
        // category decides which one a handshake matches.
        val record = psk(9, PskCategory.LONG_TERM, "server-a")
        val pairing = psk(9, PskCategory.PAIRING)
        val set = PskCandidateSet(listOf(record, pairing, SentinelPsk.psk))

        assertEquals(PskCategory.LONG_TERM, set.resolve(record.pskId, PskCategory.LONG_TERM)!!.category)
        assertEquals(PskCategory.PAIRING, set.resolve(record.pskId, PskCategory.PAIRING)!!.category)
        assertNull(set.resolve(record.pskId, PskCategory.SENTINEL))
        assertNull(set.resolve(SentinelPsk.EXPECTED_PSK_ID, PskCategory.LONG_TERM))
    }

    @Test
    fun categoryWireCodesAreTheThreeTheSpecDefines() {
        assertEquals("lt", PskCategory.LONG_TERM.wire)
        assertEquals("pr", PskCategory.PAIRING.wire)
        assertEquals("sn", PskCategory.SENTINEL.wire)
        for (category in PskCategory.entries) {
            assertEquals(category, PskCategory.fromWire(category.wire))
        }
        assertNull(PskCategory.fromWire("sentinel"))
        assertNull(PskCategory.fromWire("LT"))
        assertNull(PskCategory.fromWire(""))
    }
}

class PskTest {

    @Test
    fun rejectsAWrongLengthSecret() {
        for (size in listOf(0, 31, 33)) {
            assertFailsWith<IllegalArgumentException>("size=$size") {
                Psk(ByteArray(size), PskCategory.SENTINEL)
            }
        }
    }

    @Test
    fun onlyALongTermRecordMayBindAServerId() {
        Psk(ByteArray(32), PskCategory.LONG_TERM, "server-a")  // fine
        for (category in listOf(PskCategory.SENTINEL, PskCategory.PAIRING)) {
            assertFailsWith<IllegalArgumentException>(category.name) {
                Psk(ByteArray(32), category, "server-a")
            }
        }
    }

    @Test
    fun toStringNeverLeaksTheSecret() {
        // These end up in exception messages and log lines.
        val secret = ByteArray(32) { 0x41 }
        val rendered = Psk(secret, PskCategory.PAIRING).toString()
        assertTrue(rendered.contains("PAIRING"))
        assertTrue(rendered.contains(PskId.derive(secret)))
        assertTrue(!rendered.contains("AAAA"), "must not render the bytes: $rendered")
        assertTrue(!rendered.contains("41414141"), "must not render the bytes: $rendered")
    }

    @Test
    fun bytesAreDefensivelyCopied() {
        val original = ByteArray(32) { 5 }
        val p = Psk(original, PskCategory.SENTINEL)
        original.fill(0)
        assertTrue(p.bytes.all { it == 5.toByte() }, "constructor must copy")
        p.bytes.fill(0)
        assertTrue(p.bytes.all { it == 5.toByte() }, "getter must copy")
    }
}
