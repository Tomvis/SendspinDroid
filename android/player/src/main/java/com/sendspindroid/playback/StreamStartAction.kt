package com.sendspindroid.playback

import com.sendspindroid.sendspin.protocol.StreamConfig

/**
 * What a player `stream/start` asks of the audio pipeline.
 *
 * messaging.md: "If sent for a role that already has an active stream, updates
 * the stream configuration without ending the stream." roles/player/v1.md:
 * "Clients MUST keep buffered chunks and decode each chunk in the format that
 * was in effect when it was received."
 */
internal enum class StreamStartAction {
    /** No stream was active: discard anything left over and start clean. */
    NEW_STREAM,

    /** Active stream, identical configuration: nothing to do. */
    UNCHANGED,

    /** Active stream, new configuration: applies to later chunks only. */
    FORMAT_CHANGE;

    companion object {
        /** @param active configuration of the active stream, null if none */
        fun of(active: StreamConfig?, next: StreamConfig): StreamStartAction = when (active) {
            null -> NEW_STREAM
            next -> UNCHANGED
            else -> FORMAT_CHANGE
        }
    }
}
