package com.sendspindroid.ui.main

import android.graphics.Bitmap

/**
 * Track metadata for display in player UI.
 *
 * Integer fields use 0 to indicate "not set" / absent.
 */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val year: Int = 0,
    val albumTrack: Int = 0,
    val queueTrack: Int = 0,
    val totalTracks: Int = 0
) {
    companion object {
        val EMPTY = TrackMetadata("", "", "")
    }

    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank() && album.isBlank()
}

/**
 * Merge an incoming metadata emission with the previous state.
 *
 * title / artist / album are always taken from the new emission (callers
 * always know them). Ancillary fields use null to mean "caller doesn't have
 * this value":
 *
 *  - Same track (title/artist/album match prev): a null ancillary preserves
 *    the prior value. This is the Media3-refreshes-its-own-metadata case;
 *    we don't want a refresh to wipe queue position pushed by SendSpin.
 *  - Different track (any of title/artist/album differs): a null ancillary
 *    clears the field to its "absent" sentinel (0 / ""). Otherwise a
 *    Media3-only emission for a NEW track would keep the previous track's
 *    "Track 03 · 5 of 12 · 2019" until the authoritative SendSpin broadcast
 *    catches up — the frankenmetadata bug.
 *
 * Callers that *do* know an ancillary field pass it explicitly: the SendSpin
 * broadcast passes 0/"" for absent; Media3 paths pass whatever Media3 has;
 * the optimistic queue-tap callback passes 0/"" to fully clear.
 */
internal fun mergeTrackMetadata(
    prev: TrackMetadata,
    title: String,
    artist: String,
    album: String,
    albumArtist: String? = null,
    year: Int? = null,
    albumTrack: Int? = null,
    queueTrack: Int? = null,
    totalTracks: Int? = null
): TrackMetadata {
    val isNewTrack = title != prev.title || artist != prev.artist || album != prev.album
    return TrackMetadata(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist ?: if (isNewTrack) "" else prev.albumArtist,
        year = year ?: if (isNewTrack) 0 else prev.year,
        albumTrack = albumTrack ?: if (isNewTrack) 0 else prev.albumTrack,
        queueTrack = queueTrack ?: if (isNewTrack) 0 else prev.queueTrack,
        totalTracks = totalTracks ?: if (isNewTrack) 0 else prev.totalTracks
    )
}

/**
 * Active audio stream specs, derived from the SendSpin stream/start config.
 * Bitrate is computed from the raw PCM rate (sampleRate * channels * bitDepth)
 * regardless of the wire codec, since that's the uncompressed bandwidth the
 * device renders.
 */
data class AudioStreamSpec(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
) {
    val bitrateKbps: Int get() = sampleRate * channels * bitDepth / 1000
}

/**
 * Source for album artwork - can be binary data, URI, or URL.
 */
sealed class ArtworkSource {
    data class ByteArray(val data: kotlin.ByteArray) : ArtworkSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArray) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Uri(val uri: android.net.Uri) : ArtworkSource()
    data class Url(val url: String) : ArtworkSource()
}

/**
 * State for reconnection indicator.
 */
data class ReconnectingState(
    val serverName: String,
    val attempt: Int,
    val bufferMs: Long
) {
    val bufferSeconds: Long get() = bufferMs / 1000
}

/**
 * Server status for display in server list.
 */
sealed class ServerStatus {
    object Online : ServerStatus()
    object Offline : ServerStatus()
    data class Connecting(val progress: Float = 0f) : ServerStatus()
    data class Reconnecting(val attempt: Int, val nextRetrySeconds: Int) : ServerStatus()
}

/**
 * Player colors extracted from album artwork.
 */
data class PlayerColors(
    val backgroundColor: Int,
    val accentColor: Int,
    val textColor: Int
)
