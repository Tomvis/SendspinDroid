package com.sendspindroid.sendspin.pairing

/** Persistence for the dynamic-pairing round count. */
interface PairingCounterStore {
    fun load(): Int
    fun save(value: Int)
}

/**
 * Brute-force protection for the Dynamic Pairing Code Flow: the round limit.
 *
 * `pairing.md#rounds`: "After 20 rounds since its last verified `server_kc` -
 * the round limit - the client MUST abort rather than retry and hold attempts
 * back until a deliberate, manufacturer-defined operator action, which also
 * resets the count. The count is not partitioned by `server_id` or source
 * address." Partitioning would let an attacker reset the budget by changing
 * either.
 *
 * A round counts once its code is being emitted, so an attempt the server
 * abandons "counts toward the round limit only when the code was already
 * being emitted" (`pairing.md`, "Entering and leaving pairing"). A count left
 * by the older per-attempt gate only shortens what is left of the budget.
 */
class PairingFailureCounter(private val store: PairingCounterStore) {

    /** Abort rather than retry, and hold the next attempt back. */
    val roundLimitReached: Boolean get() = store.load() >= ROUND_LIMIT

    /** Count a round, when it starts emitting the code and before it can end. */
    fun onRoundStarted() {
        store.save(store.load() + 1)
    }

    /** Reset on a verified server_kc, whether or not the attempt finalizes. */
    fun onServerKcVerified() {
        store.save(0)
    }

    /** Reset by the operator action that lifts the hold-back ("Allow pairing"). */
    fun onOperatorAllowed() {
        store.save(0)
    }

    companion object {
        const val ROUND_LIMIT = 20
    }
}
