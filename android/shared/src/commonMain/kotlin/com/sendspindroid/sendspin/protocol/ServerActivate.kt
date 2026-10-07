package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.pairing.PairMethod
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The purposes a server may declare on a connection. */
enum class Activity(val wireName: String) {
    PLAYBACK("playback"),
    PAIRING("pairing");

    companion object {
        fun fromWire(value: String): Activity? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * A parsed `server/activate`.
 *
 * @param activeRoles null means the field was ABSENT, which is different from
 *   present-and-empty: an absent value persists whatever the previous
 *   activation set, while an empty list clears the roles.
 */
data class ServerActivate(
    val activities: Set<Activity>,
    val activeRoles: List<String>?,
    val pairingMethod: String?,
    /** `pairing.format`: the dynamic pairing code's emission format. */
    val pairingFormat: String?,
    /** Activities the client did not recognise; ignored, but worth logging. */
    val unknownActivities: List<String>,
)

/**
 * Why a connection is not usable, as the user needs to hear it.
 *
 * An accepted activation with no roles leaves the player unable to do
 * anything, but `active_roles` alone does not say why: `pairing.md` gives
 * empty roles both to a pairing activation and to a server "hold[ing] the
 * connection at empty activities, ready to activate roles once approved".
 * Those need different instructions - approve the player, versus pair it - so
 * the state is derived from `activities`, which distinguishes them.
 */
enum class AdmissionState {
    /** The connection is doing its job; say nothing. */
    READY,

    /** Held pending operator approval. The operator must approve this client. */
    AWAITING_APPROVAL,

    /** The server moved the connection into pairing. The operator must pair. */
    PAIRING;

    companion object {
        /**
         * Derive the state from an accepted activation.
         *
         * [activeRoles] is checked FIRST, and it is not redundant with
         * [activities]. A playback-capable connection may carry roles "even
         * when 'playback' is not currently in `activities`" - so a server may
         * legitimately send empty `activities` WITH live roles, and pairing
         * "can run alongside playback" without touching them. A derivation
         * reading only `activities` reports AWAITING_APPROVAL or PAIRING for
         * such a session and hides a working player behind a notice.
         *
         * Roles are the direct evidence: if any are live the player can do its
         * job, whatever the activity set says, and there is nothing to explain.
         *
         * PAIRING then takes precedence over the empty check: with no roles,
         * pairing is the state with an operator action attached.
         */
        fun from(activities: Set<Activity>, activeRoles: List<String>): AdmissionState = when {
            activeRoles.isNotEmpty() -> READY
            Activity.PAIRING in activities -> PAIRING
            activities.isEmpty() -> AWAITING_APPROVAL
            else -> READY
        }
    }
}

/** What the client must do about an activation. */
sealed interface ActivationOutcome {
    /** Accept, with the roles that are now live. */
    data class Accept(val activeRoles: List<String>) : ActivationOutcome

    /** Close the connection with this `client/goodbye` reason, sending nothing else. */
    data class Close(val goodbyeReason: String) : ActivationOutcome

    /** Reply `pair/abort` with this reason; the connection stays open. */
    data class AbortPairing(val reason: String) : ActivationOutcome
}

/**
 * Parsing and admissibility for `server/activate`.
 *
 * The admissibility table (`messaging.md#server--client-serveractivate`) binds
 * what a server may declare to which PSK admitted the connection:
 *
 * | PSK matched   | Allowed activity sets                                          |
 * |---------------|----------------------------------------------------------------|
 * | long-term PSK | `[]` or `['playback']`                                         |
 * | pairing PSK   | `[]`, `['pairing']`, `['playback']`*, `['playback','pairing']`* |
 * | Sentinel PSK  | `[]`, `['pairing']`, `['playback']`*, `['playback','pairing']`* |
 *
 * \* Only when the client has unpaired access enabled - which is why
 * advertising `unpaired_access` in `client/hello` is what makes unpaired
 * playback possible at all.
 */
object ServerActivateRules {

    const val GOODBYE_UNAUTHORIZED = "unauthorized"
    const val GOODBYE_PAIRING_REQUIRED = "pairing_required"
    const val ABORT_METHOD_NOT_SUPPORTED = "method_not_supported"

    fun parse(payload: JsonObject?): ServerActivate? {
        if (payload == null) return null
        val rawActivities = payload["activities"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return null   // activities is required
        val known = mutableSetOf<Activity>()
        val unknown = mutableListOf<String>()
        for (raw in rawActivities) {
            val activity = Activity.fromWire(raw)
            if (activity != null) known += activity else unknown += raw
        }
        // Absent stays null; present-but-empty becomes an empty list.
        val roles = payload["active_roles"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val pairing = payload["pairing"] as? JsonObject
        return ServerActivate(
            activities = known,
            activeRoles = roles,
            // The client ignores `pairing` unless 'pairing' is in activities.
            pairingMethod = pairing?.get("method")?.jsonPrimitive?.contentOrNull,
            pairingFormat = pairing?.get("format")?.jsonPrimitive?.contentOrNull,
            unknownActivities = unknown,
        )
    }

    /** Is [activities] a set this PSK category may declare? */
    fun activitiesAllowed(
        category: PskCategory,
        activities: Set<Activity>,
        unpairedAccessEnabled: Boolean,
    ): Boolean = when (category) {
        // A paired session never pairs: the server re-handshakes to the
        // Sentinel or the pairing PSK first.
        PskCategory.LONG_TERM -> Activity.PAIRING !in activities

        // Unpaired sessions may always idle or pair; playback is the footnote.
        PskCategory.PAIRING, PskCategory.SENTINEL ->
            Activity.PLAYBACK !in activities || unpairedAccessEnabled
    }

    /**
     * "A connection is playback-capable when its activities extended with
     * 'playback' are an allowed set for the matched PSK."
     *
     * Only a playback-capable connection may carry non-empty `active_roles`,
     * and it may do so even when 'playback' is not currently declared.
     */
    fun playbackCapable(
        category: PskCategory,
        activities: Set<Activity>,
        unpairedAccessEnabled: Boolean,
    ): Boolean = activitiesAllowed(
        category, activities + Activity.PLAYBACK, unpairedAccessEnabled
    )

    /**
     * Decide what to do with an activation.
     *
     * "When one is not admissible, the client rejects it, selecting the
     * response by the first rule that applies", and the three rules below are
     * the spec's three, in its order.
     *
     * @param previousRoles roles persisted from an earlier activation, used when
     *   [activate] omits the field.
     * @param offeredPairMethods the LIVE pairing configuration, which may have
     *   drifted from what `client/hello` advertised.
     */
    fun evaluate(
        activate: ServerActivate,
        category: PskCategory,
        unpairedAccessEnabled: Boolean,
        previousRoles: List<String>,
        isFirstActivation: Boolean,
        offeredPairMethods: Set<String>,
    ): ActivationOutcome {
        val activities = activate.activities

        // The roles this activation leaves live, under a given unpaired-access
        // setting. "A client treats a first server/activate that omits it as
        // carrying an empty active_roles"; later omissions persist the previous
        // value - except that persisted roles on a connection that is no longer
        // playback-capable "are treated as empty rather than the message
        // rejected".
        fun rolesUnder(unpairedAccess: Boolean): List<String> = activate.activeRoles
            ?: if (!isFirstActivation && playbackCapable(category, activities, unpairedAccess)) {
                previousRoles
            } else {
                emptyList()
            }

        fun activitiesAndRolesOk(unpairedAccess: Boolean): Boolean =
            activitiesAllowed(category, activities, unpairedAccess) &&
                (rolesUnder(unpairedAccess).isEmpty() ||
                    playbackCapable(category, activities, unpairedAccess))

        // "pairing.method MUST be 'pairing_psk' if and only if the matched PSK
        // is the pairing PSK, and MUST be a method present in the client's
        // supported_pair_methods." `pairing.format` is "required when method
        // is 'dynamic_pairing_code'" and must be a format the client offers.
        val pairingOk = Activity.PAIRING !in activities || activate.pairingMethod.let { method ->
            method != null &&
                (method == PairMethod.PAIRING_PSK) == (category == PskCategory.PAIRING) &&
                method in offeredPairMethods &&
                (method != PairMethod.DYNAMIC_PAIRING_CODE ||
                    activate.pairingFormat in MessageBuilder.PairMethodDescriptor.DYNAMIC_PAIRING_CODE.formats)
        }

        if (activitiesAndRolesOk(unpairedAccessEnabled) && pairingOk) {
            return ActivationOutcome.Accept(rolesUnder(unpairedAccessEnabled))
        }

        // Rule 1: the session is unpaired, unpaired access is off, and turning
        // it on would make this very activation admissible. The server is
        // asking for something we could grant but have switched off, and the
        // honest answer is "pair with me first" rather than a flat
        // unauthorized. Applies to every unpaired session, pairing PSK and
        // Sentinel alike.
        if (category != PskCategory.LONG_TERM && !unpairedAccessEnabled &&
            activitiesAndRolesOk(unpairedAccess = true) && pairingOk
        ) {
            return ActivationOutcome.Close(GOODBYE_PAIRING_REQUIRED)
        }

        // Rule 2: an activity set this PSK may not declare, or roles on a
        // connection that is not playback-capable.
        if (!activitiesAndRolesOk(unpairedAccessEnabled)) {
            return ActivationOutcome.Close(GOODBYE_UNAUTHORIZED)
        }

        // Rule 3: a pairing method the matched PSK disallows or we do not
        // currently offer, or an emission format we do not offer. The
        // connection stays open - the server may
        // re-activate with a different method.
        return ActivationOutcome.AbortPairing(ABORT_METHOD_NOT_SUPPORTED)
    }
}
