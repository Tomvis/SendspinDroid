package com.sendspindroid.network

import com.sendspindroid.model.*
import com.sendspindroid.shared.log.Log

/**
 * Selects the local connection for a unified server, if configured.
 *
 * Remote (WebRTC) and proxy (authenticated reverse proxy) access were removed;
 * SendSpin's spec defines no remote-access mechanism of its own. A server whose
 * only configured method is remote or proxy -- or whose preference is
 * REMOTE_ONLY/PROXY_ONLY -- has no connection available.
 */
object ConnectionSelector {

    private const val TAG = "ConnectionSelector"

    /**
     * Result of connection selection.
     */
    sealed class SelectedConnection {
        data class Local(val address: String, val path: String) : SelectedConnection()
    }

    /**
     * Selects the local connection for the given server, if one is configured
     * and the user's preference allows it.
     *
     * @param server The unified server with configured connection methods
     * @return The selected connection, or null if none is available
     */
    fun selectConnection(
        server: UnifiedServer
    ): SelectedConnection? {
        return when (server.connectionPreference) {
            ConnectionPreference.LOCAL_ONLY, ConnectionPreference.AUTO -> {
                server.local?.let {
                    SelectedConnection.Local(it.address, it.path)
                }.also {
                    if (it == null) Log.w(TAG, "No local connection configured for ${server.name}")
                }
            }
            ConnectionPreference.REMOTE_ONLY -> {
                Log.w(TAG, "REMOTE_ONLY preference but remote access is no longer supported")
                null
            }
            ConnectionPreference.PROXY_ONLY -> {
                Log.w(TAG, "PROXY_ONLY preference but proxy access is no longer supported")
                null
            }
        }
    }

    /**
     * Returns the connection priority order. Local is the only supported
     * connection method, so this is always a single-element list.
     */
    fun getPriorityOrder(): List<ConnectionType> =
        listOf(ConnectionType.LOCAL)

    /**
     * Returns a human-readable description of the selected connection type.
     */
    fun getConnectionDescription(selected: SelectedConnection): String {
        return when (selected) {
            is SelectedConnection.Local -> "Local (${selected.address})"
        }
    }
}
