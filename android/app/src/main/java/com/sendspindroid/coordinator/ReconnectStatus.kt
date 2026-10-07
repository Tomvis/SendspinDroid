package com.sendspindroid.coordinator

import com.sendspindroid.model.ConnectionType

/**
 * Status of the service's reconnect loop, published by
 * ConnectionCoordinator.reconnectStatus. The UI reads it to show that a
 * reconnect is in progress; it does not drive one.
 *
 * There is no failed state: the loop has no attempt cap and ends only by
 * succeeding or by being cancelled (back to [Idle]).
 */
sealed class ReconnectStatus {
    object Idle : ReconnectStatus()

    data class Attempting(
        val serverId: String,
        val attempt: Int,
        val method: ConnectionType?,
    ) : ReconnectStatus()

    data class Succeeded(val serverId: String) : ReconnectStatus()
}
