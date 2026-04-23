package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.ui.theme.NpInterFamily
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private val StatusGreen = Color(0xFF7EE07E)
private val StatusAmber = Color(0xFFF5A524)
private val ChromeFg = Color(0xFFFFFFFF).copy(alpha = 0.70f)
private val ChromeFgFaint = Color(0xFFFFFFFF).copy(alpha = 0.55f)

@Composable
fun SourceBadge(
    paused: Boolean,
    groupLabel: String,
    modifier: Modifier = Modifier,
) {
    val dotColor by animateColorAsState(
        targetValue = if (paused) StatusAmber else StatusGreen,
        animationSpec = tween(durationMillis = 500),
        label = "np-dot-color",
    )
    val label = buildString {
        append(if (paused) "Paused" else "Now Playing")
        if (groupLabel.isNotBlank()) {
            append(" · ")
            append(groupLabel)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .drawBehind {
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(dotColor.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f,
                    )
                    drawCircle(brush = glowBrush, radius = size.minDimension / 2f)
                    drawCircle(color = dotColor, radius = 4.dp.toPx())
                },
        )
        Text(
            text = label.uppercase(Locale.getDefault()),
            fontFamily = NpInterFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.5.sp,
            color = ChromeFg,
        )
    }
}

@Composable
fun NowPlayingClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = Calendar.getInstance()
        }
    }
    val hour = now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = now.get(Calendar.MINUTE).toString().padStart(2, '0')
    val weekday = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()).orEmpty()
    val month = now.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()).orEmpty()
    val day = now.get(Calendar.DAY_OF_MONTH)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "$hour:$minute",
            fontFamily = NpInterFamily,
            fontSize = 42.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = (-0.5).sp,
            lineHeight = 42.sp,
            color = ChromeFg,
            textAlign = TextAlign.End,
        )
        Text(
            text = "$weekday · $month $day".uppercase(Locale.getDefault()),
            modifier = Modifier.padding(top = 8.dp),
            fontFamily = NpInterFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 2.sp,
            color = ChromeFgFaint,
            textAlign = TextAlign.End,
        )
    }
}
