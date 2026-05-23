package com.sendspindroid.conformance

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * JSON shape written to the harness's `--summary` file at the end of a run.
 *
 * The harness validates fixed fields (handshake state, chunk counts, errors)
 * and the scenario-specific echo fields (scenarioId, preferredCodec).
 *
 * Field names are snake_case to match the Python harness's expected keys.
 */
@JsonClass(generateAdapter = true)
data class Summary(
    val scenarioId: String,
    val preferredCodec: String,
    val handshakeComplete: Boolean,
    val serverName: String?,
    val serverId: String?,
    val negotiatedCodec: String?,
    val negotiatedSampleRate: Int?,
    val negotiatedChannels: Int?,
    val negotiatedBitDepth: Int?,
    val clockMeasurementsReceived: Int,
    val clockConverged: Boolean,
    val lastClockOffsetUs: Long?,
    val lastClockErrorUs: Long?,
    val lastClockDriftPpm: Double?,
    val audioChunksReceived: Int,
    val audioBytesReceived: Long,
    val artworkChunksReceived: Int,
    val metadataUpdates: Int,
    val lastTitle: String?,
    val lastArtist: String?,
    val lastPlaybackState: String?,
    val controllerUpdates: Int,
    val lastControllerVolume: Int?,
    val lastControllerRepeat: String?,
    val lastControllerShuffle: Boolean?,
    val colorUpdates: Int,
    val streamStarts: Int,
    val streamEnds: Int,
    val streamClears: Int,
    val durationMs: Long,
    val errors: List<String>,
) {
    fun toJson(): String = adapter.indent("  ").toJson(this)

    companion object {
        private val moshi: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        private val adapter = moshi.adapter(Summary::class.java)
    }
}
