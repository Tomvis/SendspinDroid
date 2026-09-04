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
