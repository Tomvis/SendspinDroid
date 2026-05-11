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
    ): PlaybackState {
        val newTitle = when {
            title == null -> this.title
            title.isEmpty() -> null
            else -> title
        }
        val newArtist = when {
            artist == null -> this.artist
            artist.isEmpty() -> null
            else -> artist
        }
        val newAlbum = when {
            album == null -> this.album
            album.isEmpty() -> null
            else -> album
        }
        // Detect track change so a null ancillary on a new track clears the
        // prior value instead of leaking it. The "null = preserve" pattern is
        // useful for partial follow-up updates on the same track (server
        // sometimes omits queue_track / year), but on a new track it causes
        // the previous track's year / track number / queue position to stick.
        // Mirrors the same isNewTrack check in [mergeTrackMetadata] (VM-side).
        // artworkUrl deliberately keeps the unconditional-preserve-on-null
        // behavior: the optimistic queue-tap path relies on keeping the prior
        // artwork visible during the brief gap before the new image loads.
        val isNewTrack = newTitle != this.title ||
            newArtist != this.artist ||
            newAlbum != this.album
        fun resolveStr(input: String?, prior: String?): String? = when {
            input == null -> if (isNewTrack) null else prior
            input.isEmpty() -> null
            else -> input
        }
        fun resolveInt(input: Int?, prior: Int?): Int? = when {
            input == null -> if (isNewTrack) null else prior
            input <= 0 -> null
            else -> input
        }
        return copy(
            title = newTitle,
            artist = newArtist,
            albumArtist = resolveStr(albumArtist, this.albumArtist),
            album = newAlbum,
            artworkUrl = when {
                artworkUrl == null -> this.artworkUrl
                artworkUrl.isEmpty() -> null
                else -> artworkUrl
            },
            year = resolveInt(year, this.year),
            albumTrack = resolveInt(albumTrack, this.albumTrack),
            queueTrack = resolveInt(queueTrack, this.queueTrack),
            totalTracks = resolveInt(totalTracks, this.totalTracks),
            durationMs = if (durationMs > 0) durationMs else this.durationMs,
            positionMs = positionMs,
            // positionUpdatedAt: stamp when positionMs > 0 (real anchor),
            // zero on a new track with positionMs == 0 (interpolatedPositionMs
            // / ProgressRail use 0 as the "no real anchor yet" sentinel and
            // would otherwise leak the prior track's elapsed time into the
            // new track's display), preserve on same-track refresh.
            positionUpdatedAt = when {
                positionMs > 0 -> Platform.elapsedRealtimeMs()
                isNewTrack -> 0L
                else -> this.positionUpdatedAt
            },
            playbackSpeed = playbackSpeed
        )
    }

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
