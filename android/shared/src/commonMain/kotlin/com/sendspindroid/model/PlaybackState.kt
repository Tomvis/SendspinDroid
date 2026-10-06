package com.sendspindroid.model

import com.sendspindroid.shared.platform.Platform

data class PlaybackState(
    val groupId: String? = null,
    val groupName: String? = null,
    val playbackState: PlaybackStateType = PlaybackStateType.IDLE,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val positionUpdatedAt: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val playbackSpeed: Int = 1000  // Speed multiplier (1000 = 1.0x normal speed)
) {
    val displayTitle: String
        get() = title ?: "Unknown Track"

    val displayArtist: String
        get() = artist ?: "Unknown Artist"

    val displayString: String
        get() = when {
            artist != null && title != null -> "$artist - $title"
            title != null -> title
            else -> "Unknown Track"
        }

    val hasMetadata: Boolean
        get() = title != null || artist != null || album != null

    val interpolatedPositionMs: Long
        get() {
            if (playbackState != PlaybackStateType.PLAYING || positionUpdatedAt == 0L) {
                return positionMs
            }
            val elapsedMs = Platform.elapsedRealtimeMs() - positionUpdatedAt
            return minOf(durationMs, positionMs + elapsedMs)
        }

    /**
     * For the text fields null keeps the current value and "" clears it.
     * Duration and position are taken as given: 0 means unknown, so a caller
     * that wants to keep the current duration passes it. The position is
     * stamped as received now.
     */
    fun withMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        durationMs: Long,
        positionMs: Long,
        playbackSpeed: Int = this.playbackSpeed
    ): PlaybackState = copy(
        title = when {
            title == null -> this.title
            title.isEmpty() -> null
            else -> title
        },
        artist = when {
            artist == null -> this.artist
            artist.isEmpty() -> null
            else -> artist
        },
        album = when {
            album == null -> this.album
            album.isEmpty() -> null
            else -> album
        },
        artworkUrl = when {
            artworkUrl == null -> this.artworkUrl
            artworkUrl.isEmpty() -> null
            else -> artworkUrl
        },
        durationMs = durationMs,
        positionMs = positionMs,
        // Stamped for every position, zero included. Keeping the old stamp for
        // a new track's 0 made the display count on from the previous track.
        positionUpdatedAt = Platform.elapsedRealtimeMs(),
        playbackSpeed = playbackSpeed
    )

    fun withClearedMetadata(): PlaybackState = copy(
        title = null,
        artist = null,
        album = null,
        artworkUrl = null,
        durationMs = 0,
        positionMs = 0,
        positionUpdatedAt = 0,
        playbackSpeed = 1000
    )

    fun withGroupUpdate(
        groupId: String?,
        groupName: String?,
        playbackState: PlaybackStateType
    ): PlaybackState = copy(
        groupId = groupId,
        groupName = groupName,
        playbackState = playbackState
    )
}

enum class PlaybackStateType {
    IDLE,
    PLAYING,
    PAUSED,
    BUFFERING,
    STOPPED;

    companion object {
        fun fromString(value: String): PlaybackStateType = when (value.lowercase()) {
            "playing" -> PLAYING
            "paused" -> PAUSED
            "buffering" -> BUFFERING
            "stopped" -> STOPPED
            else -> IDLE
        }
    }
}
