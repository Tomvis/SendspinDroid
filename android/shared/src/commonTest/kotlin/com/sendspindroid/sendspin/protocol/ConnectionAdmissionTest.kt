package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.protocol.ConnectionAdmission.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `connection.md`, "Multiple servers (server-initiated)".
 *
 * The table test walks every combination of what the holder declares, what
 * the incoming connection declares, whether the holder is mid pairing attempt
 * and which of the two is the last-playback server, and checks each against
 * the spec's sentences written out as [expectAdmitted].
 */
class ConnectionAdmissionTest {

    private val playback = Activity.PLAYBACK
    private val pairing = Activity.PAIRING

    private val activitySets = listOf(
        emptySet(),
        setOf(pairing),
        setOf(playback),
        setOf(playback, pairing),
    )

    /** Which of the two servers the persisted last-playback id names. */
    private enum class LastPlayback { NEITHER, HOLDER, INCOMING, BOTH }

    private fun rank(activities: Set<Activity>) = when {
        playback in activities -> 2
        pairing in activities -> 1
        else -> 0
    }

    private fun expectAdmitted(
        holder: Set<Activity>,
        incoming: Set<Activity>,
        pairingInProgress: Boolean,
        last: LastPlayback,
    ): Boolean {
        // "A pairing attempt is not displaced by an incoming 'playback' or
        // 'pairing' connection."
        if (pairingInProgress && rank(incoming) > 0) return false
        // "When both the current holder and the incoming connection have
        // empty activities, the incoming is admitted only if its server_id
        // matches the last-playback server (and the existing one's does not)."
        if (rank(holder) == 0 && rank(incoming) == 0) return last == LastPlayback.INCOMING
        // "higher or equal is accepted, lower is rejected."
        return rank(incoming) >= rank(holder)
    }

    @Test
    fun everyCombinationOfHolderAndIncoming() {
        var cases = 0
        for (holder in activitySets) for (incoming in activitySets)
            for (pairingInProgress in listOf(false, true)) for (last in LastPlayback.entries) {
                // A pairing attempt needs a connection that declares pairing.
                if (pairingInProgress && pairing !in holder) continue
                cases++

                val holderId = "holder-id"
                val incomingId = if (last == LastPlayback.BOTH) holderId else "incoming-id"
                val lastId = when (last) {
                    LastPlayback.NEITHER -> "some-other-id"
                    LastPlayback.HOLDER, LastPlayback.BOTH -> holderId
                    LastPlayback.INCOMING -> incomingId
                }
                val admission = ConnectionAdmission<String>(lastPlaybackServerId = lastId)
                admission.onConnected("A", nowMs = 0)
                assertEquals(Decision.Admit<String>(null), admission.onActivation("A", holderId, holder))
                admission.onConnected("B", nowMs = 0)

                val decision = admission.onActivation("B", incomingId, incoming, pairingInProgress)

                val case = "holder=$holder incoming=$incoming pairingInProgress=$pairingInProgress last=$last"
                if (expectAdmitted(holder, incoming, pairingInProgress, last)) {
                    assertEquals(Decision.Admit("A"), decision, case)
                    assertEquals("B", admission.admitted, case)
                } else {
                    assertEquals(Decision.Reject, decision, case)
                    assertEquals("A", admission.admitted, case)
                }
                assertEquals(0, admission.provisionalCount, case)
            }
        // 4 holders x 4 incoming x 4 last-playback, twice over for the two
        // holders that can be mid pairing attempt.
        assertEquals(4 * 4 * 4 + 2 * 4 * 4, cases)
    }

    @Test
    fun theFirstConnectionIsAdmittedWhateverItDeclares() {
        for (activities in activitySets) {
            val admission = ConnectionAdmission<String>()
            admission.onConnected("A", 0)
            assertEquals(Decision.Admit<String>(null), admission.onActivation("A", "a", activities))
            assertEquals("A", admission.admitted)
        }
    }

    @Test
    fun aLaterActivationDoesNotReArbitrate() {
        val admission = ConnectionAdmission<String>()
        admission.onConnected("A", 0)
        admission.onActivation("A", "a", setOf(playback))
        admission.onConnected("B", 0)
        assertEquals(Decision.Reject, admission.onActivation("B", "b", emptySet()))

        // The holder drops to empty activities and a lower one would now win
        // a fresh comparison; then it escalates again. Neither is arbitrated.
        assertEquals(Decision.Admit<String>(null), admission.onActivation("A", "a", emptySet()))
        assertEquals(Decision.Admit<String>(null), admission.onActivation("A", "a", setOf(playback, pairing)))
        assertEquals("A", admission.admitted)
    }

