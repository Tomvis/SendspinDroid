package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.Base64Url
import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.crypto.PskId
import com.sendspindroid.sendspin.crypto.SentinelPsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test

/**
 * The re-handshake as the connection drives it: decode, select, reply.
 *
 * Every failure here closes the socket with no application-level message
 * (`connection.md#failure-handling`), so each one has to be distinguishable in
 * a log or it is undiagnosable in the field.
 *
 * Vectors from `ci/conformance/noise/make_rehandshake_vectors.py`, cross-checked
 * against `noiseprotocol`.
 */
class RehandshakeDriverTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val clientStatic = hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")
    private val serverStaticPublic = hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")
    private val priorH = hex("1f1486e42c483083db088f6c6c7246b509245554f2a2cf941c5dea2de9fe25cb")

    /** The PSK the server is promoting the channel to. */
    private val newPsk = hex("b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0")

    /** Message 1 referencing [newPsk] as a long-term PSK (`psk_category: lt`). */
    private val message1B64 = Base64Url.encode(
        hex(
            "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c" +
                "dd9e7b6b8fa1b6c5c8cc1ef96a3059b5cff80039bba7a44cee3f0aba6535fe54" +
                "5d1152fbbf44c65ed703e61a0705a2d583167bbb654a0593f6514c69f1a8585a" +
                "bd67c1d63e048ea10710f4ec526a5961849b6a7372e470f13e94cb5711ab36"
        )
    )

    /** The same promotion referencing [newPsk] as the pairing PSK (`psk_category: pr`). */
    private val message1PairingB64 = Base64Url.encode(
        hex(
            "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c" +
                "dd9e7b6b8fa1b6c5c8cc1ef96a3059b5cff80039bba7a44cee3f0aba6535fe54" +
                "5d1152fbbf44c65ed703e61a0705a2d583167bbb654a0593f6514c69f1a8585a" +
                "bd67c1d63e048ea10710f4f0546a59b94c739c0ea977027bc1ad95db21751a"
        )
    )

    private val serverId = "NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ"

    private fun identity() = ClientIdentity.fromStoredKey(Base64Url.encode(clientStatic))!!

    private fun driver(
        candidates: List<Psk>,
        prologue: ByteArray = priorH,
        withServerId: String = serverId,
    ) = RehandshakeDriver(
        identity = identity(),
        candidates = PskCandidateSet(candidates),
        serverId = withServerId,
        serverStaticKey = serverStaticPublic,
        suite = NoiseCipherSuite.CHACHA_POLY,
        priorHandshakeHash = prologue,
    )

    /** The PSK being promoted to, as an unbound (Pairing-category) candidate. */
    private fun pairingCandidate() = Psk(newPsk, PskCategory.PAIRING)

    /** The same PSK as a long-term record bound to [boundTo]. */
    private fun recordCandidate(boundTo: String) =
        Psk(newPsk, PskCategory.LONG_TERM, boundTo)

    @Test
    fun aValidRehandshakeProducesASignedReplyAndTheNewTransport() {
        val outcome = driver(listOf(SentinelPsk.psk, pairingCandidate())).handle(message1PairingB64)
        assertTrue("expected a Reply, got $outcome", outcome is RehandshakeDriver.Outcome.Reply)
        outcome as RehandshakeDriver.Outcome.Reply

        assertEquals(PskCategory.PAIRING, outcome.matched.category)
        assertEquals(PskId.derive(newPsk), outcome.matched.pskId)

        val json = Json.parseToJsonElement(outcome.replyJson).jsonObject
        assertEquals("noise/handshake", json["type"]?.jsonPrimitive?.contentOrNull)
        val data = json["payload"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        assertNotNull("reply must carry base64url data", data)
        // 32-byte ephemeral public + AEAD({}) + 16-byte tag.
        assertEquals(32 + 2 + 16, Base64Url.decodeOrNull(data!!)!!.size)

        // The promotion target, not the old session.
        assertFalse(outcome.transport.handshakeHash.contentEquals(priorH))
    }

    @Test
    fun promotingToALongTermRecordMatchesTheRecord() {
        // The pairing case: the record was persisted moments ago and must be
        // visible to this selection.
        val outcome = driver(listOf(SentinelPsk.psk, recordCandidate(serverId))).handle(message1B64)
        assertTrue(outcome is RehandshakeDriver.Outcome.Reply)
        assertEquals(
            PskCategory.LONG_TERM,
            (outcome as RehandshakeDriver.Outcome.Reply).matched.category,
        )
    }

    @Test
    fun aRecordBoundToADifferentServerFails() {
        val outcome = driver(
            listOf(SentinelPsk.psk, recordCandidate("some-other-server-id")),
        ).handle(message1B64)
        assertTrue(outcome is RehandshakeDriver.Outcome.Fail)
        assertTrue(
            "the reason must name the binding, got: ${(outcome as RehandshakeDriver.Outcome.Fail).reason}",
            outcome.reason.contains("record for"),
        )
    }

    @Test
    fun aPskIdMatchingNothingFails() {
        // "The fallback applies only there: a miss during a re-handshake ...
        // fail[s] the handshake as before."
        val outcome = driver(listOf(SentinelPsk.psk)).handle(message1B64)
        assertTrue(outcome is RehandshakeDriver.Outcome.Fail)
        assertTrue((outcome as RehandshakeDriver.Outcome.Fail).reason.contains("matches no"))
    }

    @Test
    fun aPskHeldUnderAnotherCategoryIsAMiss() {
        // The lookup is bound to psk_category. The client holds this PSK, but
        // as its pairing PSK; a server naming it as a long-term record - or the
        // reverse - must not match.
        val asLongTerm = driver(listOf(SentinelPsk.psk, pairingCandidate())).handle(message1B64)
        assertTrue("got $asLongTerm", asLongTerm is RehandshakeDriver.Outcome.Fail)

        val asPairing = driver(listOf(SentinelPsk.psk, recordCandidate(serverId)))
            .handle(message1PairingB64)
        assertTrue("got $asPairing", asPairing is RehandshakeDriver.Outcome.Fail)
    }

    @Test
    fun theWrongPrologueFailsBeforeSelectionEverRuns() {
        // The failure this whole item is exposed to. It surfaces as an AEAD
        // rejection of message 1, naming nothing about prologues.
        val outcome = driver(
            listOf(SentinelPsk.psk, pairingCandidate()),
            prologue = ByteArray(32),
        ).handle(message1PairingB64)
        assertTrue(outcome is RehandshakeDriver.Outcome.Fail)
        assertTrue(
            (outcome as RehandshakeDriver.Outcome.Fail).reason.contains("message 1 rejected"),
        )
    }

    @Test
    fun malformedInputFailsRatherThanThrowing() {
        val candidates = listOf(SentinelPsk.psk, pairingCandidate())
        assertTrue(driver(candidates).handle(null) is RehandshakeDriver.Outcome.Fail)
        assertTrue(driver(candidates).handle("not base64!!") is RehandshakeDriver.Outcome.Fail)
        assertTrue(
            driver(candidates).handle(Base64Url.encode(ByteArray(4)))
                is RehandshakeDriver.Outcome.Fail
        )
    }
}
