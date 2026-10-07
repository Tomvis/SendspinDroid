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

    /**
     * For title/artist/album null keeps the current value and "" clears it.
     * Fork: artwork and total tracks survive an empty value, ancillaries clear
     * on a new track, and a new track's position 0 leaves positionUpdatedAt 0
     * (the "no anchor yet" sentinel the TV progress rail reads).
     */
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
            // Preserve the prior cover on BOTH null and empty input; only a
            // non-empty URL replaces it (a real clear goes through
            // withClearedMetadata on disconnect/stop). The stateless kotlinx
            // parser collapses an OMITTED artwork_url to "", and the SendSpin
            // server omits it in most frames -- the periodic position-only
            // updates AND the new-track announce, whose real URL lands a frame
            // or two later. Clearing on empty therefore blanked the cover both
            // mid-track and at every track change until the URL caught up.
            // Holding the prior image through the gap (the next non-empty URL
            // overrides it) reconstructs the pre-merge diff-merge's
            // inherit-on-absent and matches the optimistic queue-tap's
            // preserve-on-null. isNewTrack is intentionally NOT consulted here:
            // an art-less track is vanishingly rare on this server and a
            // momentary stale cover beats a blank one.
            artworkUrl = if (artworkUrl.isNullOrEmpty()) this.artworkUrl else artworkUrl,
            year = resolveInt(year, this.year),
            albumTrack = resolveInt(albumTrack, this.albumTrack),
            queueTrack = resolveInt(queueTrack, this.queueTrack),
            // total_tracks is the queue length, not a per-track attribute. The
            // server sends it once (the connect snapshot) and OMITS it on every
            // later track-change and position frame -- the stateless parser maps
            // that to 0 -> null. resolveInt clears ancillaries on a track change
            // (correct for year / album_track), which wiped totalTracks too and
            // froze the NowPlaying "X of Y" counter once the snapshot scrolled
            // past (the sticky updater only commits when totalTracks > 0).
            // Preserve the prior value on absent/non-positive input; only a
            // positive value replaces it -- same inherit-on-absent contract as
            // artworkUrl. A real reset goes through withClearedMetadata.
            totalTracks = totalTracks?.takeIf { it > 0 } ?: this.totalTracks,
            // Upstream rc1: 0 means unknown. Keep the prior duration only on a
            // same-track refresh; a new track takes what it was given.
            durationMs = if (durationMs > 0 || isNewTrack) durationMs else this.durationMs,
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

    /**
     * The state once a connection has ended. Everything the server supplied is
     * gone; the volume is the device's own and outlives the connection. Every
     * session-extras broadcast resends it, so resetting it to the default would
     * put the Device Volume slider at 100% while a reconnect is under way.
     */
    fun withConnectionEnded(): PlaybackState = PlaybackState(volume = volume)

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
