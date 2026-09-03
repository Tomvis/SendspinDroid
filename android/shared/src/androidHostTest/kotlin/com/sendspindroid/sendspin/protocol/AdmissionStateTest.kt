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
 * both states present identically if you look only at `active_roles`.
 * `activities` is the field that separates them, which is why the derivation
 * reads it and not the role list.
 */
class AdmissionStateTest {

    @Test
    fun `empty activities means the server is waiting for operator approval`() {
        assertEquals(
            AdmissionState.AWAITING_APPROVAL,
            AdmissionState.from(emptySet())
        )
    }

    @Test
    fun `the pairing activity means pairing, not approval`() {
        assertEquals(
            AdmissionState.PAIRING,
            AdmissionState.from(setOf(Activity.PAIRING))
        )
    }

    @Test
    fun `playback is ready and says nothing to the user`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.PLAYBACK))
        )
    }

    @Test
    fun `playback with management is ready`() {
        assertEquals(
            AdmissionState.READY,
            AdmissionState.from(setOf(Activity.PLAYBACK, Activity.MANAGEMENT))
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
            AdmissionState.from(setOf(Activity.MANAGEMENT))
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
            AdmissionState.from(setOf(Activity.PAIRING, Activity.PLAYBACK))
        )
    }
}
