package com.sendspindroid.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sendspindroid.model.UnifiedServer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds the media item lists for the Android Auto / MediaLibraryService
 * browse tree.
 *
 * Extracted from PlaybackService so the list-shaping rules are unit-testable
 * without a running service. The critical invariant enforced here:
 *
 * **A browsable node must never resolve to an empty list.** Android Auto
 * renders an empty children list as a blank "unable to load content" screen,
 * which fails Google Play's automated Auto quality review (the app was
 * rejected for exactly this when no SendSpin server was reachable). Every
 * list-producing function therefore falls back to a non-interactive message
 * item that tells the user what to do next.
 */
object AutoBrowseTree {

    // Browse tree media IDs
    const val MEDIA_ID_ROOT = "root"
    const val MEDIA_ID_DISCOVERED = "discovered_servers"
    const val MEDIA_ID_SERVER_PREFIX = "server_"
    const val MEDIA_ID_SAVED_SERVER_PREFIX = "saved_server_"

    // Non-interactive informational rows (neither playable nor browsable)
    const val MEDIA_ID_MESSAGE_PREFIX = "message_"
    const val MEDIA_ID_MESSAGE_NO_SERVERS = "${MEDIA_ID_MESSAGE_PREFIX}no_servers"

    // Android Auto content style hint keys
    const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_LIST = 1

    /**
     * Root tabs. SendSpin has no library, so the only root tab is the
     * "Connect" node used to choose or switch a server from the car.
     */
    fun rootChildren(isConnected: Boolean): List<MediaItem> {
        return listOf(
            browsableItem(
                mediaId = MEDIA_ID_DISCOVERED,
                title = "Connect",
                subtitle = if (isConnected) "Connected" else null
            )
        )
    }

    /**
     * Children of the "Connect" node: saved servers (most recently connected
     * first) followed by mDNS-discovered servers not already saved.
     *
     * When there is nothing to show yet, waits up to [discoveryWaitMs] for the
     * first mDNS result so the initial browse on Android Auto doesn't race
     * discovery and come back empty. If still nothing after the wait, returns
     * a "No servers found" guidance row instead of an empty list. Later
     * discoveries refresh the node via notifyChildrenChanged.
     */
    suspend fun serverListChildren(
        savedServers: List<UnifiedServer>,
        discoveredServersFlow: StateFlow<List<UnifiedServer>>,
        discoveryWaitMs: Long,
    ): List<MediaItem> {
        var discovered = discoveredServersFlow.value
        if (savedServers.isEmpty() && discovered.isEmpty() && discoveryWaitMs > 0) {
            withTimeoutOrNull(discoveryWaitMs) {
                discoveredServersFlow.first { it.isNotEmpty() }
            }
            discovered = discoveredServersFlow.value
        }

        val savedItems = savedServers
            .sortedByDescending { it.lastConnectedMs }
            .map { savedServerItem(it) }
        val discoveredItems = discovered.mapNotNull { server ->
            val address = server.local?.address ?: return@mapNotNull null
            playableServerItem(server.name, address)
        }

        val items = savedItems + discoveredItems
        return items.ifEmpty {
            listOf(
                messageItem(
                    mediaId = MEDIA_ID_MESSAGE_NO_SERVERS,
                    title = "No servers found",
                    subtitle = "Open SendSpin Player on your phone to add a server"
                )
            )
        }
    }

    /** A browsable (folder) node. */
    fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        extras: Bundle? = null,
        iconRes: Int = 0
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .apply {
                        if (extras != null) setExtras(extras)
                        if (iconRes != 0) {
                            setArtworkUri(Uri.parse("android.resource://com.sendspindroid/$iconRes"))
                        }
                    }
                    .build()
            )
            .build()
    }

    /** A playable row for an mDNS-discovered server (media ID keyed by address). */
    fun playableServerItem(name: String, address: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SERVER_PREFIX$address")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setSubtitle(address)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /** A playable row for a saved server (media ID keyed by server UUID). */
    fun savedServerItem(server: UnifiedServer): MediaItem {
        val subtitle = server.local?.address
            ?: if (server.proxy != null) "Proxy"
            else if (server.remote != null) "Remote Access"
            else ""
        return MediaItem.Builder()
            .setMediaId("$MEDIA_ID_SAVED_SERVER_PREFIX${server.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(server.name)
                    .setSubtitle(subtitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /**
     * A non-interactive informational row. Neither playable nor browsable, so
     * Android Auto renders it as plain text the user can read but not tap.
     */
    fun messageItem(mediaId: String, title: String, subtitle: String? = null): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsPlayable(false)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }
}
