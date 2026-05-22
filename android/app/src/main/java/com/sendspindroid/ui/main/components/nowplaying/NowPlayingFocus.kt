package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.TvInitialFocus
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.main.AudioStreamSpec
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.theme.NpFrauncesFamily
import com.sendspindroid.ui.theme.NpInterFamily
import java.util.Locale

private val FocusFg = Color(0xFFFAF6F0)
private val FocusFgDim = Color(0xFFFAF6F0).copy(alpha = 0.60f)

@Composable
fun NowPlayingFocus(
    metadata: TrackMetadata,
    artworkSource: ArtworkSource?,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long,
    isPlaying: Boolean,
    playbackState: PlaybackState,
    accent: Color,
    groupLabel: String,
    audioSpec: AudioStreamSpec?,
    modifier: Modifier = Modifier,
) {
    // Treat !isPlaying as the user-paused look only when the player is
    // actually in READY. STATE_BUFFERING during a SendSpin track transition
    // flips Media3's isPlaying false for a few hundred ms; without the
    // state guard, the album art / ambient / source badge run their full
    // paused-state animations and snap back, producing the visible
    // shrink-and-return on every track change.
    val paused = !isPlaying && playbackState == PlaybackState.READY
    // Now Playing is a passive view; no interactive elements on-screen. Park
    // initial focus on an invisible anchor so the Activity still gets D-pad
    // key events (e.g. BACK).
    val focusAnchor = remember { FocusRequester() }
    TvInitialFocus(focusAnchor)

    Box(modifier = modifier.fillMaxSize()) {
        // AmbientBg deliberately full-bleed (outside overscanSafe) so the
        // blurred-cover wash extends to the actual screen edge; nothing
        // critical sits there.
        AmbientBg(artworkSource = artworkSource, accent = accent, paused = paused)

        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusAnchor)
                .focusable(),
        )

        // Foreground stack rides inside the overscan-safe inset. The design's
        // spec margins (54dp top/bottom, 96dp sides) are split: 48dp comes from
        // overscanSafe so we comply with the fork policy explicitly, and the
        // remainder lives in the per-Row padding below. Net visual margin from
        // the screen edge is unchanged.
        Box(modifier = Modifier.fillMaxSize().overscanSafe()) {
            // Top chrome: total top=54dp (48 overscan + 6 internal), sides=96dp.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                SourceBadge(
                    paused = paused,
                    groupLabel = groupLabel,
                )
                NowPlayingClock()
            }

            // Center content: art (620dp) + 88dp gap + info column. Sides=96dp.
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    artworkSource = artworkSource,
                    accent = accent,
                    paused = paused,
                )
                Spacer(modifier = Modifier.width(88.dp))
                InfoColumn(
                    metadata = metadata,
                    audioSpec = audioSpec,
                    modifier = Modifier.weight(1f),
                )
            }

            // Progress rail: total bottom=54dp (48 overscan + 6 internal), sides=96dp.
            ProgressRail(
                positionMs = positionMs,
                durationMs = durationMs,
                positionUpdatedAt = positionUpdatedAt,
                isPlaying = isPlaying,
                trackNumber = metadata.queueTrack,
                trackTotal = metadata.totalTracks,
                accent = accent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun AlbumArt(
    artworkSource: ArtworkSource?,
    accent: Color,
    paused: Boolean,
) {
    val scale by animateFloatAsState(
        targetValue = if (paused) 0.98f else 1f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "np-art-scale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (paused) 0.13f else 0.33f,
        animationSpec = tween(500),
        label = "np-glow-alpha",
    )
    val imageAlpha by animateFloatAsState(
        targetValue = if (paused) 0.92f else 1f,
        animationSpec = tween(500),
        label = "np-art-alpha",
    )

    Box(
        modifier = Modifier
            .size(620.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        // Accent glow extending ~40dp outside the image bounds (spec: inset -40).
        // Migrated from drawBehind { drawRect(brush=...) } because the Shield
        // Tegra renderer drops Canvas-shader brushes and paints black.
        // BoxWithConstraints reads the actual layout size so the brush radius
        // can be derived once and applied via Modifier.background. The 700dp
        // size matches the original (620 + 40*2) and centers in the parent so
        // the glow overhangs the image by 40dp on each side.
        BoxWithConstraints(modifier = Modifier.size(700.dp)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            // Keyed on accent + size only; the per-frame glowAlpha tween is
            // applied via graphicsLayer so the gradient's backing Shader
            // isn't re-allocated on every animation tick. The visual result
            // is equivalent (lerp(c.copy(alpha=a), transparent, t) ==
            // alpha*lerp(c, transparent, t)).
            val glowBrush = remember(accent, w, h) {
                Brush.radialGradient(
                    colors = listOf(accent, Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.55f),
                    radius = w * 0.5f * 0.9f,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glowAlpha }
                    .background(glowBrush)
            )
        }

        val model = rememberCrossfadingArtworkRequest(
            artworkSource = artworkSource,
            namespace = "np-focus",
        )

        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .size(620.dp)
                .shadow(
                    elevation = 60.dp,
                    shape = RoundedCornerShape(8.dp),
                    clip = false,
                )
                .graphicsLayer { alpha = imageAlpha }
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.placeholder_album_simple),
            error = painterResource(R.drawable.placeholder_album_simple),
            fallback = painterResource(R.drawable.placeholder_album_simple),
        )
    }
}

@Composable
private fun InfoColumn(
    metadata: TrackMetadata,
    audioSpec: AudioStreamSpec?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val slug = buildString {
            if (metadata.albumTrack > 0) {
                append("Track ")
                append(metadata.albumTrack.toString().padStart(2, '0'))
            }
            if (metadata.album.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("From the Album")
            }
        }
        if (slug.isNotEmpty()) {
            Text(
                text = slug.uppercase(Locale.getDefault()),
                fontFamily = NpInterFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 4.sp,
                color = FocusFgDim,
            )
            Spacer(modifier = Modifier.height(28.dp))
        }

        Text(
            text = metadata.title,
            fontFamily = NpFrauncesFamily,
            fontSize = 88.sp,
            fontWeight = FontWeight.W500,
            lineHeight = 84.sp,
            letterSpacing = (-2.5).sp,
            color = FocusFg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (metadata.artist.isNotBlank()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = metadata.artist,
                fontFamily = NpInterFamily,
                fontSize = 32.sp,
                fontWeight = FontWeight.W500,
                letterSpacing = (-0.4).sp,
                color = FocusFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val albumLine = when {
            metadata.album.isNotBlank() && metadata.year > 0 -> "${metadata.album} · ${metadata.year}"
            metadata.album.isNotBlank() -> metadata.album
            else -> ""
        }
        if (albumLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = albumLine,
                fontFamily = NpInterFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = (-0.2).sp,
                color = FocusFgDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }

        if (audioSpec != null) {
            Spacer(modifier = Modifier.height(56.dp))
            SpecChips(spec = audioSpec)
        }
    }
}
