package com.sendspindroid.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlin.math.abs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment

// Color constants for status indicators
private val ColorGood = Color(0xFF4CAF50)      // Green
private val ColorWarning = Color(0xFFFFC107)   // Yellow/Amber
private val ColorBad = Color(0xFFF44336)       // Red

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsBottomSheet(
    sheetState: SheetState,
    state: StatsState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        StatsContent(state = state)
    }
}

@Composable
fun StatsContent(
    state: StatsState,
    modifier: Modifier = Modifier
) {
    // Use nestedScroll to properly integrate with BottomSheetDialogFragment
    val nestedScrollInterop = rememberNestedScrollInteropConnection()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(nestedScrollInterop)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SyncSummary(state)

        // === SYNC: how far playback is from where the server wants it ===
        SectionHeader(stringResource(R.string.stats_section_sync))
        StatRow(stringResource(R.string.stats_playback), playbackLabel(state.playbackState), playbackColor(state.playbackState))
        StatRow(
            stringResource(R.string.stats_sync_error_smoothed),
            String.format("%+.2f ms", state.smoothedSyncErrorMs),
            if (state.isPlaying) getStatusColor(getSyncErrorStatus(state.smoothedSyncErrorUs)) else null,
        )
        // Single readings jitter by more than the player corrects for; the
        // smoothed value above is what it acts on.
        StatRow(stringResource(R.string.stats_sync_error_raw), String.format("%+.2f ms", state.syncErrorMs))
        StatRow(
            stringResource(R.string.stats_start_aligned),
            yesNo(state.startTimeCalibrated),
            if (state.startTimeCalibrated) ColorGood else ColorWarning,
        )
        if (state.gracePeriodRemainingUs >= 0) {
            StatRow(
                stringResource(R.string.stats_settling),
                String.format("%.1f s", state.gracePeriodRemainingUs / 1_000_000.0),
                ColorWarning,
            )
        }
        StatRow(stringResource(R.string.stats_manual_offset), String.format("%+.0f ms", state.staticDelayMs))
        SectionNote(stringResource(R.string.stats_sync_note))

        SectionDivider()

        // === CLOCK: this device's clock against the server's ===
        SectionHeader(stringResource(R.string.stats_section_clock))
        StatRow(
            stringResource(R.string.stats_clock_synced),
            if (state.clockConverged && state.timeFilterConvergenceMs > 0) {
                stringResource(R.string.stats_clock_synced_after, state.timeFilterConvergenceMs / 1000.0)
            } else {
                yesNo(state.clockConverged)
            },
            if (state.clockConverged) ColorGood else ColorWarning,
        )
        StatRow(stringResource(R.string.stats_clock_drift), String.format("%+.2f ppm", state.clockDriftPpm))
        StatRow(stringResource(R.string.stats_clock_drift_per_hour), String.format("%+.1f ms", state.clockDriftMsPerHour))
        StatRow(
            stringResource(R.string.stats_clock_uncertainty),
            String.format("+/- %.2f ms", state.clockErrorMs),
            getStatusColor(getClockErrorStatus(state.clockErrorUs)),
        )
        StatRow(stringResource(R.string.stats_clock_offset), String.format("%+.2f ms", state.clockOffsetMs))
        StatRow(stringResource(R.string.stats_measurements), state.measurementCount.toString())
        if (state.lastTimeSyncAgeMs >= 0) {
            StatRow(
                stringResource(R.string.stats_last_measurement),
                agoSeconds(state.lastTimeSyncAgeMs),
                getLastSyncColor(state.lastTimeSyncAgeMs),
            )
        }
        SectionNote(stringResource(R.string.stats_clock_note))

        SectionDivider()

        // === CORRECTION: what the player has done to stay in sync ===
        SectionHeader(stringResource(R.string.stats_section_correction))
        StatRow(
            stringResource(R.string.stats_correcting_now),
            when {
                state.dropEveryNFrames > 0 -> stringResource(R.string.stats_correcting_dropping)
                state.insertEveryNFrames > 0 -> stringResource(R.string.stats_correcting_inserting)
                else -> stringResource(R.string.stats_correcting_none)
            },
            if (state.dropEveryNFrames > 0 || state.insertEveryNFrames > 0) ColorWarning else ColorGood,
        )
        StatRow(stringResource(R.string.stats_frames_dropped), framesAndMs(state.framesDropped, state))
        StatRow(stringResource(R.string.stats_frames_inserted), framesAndMs(state.framesInserted, state))
        StatRow(
            stringResource(R.string.stats_reanchors),
            state.reanchorCount.toString(),
            if (state.reanchorCount > 0) ColorWarning else ColorGood,
        )
        StatRow(
            stringResource(R.string.stats_underruns),
            state.bufferUnderrunCount.toString(),
            if (state.bufferUnderrunCount > 0) ColorBad else ColorGood,
        )
        SectionNote(stringResource(R.string.stats_correction_note))

        SectionDivider()

        // === BUFFER ===
        SectionHeader(stringResource(R.string.stats_section_buffer))
        StatRow(
            stringResource(R.string.stats_queued),
            String.format("%.1f s", state.queuedMs / 1000.0),
            if (state.isPlaying) getStatusColor(getBufferStatus(state.queuedMs)) else null,
        )
        StatRow(stringResource(R.string.stats_min_buffer), "${state.minBufferMs} ms")
        StatRow(stringResource(R.string.stats_lead_time), "${state.requiredLeadTimeMs} ms")
        StatRow(
            stringResource(R.string.stats_chunks),
            "${state.chunksReceived} / ${state.chunksPlayed} / ${state.chunksDropped}",
            if (state.chunksDropped > 0) ColorWarning else null,
        )
        StatRow(
            stringResource(R.string.stats_gaps),
            "${state.gapsFilled} (${state.gapSilenceMs} ms)",
            if (state.gapsFilled > 0) ColorWarning else null,
        )
        StatRow(
            stringResource(R.string.stats_overlaps),
            "${state.overlapsTrimmed} (${state.overlapTrimmedMs} ms)",
            if (state.overlapsTrimmed > 0) ColorWarning else null,
        )

        SectionDivider()

        // === PROTOCOL: what was negotiated with the server ===
        SectionHeader(stringResource(R.string.stats_section_protocol))
        StatRow(stringResource(R.string.stats_server), state.serverName ?: "--")
        StatRow(stringResource(R.string.stats_address), state.serverAddress ?: "--")
        StatRow(
            stringResource(R.string.stats_state),
            state.connectionState,
            getStatusColor(getConnectionStatus(state.connectionState)),
        )
        StatRow(
            stringResource(R.string.stats_encryption),
            when (state.pskCategory) {
                "LONG_TERM" -> stringResource(R.string.stats_encryption_paired)
                "PAIRING" -> stringResource(R.string.stats_encryption_pairing)
                "SENTINEL" -> stringResource(R.string.stats_encryption_unpaired)
                else -> "--"
            },
            when (state.pskCategory) {
                "LONG_TERM" -> ColorGood
                null -> null
                else -> ColorWarning
            },
        )
        StatRow(stringResource(R.string.stats_roles), rolesLabel(state.activeRoles))
        StatRow(stringResource(R.string.stats_stream), streamLabel(state))
        if (state.lastByteReceivedAgoMs >= 0) {
            StatRow(
                stringResource(R.string.stats_last_message),
                agoSeconds(state.lastByteReceivedAgoMs),
                getLastSyncColor(state.lastByteReceivedAgoMs),
            )
        }
        if (state.lastDisconnectCode != null || state.lastDisconnectReason != null) {
            val code = state.lastDisconnectCode?.toString() ?: "--"
            val reason = state.lastDisconnectReason?.take(40) ?: ""
            StatRow(
                stringResource(R.string.stats_last_disconnect),
                "$code ${if (reason.isNotEmpty()) "\"$reason\"" else ""}".trim(),
            )
        }

        SectionDivider()

        // === NETWORK ===
        SectionHeader(stringResource(R.string.stats_section_network))
        StatRow(stringResource(R.string.stats_type), state.networkType, getNetworkTypeColor(state.networkType))
        StatRow(stringResource(R.string.stats_quality), state.networkQuality, getNetworkQualityColor(state.networkQuality))
        if (state.isWifi) {
            if (state.wifiRssi != Int.MIN_VALUE) {
                StatRow(stringResource(R.string.stats_wifi_rssi), "${state.wifiRssi} dBm", getWifiRssiColor(state.wifiRssi))
            }
            if (state.wifiSpeed > 0) {
                StatRow(stringResource(R.string.stats_wifi_speed), "${state.wifiSpeed} Mbps")
            }
            if (state.wifiFrequency > 0) {
                StatRow(stringResource(R.string.stats_wifi_band), state.wifiBand,
                    if (state.wifiFrequency >= 5000) ColorGood else ColorWarning)
            }
        }

        // === RECONNECTS: only when there has been one ===
        val episodes = handoffEpisodeLines(state.handoffEpisodes)
        if (episodes.isNotEmpty()) {
            SectionDivider()
            SectionHeader(stringResource(R.string.stats_section_reconnects))
            episodes.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = handoffLineColor(line),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * The one line that answers "is it in sync?": the smoothed error, large, and
 * what the player is doing about it.
 */
@Composable
private fun SyncSummary(state: StatsState) {
    val absError = abs(state.smoothedSyncErrorUs)
    val (label, color) = when {
        state.connectionState != "Connected" -> stringResource(R.string.stats_summary_not_connected) to null
        !state.isPlaying -> playbackLabel(state.playbackState) to null
        state.gracePeriodRemainingUs >= 0 -> stringResource(R.string.stats_summary_settling) to ColorWarning
        absError <= SYNC_DEADBAND_US -> stringResource(R.string.stats_summary_in_sync) to ColorGood
        absError < SYNC_RESYNC_US -> stringResource(R.string.stats_summary_correcting) to ColorGood
        else -> stringResource(R.string.stats_summary_out_of_sync) to ColorBad
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.isPlaying) {
                Text(
                    text = String.format("%+.2f ms", state.smoothedSyncErrorMs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = color ?: MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun playbackLabel(playbackState: String): String = stringResource(
    when (playbackState) {
        "PLAYING" -> R.string.stats_playback_playing
        "WAITING_FOR_START" -> R.string.stats_playback_waiting
        "REANCHORING" -> R.string.stats_playback_reanchoring
        else -> R.string.stats_playback_idle
    }
)

private fun playbackColor(playbackState: String): Color? = when (playbackState) {
    "PLAYING" -> ColorGood
    "WAITING_FOR_START", "REANCHORING" -> ColorWarning
    else -> null
}

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.action_yes else R.string.action_no)

private fun agoSeconds(ageMs: Long): String = String.format("%.1f s ago", ageMs / 1000.0)

/** A frame count with the stretch of audio it amounts to. */
private fun framesAndMs(frames: Long, state: StatsState): String =
    String.format("%,d (%.1f ms)", frames, state.framesToMs(frames))

/** "player, controller, ..." from the versioned role ids the server activated. */
private fun rolesLabel(roles: List<String>): String =
    if (roles.isEmpty()) "--" else roles.joinToString(", ") { it.substringBefore('@') }

/** "OPUS 48 kHz 16-bit stereo", or just the codec while no stream is active. */
private fun streamLabel(state: StatsState): String {
    if (state.streamSampleRate <= 0) return state.audioCodec
    val rate = String.format("%.4g", state.streamSampleRate / 1000.0).trimEnd('0').trimEnd('.')
    val channels = when (state.streamChannels) {
        1 -> "mono"
        2 -> "stereo"
        else -> "${state.streamChannels} ch"
    }
    return "${state.audioCodec} $rate kHz ${state.streamBitDepth}-bit $channels"
}

/** Episode lines from the recorder's summary, dropping its header / empty marker. */
private fun handoffEpisodeLines(summary: String?): List<String> =
    summary?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("---") && !it.startsWith("(no ") }
        ?.toList()
        ?: emptyList()

private fun handoffLineColor(line: String): Color = when {
    line.contains("RECOVERED") -> ColorGood
    line.contains("EXHAUSTED") -> ColorBad
    else -> ColorWarning
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

/** A line under a section saying what its numbers mean. */
@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (valueColor != null) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ============================================================================
// Color Helpers
// ============================================================================

private fun getStatusColor(status: ThresholdStatus): Color {
    return when (status) {
        ThresholdStatus.GOOD -> ColorGood
        ThresholdStatus.WARNING -> ColorWarning
        ThresholdStatus.BAD -> ColorBad
    }
}

private fun getNetworkTypeColor(type: String): Color? {
    return when (type) {
        "WIFI", "ETHERNET" -> ColorGood
        "CELLULAR" -> ColorWarning
        else -> null
    }
}

private fun getNetworkQualityColor(quality: String): Color? {
    return when (quality) {
        "EXCELLENT", "GOOD" -> ColorGood
        "FAIR" -> ColorWarning
        "POOR" -> ColorBad
        else -> null
    }
}

private fun getWifiRssiColor(rssi: Int): Color {
    return when {
        rssi > -50 -> ColorGood
        rssi > -65 -> ColorGood
        rssi > -75 -> ColorWarning
        else -> ColorBad
    }
}

private fun getLastSyncColor(ageMs: Long): Color {
    return when {
        ageMs < 2_000L -> ColorGood
        ageMs < 10_000L -> ColorWarning
        else -> ColorBad
    }
}


// ============================================================================
// Previews
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun StatsContentPreview() {
    SendSpinTheme {
        StatsContent(
            state = StatsState(
                serverName = "Living Room",
                serverAddress = "192.168.1.100:8927",
                connectionState = "Connected",
                audioCodec = "OPUS",
                streamSampleRate = 48000,
                streamBitDepth = 16,
                streamChannels = 2,
                activeRoles = listOf("player@v1", "controller@v1", "metadata@v1", "artwork@v1"),
                pskCategory = "LONG_TERM",
                minBufferMs = 350,
                requiredLeadTimeMs = 650,
                networkType = "WIFI",
                networkQuality = "EXCELLENT",
                wifiRssi = -55,
                wifiSpeed = 866,
                wifiFrequency = 5180,
                playbackState = "PLAYING",
                syncErrorUs = -410,
                smoothedSyncErrorUs = -40,
                startTimeCalibrated = true,
                clockOffsetUs = 5000,
                clockDriftPpm = 2.5,
                clockErrorUs = 300,
                clockConverged = true,
                timeFilterConvergenceMs = 1200,
                measurementCount = 150,
                lastTimeSyncAgeMs = 500,
                framesDropped = 12,
                framesInserted = 3,
                queuedSamples = 1_440_000,
                chunksReceived = 1000,
                chunksPlayed = 998
            )
        )
    }
}
