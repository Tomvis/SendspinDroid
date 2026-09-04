package com.sendspindroid.sendspin.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * pairing.md "Failure counter": one counter for the method, persisted across
 * reboots, NOT partitioned by server_id or source IP. It increments when the
 * client starts emitting the code, at most once per attempt, and "no other
 * event increments it". It resets when server_kc verification succeeds,
 * whether or not the attempt finalizes. At 5 the method escalates.
 *
 * Note the divergence recorded in the design doc: the aiosendspin 9.1.1
 * reference increments on a server_kc MISMATCH instead. We follow the spec,
 * so five attempts escalate even when each reached a valid server_kc.
 */
class PairingFailureCounterTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        var saves = 0
        override fun load(): Int = value
        override fun save(v: Int) { value = v; saves++ }
    }

    @Test
    fun `starts unescalated`() {
        assertEquals(false, PairingFailureCounter(FakeStore()).isEscalated)
    }

    @Test
    fun `escalates on the fifth emission`() {
        val counter = PairingFailureCounter(FakeStore())
        repeat(4) { counter.onEmissionStarted() }
        assertEquals(false, counter.isEscalated)
        counter.onEmissionStarted()
        assertEquals(true, counter.isEscalated)
    }

    @Test
    fun `a verified server_kc de-escalates`() {
        val counter = PairingFailureCounter(FakeStore())
        repeat(5) { counter.onEmissionStarted() }
        counter.onServerKcVerified()
        assertEquals(false, counter.isEscalated)
    }

    @Test
    fun `the counter persists through the store`() {
        val store = FakeStore()
        PairingFailureCounter(store).onEmissionStarted()
        assertEquals(1, store.value)
        assertEquals(1, PairingFailureCounter(store).let { it.onEmissionStarted(); store.value } - 1)
    }

    @Test
    fun `a stored value above the threshold is already escalated`() {
        assertEquals(true, PairingFailureCounter(FakeStore(9)).isEscalated)
    }

    @Test
    fun `reset writes zero even when already zero is not required`() {
        val store = FakeStore(3)
        PairingFailureCounter(store).onServerKcVerified()
        assertEquals(0, store.value)
    }
}
