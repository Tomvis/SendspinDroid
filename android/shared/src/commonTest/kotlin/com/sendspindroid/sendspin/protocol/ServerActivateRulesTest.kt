package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.PskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The `server/activate` admissibility table, as of Sendspin 1.0.0-rc1:
 *
 * | PSK matched   | Allowed activity sets                                          |
 * |---------------|----------------------------------------------------------------|
 * | long-term PSK | `[]` or `['playback']`                                         |
 * | pairing PSK   | `[]`, `['pairing']`, `['playback']`*, `['playback','pairing']`* |
 * | Sentinel PSK  | `[]`, `['pairing']`, `['playback']`*, `['playback','pairing']`* |
 *
 * \* Only when the client has unpaired access enabled.
 *
 * These rules decide whether an unauthenticated server gets to drive this
 * device's audio, so the negative cases matter more than the positive ones.
 */
class ServerActivateRulesTest {

    private val playback = Activity.PLAYBACK
    private val pairing = Activity.PAIRING

    private val unauthorized = ActivationOutcome.Close(ServerActivateRules.GOODBYE_UNAUTHORIZED)
    private val pairingRequired = ActivationOutcome.Close(ServerActivateRules.GOODBYE_PAIRING_REQUIRED)
    private val methodNotSupported =
        ActivationOutcome.AbortPairing(ServerActivateRules.ABORT_METHOD_NOT_SUPPORTED)

    private val roles = listOf("player@v1")

    private fun parse(json: String): ServerActivate? =
        ServerActivateRules.parse(
            Json.parseToJsonElement(json).jsonObject["payload"]?.jsonObject
        )

    private fun activate(
        vararg activities: Activity,
        roles: List<String>? = null,
        method: String? = null,
    ) = ServerActivate(activities.toSet(), roles, method, emptyList())

    private fun evaluate(
        activate: ServerActivate,
        category: PskCategory = PskCategory.SENTINEL,
        unpairedAccess: Boolean = true,
        previousRoles: List<String> = emptyList(),
        first: Boolean = true,
        methods: Set<String> = setOf("pairing_psk", "dynamic_pairing_code"),
    ) = ServerActivateRules.evaluate(
        activate, category, unpairedAccess, previousRoles, first, methods
    )

    /** The method a conforming server picks for a pairing activation on [category]. */
    private fun methodFor(category: PskCategory) =
        if (category == PskCategory.PAIRING) "pairing_psk" else "dynamic_pairing_code"

    // ---- the table, row by row ----

    @Test
    fun theAllowedActivitySetsAreExactlyTheTable() {
        val none = emptySet<Activity>()
        val all = listOf(none, setOf(pairing), setOf(playback), setOf(playback, pairing))

        // allowed[category][unpairedAccess] -> the sets the table lists.
        val expected = mapOf(
            (PskCategory.LONG_TERM to true) to setOf(none, setOf(playback)),
            (PskCategory.LONG_TERM to false) to setOf(none, setOf(playback)),
            (PskCategory.PAIRING to true) to all.toSet(),
            (PskCategory.PAIRING to false) to setOf(none, setOf(pairing)),
            (PskCategory.SENTINEL to true) to all.toSet(),
            (PskCategory.SENTINEL to false) to setOf(none, setOf(pairing)),
        )
        for ((key, allowed) in expected) {
            val (category, unpairedAccess) = key
            for (activities in all) {
                assertEquals(
                    activities in allowed,
                    ServerActivateRules.activitiesAllowed(category, activities, unpairedAccess),
                    "$category unpaired_access=$unpairedAccess activities=$activities",
                )
            }
        }
    }

