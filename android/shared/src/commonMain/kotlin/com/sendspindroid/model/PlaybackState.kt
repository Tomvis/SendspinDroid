package com.sendspindroid.model

import com.sendspindroid.shared.platform.Platform

data class PlaybackState(
    val groupId: String? = null,
    val groupName: String? = null,
    val playbackState: PlaybackStateType = PlaybackStateType.IDLE,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val albumTrack: Int? = null,
    val queueTrack: Int? = null,
    val totalTracks: Int? = null,
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

    fun withMetadata(
        title: String?,
        artist: String?,
        albumArtist: String?,
        album: String?,
        artworkUrl: String?,
        year: Int?,
        albumTrack: Int?,
        queueTrack: Int?,
        totalTracks: Int?,
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
        albumArtist = when {
            albumArtist == null -> this.albumArtist
            albumArtist.isEmpty() -> null
            else -> albumArtist
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
        year = when {
            year == null -> this.year
            year <= 0 -> null
            else -> year
        },
        albumTrack = when {
            albumTrack == null -> this.albumTrack
            albumTrack <= 0 -> null
            else -> albumTrack
        },
        queueTrack = when {
            queueTrack == null -> this.queueTrack
            queueTrack <= 0 -> null
            else -> queueTrack
        },
        totalTracks = when {
            totalTracks == null -> this.totalTracks
            totalTracks <= 0 -> null
            else -> totalTracks
        },
        durationMs = if (durationMs > 0) durationMs else this.durationMs,
        positionMs = positionMs,
        // Only stamp positionUpdatedAt when position is non-zero. When positionMs is 0
        // (e.g., initial metadata for a new track before audio starts), keep existing
        // timestamp so interpolatedPositionMs doesn't phantom-count up from zero.
        positionUpdatedAt = if (positionMs > 0) Platform.elapsedRealtimeMs() else this.positionUpdatedAt,
        playbackSpeed = playbackSpeed
    )

    fun withClearedMetadata(): PlaybackState = copy(
        title = null,
        artist = null,
        albumArtist = null,
        album = null,
        artworkUrl = null,
        year = null,
        albumTrack = null,
        queueTrack = null,
        totalTracks = null,
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
