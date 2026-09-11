package com.sendspindroid.sendspin.pairing

/** Persistence for the dynamic-pairing failure counter. */
interface PairingCounterStore {
    fun load(): Int
    fun save(value: Int)
}

/**
 * Brute-force protection for the Dynamic Pairing Code Flow.
 *
 * One counter for the method, deliberately NOT partitioned by server or
 * address: partitioning would let an attacker reset the budget by changing
 * either.
 */
class PairingFailureCounter(private val store: PairingCounterStore) {

    val isEscalated: Boolean get() = store.load() >= ESCALATION_THRESHOLD

    /**
     * Increment, at most once per attempt.
     *
     * The spec is explicit that emission is the ONLY increment trigger; a
     * verification failure does not add to it. Callers must invoke this once
     * per attempt, when emission begins.
     */
    fun onEmissionStarted() {
        store.save(store.load() + 1)
    }

    /** Reset on a verified server_kc, whether or not the attempt finalizes. */
    fun onServerKcVerified() {
        store.save(0)
    }

    companion object {
        const val ESCALATION_THRESHOLD = 5
    }
}
