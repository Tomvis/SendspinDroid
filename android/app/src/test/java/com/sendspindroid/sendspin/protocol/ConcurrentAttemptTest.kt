package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `connection.md`, "Multiple servers (server-initiated)": "A rejected incoming
 * receives `client/goodbye` reason `'concurrent_attempt'` (or `pair/abort`
 * reason `concurrent_attempt` for pairings). The client then closes the
 * connection."
 *
 * The refused connection is past its Noise handshake but its activation is
 * never applied, so nothing an accepted activation does may happen on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentAttemptTest {

    private val playback = """{"type":"server/activate","payload":{"activities":["playback"],""" +
        """"active_roles":["player@v1"]}}"""
    private val empty = """{"type":"server/activate","payload":{"activities":[],"active_roles":[]}}"""
    private val pairing = """{"type":"server/activate","payload":{"activities":["pairing"],""" +
        """"active_roles":[],"pairing":{"method":"pairing_psk"}}}"""
    private val playbackAndPairing = """{"type":"server/activate","payload":{"activities":""" +
        """["playback","pairing"],"active_roles":["player@v1"],"pairing":{"method":"pairing_psk"}}}"""

    /** A handler whose connection the admission rules answer for. */
    private class GatedHandler(
        matched: PskCategory,
        private val admit: (Set<Activity>) -> Boolean,
    ) : AbortTestHandler(matched) {
        val asked = mutableListOf<Set<Activity>>()
        var admissionStates = 0

        override fun admitActivation(activities: Set<Activity>): Boolean {
            asked += activities
            return admit(activities)
        }

        override fun onAdmissionStateChanged(state: AdmissionState) {
            admissionStates++
        }
    }

    private fun handler(
        matched: PskCategory = PskCategory.PAIRING,
        admit: (Set<Activity>) -> Boolean = { false },
    ) = GatedHandler(matched, admit).apply { setHandshakeCompleteForTest() }

    private fun GatedHandler.receive(message: String) {
        handleTextMessageForTest(message)
        scope.runCurrent()
    }

    private fun String.field(name: String): String? =
        Json.parseToJsonElement(this).jsonObject["payload"]?.jsonObject?.get(name)?.jsonPrimitive?.content

    @Test
    fun `a refused playback connection is told concurrent_attempt and closed`() {
        val handler = handler()

        handler.receive(playback)

        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertEquals("concurrent_attempt", handler.sent.single().field("reason"))
        assertEquals(listOf(setOf(Activity.PLAYBACK)), handler.asked)
    }

    @Test
    fun `a refused connection with empty activities is told concurrent_attempt and closed`() {
        val handler = handler()

        handler.receive(empty)

        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertEquals("concurrent_attempt", handler.sent.single().field("reason"))
    }

    @Test
    fun `a refused pairing connection is told pair abort concurrent_attempt and closed`() {
        val handler = handler()

        handler.receive(pairing)

        // No client/pair-init: the attempt the activation admits never starts.
        assertEquals(listOf("send:pair/abort", "close"), handler.events)
        assertEquals("concurrent_attempt", handler.sent.single().field("reason"))
        assertFalse(handler.pairingAttemptInProgress)
    }

    @Test
    fun `a refused connection that declares playback too is ranked as playback`() {
        val handler = handler()

        handler.receive(playbackAndPairing)

        assertEquals(listOf("send:client/goodbye", "close"), handler.events)
        assertEquals("concurrent_attempt", handler.sent.single().field("reason"))
    }

    @Test
    fun `nothing of a refused activation is applied`() {
        val handler = handler()

        handler.receive(playback)

        // No client/state, no client/time, and nothing for the UI.
        assertEquals(listOf("client/goodbye"), handler.sent.map {
            Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content
        })
        assertEquals(0, handler.admissionStates)
        assertEquals(emptyList<String>(), handler.protocolStats().activeRoles)
    }

    @Test
    fun `an inadmissible activation is not put to the admission rules`() {
        // A long-term key may not declare pairing: unauthorized, whatever
        // the other connections are doing.
        val handler = handler(matched = PskCategory.LONG_TERM)

        handler.receive(pairing)

        assertEquals(emptyList<Set<Activity>>(), handler.asked)
        assertEquals("unauthorized", handler.sent.single().field("reason"))
    }

    @Test
    fun `an admitted connection carries on as before`() {
        val handler = handler(admit = { true })

        handler.receive(playback)

        assertTrue("client/state follows the activation", handler.events.contains("send:client/state"))
        assertFalse(handler.events.contains("close"))
        assertEquals(1, handler.admissionStates)
        assertEquals(listOf("player@v1"), handler.protocolStats().activeRoles)
    }

    @Test
    fun `a pairing attempt is in progress from pair-init until it ends`() {
        val handler = handler(admit = { true })
        assertFalse(handler.pairingAttemptInProgress)

        handler.receive(pairing)
        assertTrue(handler.events.contains("send:client/pair-init"))
        assertTrue(handler.pairingAttemptInProgress)

        handler.receive("""{"type":"pair/abort","payload":{"reason":"user_cancelled"}}""")
        assertFalse(handler.pairingAttemptInProgress)
    }
}
