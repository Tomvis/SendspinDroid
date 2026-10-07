package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectStatusTest {
    @Test
    fun `Attempting carries server id, attempt number, and method`() {
        val s: ReconnectStatus = ReconnectStatus.Attempting(
            serverId = "s1",
            attempt = 3,
            method = ConnectionType.LOCAL,
        )
        assertEquals("s1", (s as ReconnectStatus.Attempting).serverId)
        assertEquals(3, s.attempt)
        assertEquals(ConnectionType.LOCAL, s.method)
    }

    @Test
    fun `Succeeded carries server id`() {
        val ok: ReconnectStatus = ReconnectStatus.Succeeded("s1")
        assertEquals("s1", (ok as ReconnectStatus.Succeeded).serverId)
    }

    @Test
    fun `when expression is exhaustive`() {
        val cases: List<ReconnectStatus> = listOf(
            ReconnectStatus.Idle,
            ReconnectStatus.Attempting("s", 1, null),
            ReconnectStatus.Succeeded("s"),
        )
        val labels = cases.map {
            when (it) {
                ReconnectStatus.Idle -> "idle"
                is ReconnectStatus.Attempting -> "attempting"
                is ReconnectStatus.Succeeded -> "succeeded"
            }
        }
        assertEquals(listOf("idle", "attempting", "succeeded"), labels)
    }
}
