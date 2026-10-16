package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md "Rounds": "After 20 rounds since its last verified `server_kc` -
 * the round limit - the client MUST abort rather than retry and hold attempts
 * back until a deliberate, manufacturer-defined operator action, which also
 * resets the count. The count is not partitioned by `server_id` or source
 * address."
 */
class PairingFailureCounterTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        override fun load(): Int = value
        override fun save(v: Int) { value = v }
    }

    @Test
    fun `starts below the round limit`() {
        assertEquals(false, PairingFailureCounter(FakeStore()).roundLimitReached)
    }

    @Test
    fun `each round started adds exactly one`() {
        val store = FakeStore()
        val counter = PairingFailureCounter(store)
        counter.onRoundStarted()
        assertEquals(1, store.value)
        counter.onRoundStarted()
        assertEquals(2, store.value)
    }

    @Test
    fun `the limit is reached at the twentieth round`() {
        val counter = PairingFailureCounter(FakeStore())
        repeat(19) { counter.onRoundStarted() }
        assertEquals(false, counter.roundLimitReached)
        counter.onRoundStarted()
        assertEquals(true, counter.roundLimitReached)
    }

    @Test
    fun `a verified server_kc resets the count`() {
        val store = FakeStore(20)
        val counter = PairingFailureCounter(store)
        counter.onServerKcVerified()
        assertEquals(0, store.value)
        assertEquals(false, counter.roundLimitReached)
    }

    @Test
    fun `the operator action resets the count`() {
        val store = FakeStore(20)
        val counter = PairingFailureCounter(store)
        counter.onOperatorAllowed()
        assertEquals(0, store.value)
        assertEquals(false, counter.roundLimitReached)
    }

    @Test
    fun `the count persists through the store`() {
        val store = FakeStore()
        PairingFailureCounter(store).onRoundStarted()
        PairingFailureCounter(store).onRoundStarted()
        assertEquals(2, store.value)
    }

    @Test
    fun `a stored value past the limit is at the limit`() {
        assertEquals(true, PairingFailureCounter(FakeStore(25)).roundLimitReached)
    }
}
