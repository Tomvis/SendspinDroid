package com.sendspindroid.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user-facing projection of an accepted `server/activate`.
 *
 * A player with no roles cannot do anything, but "no roles" alone does not say
 * WHY, and the two reasons need different instructions to the operator:
 *
 * | activities     | meaning                                    | what the user must do |
 * |----------------|--------------------------------------------|-----------------------|
 * | `[]`           | held pending operator approval             | approve the player   |
 * | `['pairing']`  | server moved the connection into pairing   | pair the player      |
 * | anything else  | the connection is doing its job            | nothing              |
 *
 * `pairing.md#unpaired-access`: "The server MAY hold the connection at empty
 * activities, ready to activate roles once approved, or to enter pairing." A
 * pairing activation on a connection that was never granted roles has none
 * either - so both blocked states present identically if you look only at
 * `active_roles`, and `activities` is the field that separates them.
 *
 * The role list still comes first, though: an accepted activation may carry
 * live roles WITH an empty activity set, so roles are what prove the player
 * works, and `activities` only explains a player that does not.
 */
class AdmissionStateTest {

    @Test
    fun `empty activities means the server is waiting for operator approval`() {
        assertEquals(
            AdmissionState.AWAITING_APPROVAL,
            AdmissionState.from(emptySet(), emptyList())
        )
    }

    @Test
    fun `the pairing activity means pairing, not approval`() {
        assertEquals(
            AdmissionState.PAIRING,
            AdmissionState.from(setOf(Activity.PAIRING), emptyList())
        )
    }

    @Test
    fun `playback is ready and says nothing to the user`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.PLAYBACK), emptyList())
        )
    }

    /**
     * Pairing "can run alongside playback", so this set is legal on an unpaired
     * session. With no roles the player still cannot do anything, and pairing
     * is the state with an action attached, so it wins over READY.
     */
    @Test
    fun `pairing wins over playback while no roles are live`() {
        assertEquals(
            AdmissionState.PAIRING,
            AdmissionState.from(setOf(Activity.PAIRING, Activity.PLAYBACK), emptyList())
        )
    }

    /**
     * The regression this guard exists for.
     *
     * `activitiesAllowed` admits the empty activity set on every PSK, and
     * `playbackCapable` admits roles alongside it, so a server may send
     * empty `activities` with live roles and be accepted. Reading only
     * `activities` would call that AWAITING_APPROVAL and replace a working
     * player with a notice telling the user to approve an approved device.
     */
    @Test
    fun `live roles win over an empty activity set`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(emptySet(), listOf("player@v1"))
        )
    }

    /** Roles are the evidence the player works, whatever else is declared. */
    @Test
    fun `live roles win over a pairing activity`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.PAIRING), listOf("player@v1"))
        )
    }
}
