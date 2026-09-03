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
 * activities, ready to activate roles once approved, or to enter pairing." And
 * a pairing activation is "`server/activate` with empty `active_roles`" - so
 * both blocked states present identically if you look only at `active_roles`,
 * and `activities` is the field that separates them.
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

    @Test
    fun `playback with management is ready`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.PLAYBACK, Activity.MANAGEMENT), emptyList())
        )
    }

    /**
     * A management-only connection is doing legitimate work and is not a
     * pairing or approval problem, so it must not tell the user to go pair.
     */
    @Test
    fun `management alone is ready`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.MANAGEMENT), emptyList())
        )
    }

    /**
     * `activitiesAllowed` never admits pairing mixed with another activity, so
     * this set cannot arrive from a conforming server. Pinning the precedence
     * anyway keeps the function total: pairing is the state with an action
     * attached, so it wins rather than being masked as READY.
     */
    @Test
    fun `pairing wins if a server somehow mixes it with playback`() {
        assertEquals(
            AdmissionState.PAIRING,
            AdmissionState.from(setOf(Activity.PAIRING, Activity.PLAYBACK), emptyList())
        )
    }

    /**
     * The regression this guard exists for.
     *
     * `activitiesAllowed` admits the empty activity set on a long-term PSK,
     * and `playbackCapable` admits roles alongside it, so a server may send
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
