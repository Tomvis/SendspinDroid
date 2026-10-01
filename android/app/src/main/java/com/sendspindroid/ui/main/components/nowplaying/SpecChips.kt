package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.main.AudioStreamSpec
import com.sendspindroid.ui.theme.NpRubikFamily
import com.sendspindroid.ui.theme.NpTabular
import java.util.Locale

// Dark-theme frosted-glass per spec: rgba(255,255,255,0.08) bg, 0.14 border, 0.88 fg.
private val ChipBg = Color(0xFFFFFFFF).copy(alpha = 0.08f)
private val ChipBorder = Color(0xFFFFFFFF).copy(alpha = 0.14f)
private val ChipFg = Color(0xFFFFFFFF).copy(alpha = 0.88f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpecChips(
    spec: AudioStreamSpec,
    modifier: Modifier = Modifier,
) {
    // buildList's lambda is not composable, so the patterns are resolved here
    // and formatted inside. Formatting goes through Locale.ROOT for the same
    // reason formatSampleRateKhz does -- the config locale would render the
    // numbers in Arabic-Indic digits on some devices.
    val bitDepthFormat = stringResource(R.string.np_tv_spec_bit_depth)
    val sampleRateFormat = stringResource(R.string.np_tv_spec_sample_rate)
    val bitrateFormat = stringResource(R.string.np_tv_spec_bitrate)
    val labels = buildList {
        if (spec.codec.isNotBlank()) add(spec.codec)
        if (spec.bitDepth > 0) add(String.format(Locale.ROOT, bitDepthFormat, spec.bitDepth))
        if (spec.sampleRate > 0) {
            add(String.format(Locale.ROOT, sampleRateFormat, formatSampleRateKhz(spec.sampleRate)))
        }
        if (spec.bitrateKbps > 0) add(String.format(Locale.ROOT, bitrateFormat, spec.bitrateKbps))
    }
    if (labels.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NowPlayingTvTokens.Space.TightGap),
        verticalArrangement = Arrangement.spacedBy(NowPlayingTvTokens.Space.TightGap),
    ) {
        labels.forEach { label ->
            Chip(text = label)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        // Locale.ROOT, not the device locale: the label text is English
        // (codec names, "kHz", "kbps") whatever the device is set to, so
        // uppercasing it under a foreign locale's casing rules is wrong.
        text = text.uppercase(Locale.ROOT),
        fontFamily = NpRubikFamily,
        style = NpTabular,
        fontSize = NowPlayingTvTokens.Type.Label,
        fontWeight = FontWeight.W600,
        letterSpacing = NowPlayingTvTokens.Type.TrackChip,
        color = ChipFg,
        modifier = Modifier
            .clip(RoundedCornerShape(NowPlayingTvTokens.Radius.Pill))
            .background(ChipBg)
            .border(1.dp, ChipBorder, RoundedCornerShape(NowPlayingTvTokens.Radius.Pill))
            .padding(
                horizontal = NowPlayingTvTokens.Dimen.ChipPaddingH,
                vertical = NowPlayingTvTokens.Dimen.ChipPaddingV,
            ),
    )
}

/** The numeric part of the sample-rate chip; the caller appends the "kHz" unit. */
private fun formatSampleRateKhz(sampleRateHz: Int): String {
    val khz = sampleRateHz / 1000f
    // Common rates (44.1, 48, 88.2, 96, 176.4, 192) — show one decimal only when fractional.
    // Locale.ROOT keeps "44.1" stable across locales (no "44,1" in de-DE etc.).
    val isFractional = sampleRateHz % 1000 != 0
    return if (isFractional) String.format(Locale.ROOT, "%.1f", khz) else khz.toInt().toString()
}

