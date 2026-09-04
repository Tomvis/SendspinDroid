package com.sendspindroid.sendspin.protocol.message

import com.sendspindroid.sendspin.crypto.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    // ---- server parsers ----
    //
    // A malformed frame here comes from an unauthenticated peer, so the
    // contract is strict: return null, never throw. Each case below is run
    // through a wrapper that turns any escaped exception into an explicit,
    // named failure rather than an ambiguous crash.

    /** One table row: a named payload and the bytes [parse] must return, or null. */
    private class Case(val name: String, val payload: JsonObject, val expected: ByteArray?)

    private fun runCases(parse: (JsonObject?) -> ByteArray?, cases: List<Case>) {
        for (case in cases) {
            val actual = try {
                parse(case.payload)
            } catch (e: Throwable) {
                fail("case '${case.name}' threw ${e::class.simpleName}: ${e.message}")
            }
            if (case.expected == null) {
                assertNull(actual, "case '${case.name}' should return null")
            } else {
                assertContentEquals(case.expected, actual, "case '${case.name}' returned unexpected bytes")
            }
        }
    }

    private fun payloadOf(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }

    /** A base64url alphabet violation: '!' never appears in RFC 4648 section 5. */
    private val INVALID_BASE64 = "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"

    @Test
    fun `server pair-init parses nonce_A`() {
        val nonce = ByteArray(32) { it.toByte() }
        runCases(
            MessageParser::parseServerPairInit,
            listOf(
                Case("well-formed", payloadOf("nonce_A", Base64Url.encode(nonce)), nonce),
                Case("missing field", buildJsonObject { put("other", "x") }, null),
                Case("decodes to 31 bytes", payloadOf("nonce_A", Base64Url.encode(ByteArray(31))), null),
                Case("decodes to 33 bytes", payloadOf("nonce_A", Base64Url.encode(ByteArray(33))), null),
                Case("invalid base64", payloadOf("nonce_A", INVALID_BASE64), null),
            ),
        )
    }

    @Test
    fun `server pair-auth parses pake_msg_1`() {
        val ya = ByteArray(32) { (it * 3).toByte() }
        runCases(
            MessageParser::parseServerPairAuth,
            listOf(
                Case("well-formed", payloadOf("pake_msg_1", Base64Url.encode(ya)), ya),
                Case("missing field", buildJsonObject { put("other", "x") }, null),
                Case("decodes to 31 bytes", payloadOf("pake_msg_1", Base64Url.encode(ByteArray(31))), null),
                Case("decodes to 33 bytes", payloadOf("pake_msg_1", Base64Url.encode(ByteArray(33))), null),
                Case("invalid base64", payloadOf("pake_msg_1", INVALID_BASE64), null),
            ),
        )
    }

    @Test
    fun `server pair-confirm parses server_kc`() {
        val ta = ByteArray(64) { (it * 5).toByte() }
        runCases(
            MessageParser::parseServerPairConfirm,
            listOf(
                Case("well-formed", payloadOf("server_kc", Base64Url.encode(ta)), ta),
                Case("missing field", buildJsonObject { put("other", "x") }, null),
                Case("decodes to 63 bytes", payloadOf("server_kc", Base64Url.encode(ByteArray(63))), null),
                Case("decodes to 65 bytes", payloadOf("server_kc", Base64Url.encode(ByteArray(65))), null),
                Case("invalid base64", payloadOf("server_kc", INVALID_BASE64), null),
            ),
        )
    }
}
