package com.sendspindroid.sendspin

import com.sendspindroid.sendspin.protocol.SendSpinProtocol

/**
 * Endpoint a SendSpin connection targets. Wraps the explicit connectLocal
 * method behind a single `connect(endpoint)` entry point.
 *
 * Phase 4 of the ConnectionCoordinator design.
 * See docs/superpowers/specs/2026-05-05-connection-coordinator-design.md
 */
sealed class SendSpinEndpoint {
    /**
     * Direct WebSocket to a server on the local network.
     * @param address host[:port], e.g. "10.0.1.5:8927"
     * @param path WebSocket path, defaults to SendSpin's standard endpoint.
     */
    data class Local(
        val address: String,
        val path: String = SendSpinProtocol.ENDPOINT_PATH,
    ) : SendSpinEndpoint()
}
