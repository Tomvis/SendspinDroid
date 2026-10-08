package com.sendspindroid.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    @Test
    fun `default SessionState has no server and an Idle transport`() {
        val state = SessionState()
        assertNull(state.server)
        assertEquals(TransportState.Idle, state.sendSpin)
    }

    @Test
    fun `SessionState holds the transport state it was given`() {
        val state = SessionState(
            server = null,
            sendSpin = TransportState.Failed(FailureReason.TransientNetwork),
        )
        assertEquals(
            TransportState.Failed(FailureReason.TransientNetwork),
            state.sendSpin
        )
    }
}