    @Test
    fun theHolderIsRankedByWhatItDeclaresNow() {
        val admission = ConnectionAdmission<String>()
        admission.onConnected("A", 0)
        admission.onActivation("A", "a", emptySet())
        admission.onActivation("A", "a", setOf(playback))

        admission.onConnected("B", 0)
        assertEquals(Decision.Reject, admission.onActivation("B", "b", setOf(pairing)))
    }

    @Test
    fun anActivationOnAConnectionThatWasDroppedIsRejected() {
        val admission = ConnectionAdmission<String>()
        assertEquals(Decision.Reject, admission.onActivation("ghost", "g", setOf(playback)))
        assertNull(admission.admitted)
    }

    @Test
    fun aClosedHolderLeavesTheSlotFree() {
        val admission = ConnectionAdmission<String>()
        admission.onConnected("A", 0)
        admission.onActivation("A", "a", setOf(playback))
        admission.onClosed("A")
        assertNull(admission.admitted)

        admission.onConnected("B", 0)
        assertEquals(Decision.Admit<String>(null), admission.onActivation("B", "b", emptySet()))
    }

    // ---- last-playback server ----

    @Test
    fun theLastPlaybackServerIsTheOneThatHeldTheConnectionWithPlayback() {
        val stored = mutableListOf<String>()
        val admission = ConnectionAdmission<String>(onLastPlaybackServerChanged = { stored += it })

        admission.onConnected("A", 0)
        admission.onActivation("A", "a", emptySet())
        assertNull(admission.lastPlaybackServerId)

        // Escalating to playback on the admitted connection counts.
        admission.onActivation("A", "a", setOf(playback))
        assertEquals("a", admission.lastPlaybackServerId)

        // A rejected connection declaring playback never held it.
        admission.onActivation("A", "a", setOf(playback, pairing))
        admission.onConnected("B", 0)
        assertEquals(Decision.Reject, admission.onActivation("B", "b", setOf(playback), holderPairingInProgress = true))
        assertEquals("a", admission.lastPlaybackServerId)

        admission.onConnected("C", 0)
        assertEquals(Decision.Admit("A"), admission.onActivation("C", "c", setOf(playback)))
        assertEquals("c", admission.lastPlaybackServerId)

        // Stored once per change, not once per activation.
        assertEquals(listOf("a", "c"), stored)
    }

    // ---- provisional connections ----

    @Test
    fun aProvisionalConnectionIsDroppedAfterThirtySeconds() {
        val admission = ConnectionAdmission<String>()
        admission.onConnected("A", nowMs = 1_000)

        assertEquals(emptyList(), admission.expired(nowMs = 30_000))
        assertEquals(listOf("A"), admission.expired(nowMs = 32_000))
        assertEquals(0, admission.provisionalCount)
        // Dropped means dropped: it cannot be admitted afterwards.
        assertEquals(Decision.Reject, admission.onActivation("A", "a", setOf(playback)))
    }

    @Test
    fun aConnectionThatActivatesInTimeIsNotDropped() {
        val admission = ConnectionAdmission<String>()
        admission.onConnected("A", nowMs = 0)
        admission.onConnected("B", nowMs = 5_000)
        assertEquals(Decision.Admit<String>(null), admission.onActivation("A", "a", emptySet()))

        // The admitted connection has no deadline; the other one still does.
        assertEquals(emptyList(), admission.expired(nowMs = 34_999))
        assertEquals(listOf("B"), admission.expired(nowMs = 35_000))
        assertEquals("A", admission.admitted)
    }

    @Test
    fun provisionalConnectionsAreCapped() {
        val admission = ConnectionAdmission<Int>()
        repeat(ConnectionAdmission.MAX_PROVISIONAL) { assertTrue(admission.onConnected(it, 0)) }
        assertFalse(admission.onConnected(99, 0))
        assertEquals(ConnectionAdmission.MAX_PROVISIONAL, admission.provisionalCount)
        // Refused at the door, so it is not waiting for an activation either.
        assertEquals(Decision.Reject, admission.onActivation(99, "s", setOf(playback)))

        // One leaving makes room. The admitted connection does not count.
        admission.onActivation(0, "s0", setOf(playback))
        assertTrue(admission.onConnected(100, 0))
        admission.onClosed(1)
        assertTrue(admission.onConnected(101, 0))
        assertFalse(admission.onConnected(102, 0))
    }
}