    @Test
    fun aLongTermSessionAcceptsEmptyAndPlaybackAndNothingWithPairing() {
        val cat = PskCategory.LONG_TERM
        assertEquals(ActivationOutcome.Accept(emptyList()), evaluate(activate(), category = cat))
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(activate(playback, roles = roles), category = cat),
        )
        // Unpaired access is irrelevant to a paired session.
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(activate(playback, roles = roles), category = cat, unpairedAccess = false),
        )
        // "long-term PSK: [] or ['playback']". A paired session never pairs,
        // whatever method is named, and no setting of ours would change that.
        for (method in listOf("pairing_psk", "dynamic_pairing_code")) {
            for (unpairedAccess in listOf(true, false)) {
                assertEquals(
                    unauthorized,
                    evaluate(activate(pairing, method = method), category = cat, unpairedAccess = unpairedAccess),
                )
                assertEquals(
                    unauthorized,
                    evaluate(
                        activate(playback, pairing, roles = roles, method = method),
                        category = cat, unpairedAccess = unpairedAccess,
                    ),
                )
            }
        }
    }

    @Test
    fun unpairedSessionsWithUnpairedAccessAcceptAllFourSets() {
        for (cat in listOf(PskCategory.PAIRING, PskCategory.SENTINEL)) {
            val method = methodFor(cat)
            assertEquals(ActivationOutcome.Accept(emptyList()), evaluate(activate(), category = cat), "$cat []")
            assertEquals(
                ActivationOutcome.Accept(emptyList()),
                evaluate(activate(pairing, method = method), category = cat),
                "$cat [pairing]",
            )
            assertEquals(
                ActivationOutcome.Accept(roles),
                evaluate(activate(playback, roles = roles), category = cat),
                "$cat [playback]",
            )
            // Pairing runs alongside playback and keeps the roles.
            assertEquals(
                ActivationOutcome.Accept(roles),
                evaluate(activate(playback, pairing, roles = roles, method = method), category = cat),
                "$cat [playback, pairing]",
            )
        }
    }

    @Test
    fun unpairedSessionsWithoutUnpairedAccessStillAcceptEmptyAndPairing() {
        for (cat in listOf(PskCategory.PAIRING, PskCategory.SENTINEL)) {
            assertEquals(
                ActivationOutcome.Accept(emptyList()),
                evaluate(activate(), category = cat, unpairedAccess = false),
                "$cat []",
            )
            assertEquals(
                ActivationOutcome.Accept(emptyList()),
                evaluate(activate(pairing, method = methodFor(cat)), category = cat, unpairedAccess = false),
                "$cat [pairing]",
            )
        }
    }

    // ---- pairing_required versus unauthorized ----

    @Test
    fun theSpecsWorkedExampleAsksForPairing() {
        // "A Sentinel-keyed connection to a client with unpaired access
        // disabled receives activities: ['playback'] and active_roles:
        // ['player@v1']. Under a hypothetical unpaired_access: enabled ... the
        // activation would be admissible: the client closes with
        // 'pairing_required'."
        assertEquals(
            pairingRequired,
            evaluate(activate(playback, roles = roles), unpairedAccess = false),
        )
    }

    @Test
    fun pairingRequiredAppliesToEveryUnpairedSessionNotOnlyTheSentinel() {
        // "If the session is unpaired" - a pairing-PSK session is unpaired too.
        for (cat in listOf(PskCategory.PAIRING, PskCategory.SENTINEL)) {
            val method = methodFor(cat)
            // Each of these is admissible the moment unpaired access is on.
            val needUnpairedAccess = listOf(
                activate(playback),
                activate(playback, roles = roles),
                activate(playback, pairing, method = method),
                activate(playback, pairing, roles = roles, method = method),
                // Roles with no playback declared: only a playback-capable
                // connection may carry them, and this one would be.
                activate(roles = roles),
                activate(pairing, roles = roles, method = method),
            )
            for (activation in needUnpairedAccess) {
                assertEquals(
                    pairingRequired,
                    evaluate(activation, category = cat, unpairedAccess = false),
                    "$cat $activation",
                )
                // And with it on, the same activation is accepted.
                assertEquals(
                    ActivationOutcome.Accept(activation.activeRoles ?: emptyList()),
                    evaluate(activation, category = cat, unpairedAccess = true),
                    "$cat $activation",
                )
            }
        }
    }

    @Test
    fun enablingUnpairedAccessMustMakeTheWholeActivationAdmissible() {
        // Rule 1 asks whether enabling unpaired access "would make the
        // activation admissible". With a pairing method that is wrong for the
        // PSK it would not, so the answer is the next rule that applies:
        // 'unauthorized' for the activity set we may not accept.
        assertEquals(
            unauthorized,
            evaluate(
                activate(playback, pairing, roles = roles, method = "pairing_psk"),
                category = PskCategory.SENTINEL,
                unpairedAccess = false,
            ),
        )
    }

    @Test
    fun aPairedSessionIsNeverToldPairingIsRequired() {
        // Rule 1 needs an unpaired session; a long-term one falls to rule 2.
        assertEquals(
            unauthorized,
            evaluate(
                activate(pairing, method = "dynamic_pairing_code"),
                category = PskCategory.LONG_TERM,
                unpairedAccess = false,
            ),
        )
    }

    // ---- active_roles ----

    @Test
    fun rolesMayBeCarriedWithoutPlaybackBeingDeclared() {
        // "it MAY do so even when 'playback' is not currently in activities."
        for (cat in PskCategory.entries) {
            assertEquals(
                ActivationOutcome.Accept(roles),
                evaluate(activate(roles = roles), category = cat),
                cat.name,
            )
        }
    }

    @Test
    fun absentRolesOnTheFirstActivationMeanEmpty() {
        assertEquals(
            ActivationOutcome.Accept(emptyList()),
            evaluate(activate(playback), previousRoles = roles, first = true),
        )
    }

    @Test
    fun absentRolesLaterPersistThePreviousValue() {
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(activate(playback), previousRoles = roles, first = false),
        )
        // Entering pairing does not by itself affect active_roles.
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(
                activate(playback, pairing, method = "dynamic_pairing_code"),
                previousRoles = roles,
                first = false,
            ),
        )
        // An explicit empty list clears them.
        assertEquals(
            ActivationOutcome.Accept(emptyList()),
            evaluate(activate(playback, roles = emptyList()), previousRoles = roles, first = false),
        )
    }

    @Test
    fun losingPlaybackCapabilityLaterClearsPersistedRolesRatherThanRejecting() {
        // "if a later activation changes activities so the connection is no
        // longer playback-capable without explicitly sending active_roles, the
        // persisted roles are treated as empty rather than the message
        // rejected." Reachable when unpaired access was switched off after the
        // roles were granted.
        for (cat in listOf(PskCategory.PAIRING, PskCategory.SENTINEL)) {
            assertEquals(
                ActivationOutcome.Accept(emptyList()),
                evaluate(
                    activate(pairing, method = methodFor(cat)),
                    category = cat,
                    unpairedAccess = false,
                    previousRoles = roles,
                    first = false,
                ),
                cat.name,
            )
            assertEquals(
                ActivationOutcome.Accept(emptyList()),
                evaluate(activate(), category = cat, unpairedAccess = false, previousRoles = roles, first = false),
                cat.name,
            )
        }
    }

    @Test
    fun aRehandshakeToTheLongTermPskKeepsRolesWhenTheNextActivationOmitsThem() {
        // connection.md#re-handshake: the activation after it "is a subsequent
        // one on the same connection. The activation rules, including those
        // for omitted active_roles, apply under the newly matched PSK."
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(
                activate(playback),
                category = PskCategory.LONG_TERM,
                unpairedAccess = false,
                previousRoles = roles,
                first = false,
            ),
        )
    }

    // ---- pairing method ----

    @Test
    fun aMethodWeDoNotOfferAbortsButKeepsTheConnection() {
        assertEquals(
            methodNotSupported,
            evaluate(
                activate(pairing, method = "static_pairing_code"),
                methods = setOf("pairing_psk", "dynamic_pairing_code"),
            ),
        )
        assertEquals(
            methodNotSupported,
            evaluate(activate(pairing, method = "dynamic_pairing_code"), methods = setOf("pairing_psk")),
        )
    }

    @Test
    fun pairingPskIsValidIfAndOnlyIfThePairingPskMatched() {
        // "pairing.method MUST be 'pairing_psk' if and only if the matched PSK
        // is the pairing PSK."
        assertEquals(
            methodNotSupported,
            evaluate(activate(pairing, method = "pairing_psk"), category = PskCategory.SENTINEL),
        )
        assertEquals(
            methodNotSupported,
            evaluate(activate(pairing, method = "dynamic_pairing_code"), category = PskCategory.PAIRING),
        )
    }

    @Test
    fun aPairingActivationWithNoMethodAborts() {
        // "pairing: Required when 'pairing' is in activities."
        assertEquals(methodNotSupported, evaluate(activate(pairing)))
    }

    @Test
    fun theMethodIsIgnoredWhenPairingIsNotDeclared() {
        // "A client ignores this field when activities does not include
        // 'pairing'."
        assertEquals(
            ActivationOutcome.Accept(roles),
            evaluate(activate(playback, roles = roles, method = "static_pairing_code")),
        )
    }

    // ---- parsing ----

    @Test
    fun parsesActivitiesRolesAndPairing() {
        val a = parse(
            """{"type":"server/activate","payload":{"activities":["playback","pairing"],
               "active_roles":["player@v1"],
               "pairing":{"method":"dynamic_pairing_code","format":"digits"}}}"""
        )!!
        assertEquals(setOf(playback, pairing), a.activities)
        assertEquals(listOf("player@v1"), a.activeRoles)
        assertEquals("dynamic_pairing_code", a.pairingMethod)
    }

    @Test
    fun distinguishesAbsentRolesFromEmptyRoles() {
        assertNull(parse("""{"payload":{"activities":[]}}""")!!.activeRoles)
        assertEquals(
            emptyList(),
            parse("""{"payload":{"activities":[],"active_roles":[]}}""")!!.activeRoles,
        )
    }

    @Test
    fun ignoresUnknownActivitiesRatherThanFailing() {
        // 'management' was an activity before 1.0.0-rc1; it is unknown now.
        val a = parse("""{"payload":{"activities":["playback","management"]}}""")!!
        assertEquals(setOf(playback), a.activities)
        assertEquals(listOf("management"), a.unknownActivities)
    }

    @Test
    fun missingActivitiesIsUnparseable() {
        assertNull(parse("""{"payload":{"active_roles":[]}}"""))
        assertNull(ServerActivateRules.parse(null))
    }
}
