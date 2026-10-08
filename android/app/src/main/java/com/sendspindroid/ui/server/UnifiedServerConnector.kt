package com.sendspindroid.ui.server

import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.playback.PlaybackService

/**
 * Helper class for connecting to unified servers.
 *
 * Encapsulates the logic of selecting the appropriate connection method
 * and sending the correct command to PlaybackService.
 *
 * ## Usage
 * ```kotlin
 * val connector = UnifiedServerConnector { method ->
 *     onConnectionMethodSelected(method)
 * }
 *
 * // Connect using auto-selection
 * connector.connect(server, mediaController)
 *
 * // Or connect using a specific method
 * connector.connectLocal(server, mediaController)
 * ```
 */
class UnifiedServerConnector(
    private val onConnectionStarted: ((ConnectionSelector.SelectedConnection) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UnifiedServerConnector"
    }

    /**
     * Connect to a unified server using auto-selection.
     *
     * @param server The unified server to connect to
     * @param controller MediaController for sending commands
     * @return The selected connection method, or null if no method available
     */
    fun connect(
        server: UnifiedServer,
        controller: MediaController
    ): ConnectionSelector.SelectedConnection? {
        // Select best connection method
        val selected = ConnectionSelector.selectConnection(server)
        if (selected == null) {
            Log.w(TAG, "No connection method available for ${server.name}")
            return null
        }

        Log.d(TAG, "Auto-selected ${ConnectionSelector.getConnectionDescription(selected)} for ${server.name}")

        // Execute connection with server ID
        executeConnection(selected, controller, server.id)
        onConnectionStarted?.invoke(selected)

        // Update last connected timestamp
        UnifiedServerRepository.updateLastConnected(server.id)

        return selected
    }

    /**
     * Connect using local connection method.
     */
    fun connectLocal(
        server: UnifiedServer,
        controller: MediaController
    ): Boolean {
        val local = server.local ?: return false

        val selected = ConnectionSelector.SelectedConnection.Local(local.address, local.path)
        executeConnection(selected, controller, server.id)
        onConnectionStarted?.invoke(selected)

        UnifiedServerRepository.updateLastConnected(server.id)
        return true
    }

    /**
     * Sends the appropriate command to PlaybackService based on connection type.
     *
     * @param selected The selected connection method with connection details
     * @param controller MediaController for sending commands
     * @param serverId Optional server ID (if known)
     */
    private fun executeConnection(
        selected: ConnectionSelector.SelectedConnection,
        controller: MediaController,
        serverId: String? = null
    ) {
        when (selected) {
            is ConnectionSelector.SelectedConnection.Local -> {
                val args = Bundle().apply {
                    putString(PlaybackService.ARG_SERVER_ADDRESS, selected.address)
                    putString(PlaybackService.ARG_SERVER_PATH, selected.path)
                    serverId?.let { putString(PlaybackService.ARG_SERVER_ID, it) }
                }
                val command = SessionCommand(PlaybackService.COMMAND_CONNECT, Bundle.EMPTY)
                controller.sendCustomCommand(command, args)
                Log.d(TAG, "Sent local connect: ${selected.address}, serverId=$serverId")
            }
        }
    }
}
