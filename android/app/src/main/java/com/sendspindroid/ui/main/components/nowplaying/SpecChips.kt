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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.ui.main.AudioStreamSpec
import com.sendspindroid.ui.theme.NpMonoFamily
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
    val labels = buildList {
        if (spec.codec.isNotBlank()) add(spec.codec)
        if (spec.bitDepth > 0) add("${spec.bitDepth}-BIT")
        if (spec.sampleRate > 0) add(formatSampleRate(spec.sampleRate))
        if (spec.bitrateKbps > 0) add("${spec.bitrateKbps} kbps")
    }
    if (labels.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        labels.forEach { label ->
            Chip(text = label)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        fontFamily = NpMonoFamily,
        fontSize = 18.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 1.8.sp,
        color = ChipFg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ChipBg)
            .border(1.dp, ChipBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

private fun formatSampleRate(sampleRateHz: Int): String {
    val khz = sampleRateHz / 1000f
    // Common rates (44.1, 48, 88.2, 96, 176.4, 192) — show one decimal only when fractional.
    // Locale.ROOT keeps "44.1" stable across locales (no "44,1" in de-DE etc.).
    val isFractional = sampleRateHz % 1000 != 0
    val formatted = if (isFractional) String.format(Locale.ROOT, "%.1f", khz) else khz.toInt().toString()
    return "$formatted kHz"
}

