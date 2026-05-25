package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
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
    @Suppress("UNUSED_PARAMETER") playbackState: PlaybackState,
    accent: Color,
    groupLabel: String,
    audioSpec: AudioStreamSpec?,
    isBuffering: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Paused-state machine.
    //
    // Commit to the paused look after a 150 ms debounce off isPlaying. The
    // old 800 ms debounce existed to ride out the ~200-500 ms isPlaying
    // dropouts that Media3 emits when SendSpin sends stream/end before
    // stream/start during a skip -- but that meant the art / glow /
    // ambient wash didn't begin reacting to a real pause press for nearly
    // a full second. With `effectivePaused` below now gating the dim
    // treatments on `!isBuffering`, the longer skip transitions are
    // suppressed by the buffering signal instead of by an outright
    // visual-state delay. 150 ms is long enough to filter sub-frame
    // jitter and brief stream/end -> stream/start gaps that resolve
    // before positionMs has a chance to reset (which is what activates
    // the buffering gate), and short enough that a real pause begins
    // animating before the 500 ms tween finishes.
    var paused by remember { mutableStateOf(!isPlaying) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            paused = false
        } else {
            delay(150)
            paused = true
        }
    }

    // The badge correctly labels three states (Buffering > Paused > Now
    // Playing), but the art / glow / ambient wash shouldn't dim during a
    // track-start load just because isPlaying happens to be false. Gate the
    // dim treatments on (paused && !isBuffering) so they only fire on a
    // real user-initiated pause.
    val effectivePaused = paused && !isBuffering

    // Accent tween + quantization.
    //
    // animateColorAsState gives the smooth 250ms colour transition across the
    // halo + wash + progress-fill brushes (matches the 100ms art crossfade
    // with a small tail). Naively, the raw tween emits a fresh Color every
    // frame, and the downstream `remember(accent, ...)` brush caches re-key
    // 15x per track change -- each one a fresh ShaderBrush + backing Shader.
    // On the Shield Tegra that showed up as GC pressure during exactly the
    // moment the art crossfade also fires.
    //
    // Quantizing the tween output to a coarse RGB grid stabilises the cache
    // key across most frames: a 250ms tween between two arbitrary accents
    // typically traverses 4-8 distinct quantized values total. Each brush
    // site then re-allocates 4-8 times per track change instead of 15, and
    // the children skip recomposition entirely between quantization steps
    // thanks to derivedStateOf's structural-equality emit.
    val rawAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "np-accent",
    )
    val animatedAccent by remember {
        derivedStateOf { rawAccent.quantizeAccent() }
    }

    // Sticky queue position. server/state and the track-info frame arrive on
    // separate WebSocket messages, so on a fresh play we can momentarily
    // have a real title with queueTrack=0 / totalTracks=0. The bottom-right
    // "X OF Y" label would pop in a beat after the rest of the screen.
    //
    // Hold the last good pair and reset only when we're confident the new
    // track really has no queue context: wait 1.5 s after seeing zeros, and
    // only commit the clear if the title is also empty (genuine end of
    // playback). Hold the previous numbers across track changes too -- if a
    // new track has no queue context, the 1.5 s debounce below clears them;
    // otherwise the next non-zero queueTrack/totalTracks pair updates in
    // place without a visible blank.
    var stickyTrackNumber by remember { mutableIntStateOf(metadata.queueTrack) }
    var stickyTrackTotal by remember { mutableIntStateOf(metadata.totalTracks) }
    LaunchedEffect(metadata.queueTrack, metadata.totalTracks, metadata.title) {
        if (metadata.queueTrack > 0 && metadata.totalTracks > 0) {
            stickyTrackNumber = metadata.queueTrack
            stickyTrackTotal = metadata.totalTracks
        } else if (metadata.title.isNotBlank()) {
            // Title is present but queue position isn't yet. Hold the
            // previous numbers briefly; if the server still hasn't pushed
            // them after 1.5 s, this track genuinely has no queue context
            // and we clear.
            delay(1500)
            if (metadata.queueTrack == 0 && metadata.totalTracks == 0) {
                stickyTrackNumber = 0
                stickyTrackTotal = 0
            }
        }
    }

    // Now Playing is a passive view; no interactive elements on-screen. Park
    // initial focus on an invisible anchor so the Activity still gets D-pad
    // key events (e.g. BACK).
    val focusAnchor = remember { FocusRequester() }
    TvInitialFocus(focusAnchor)

    // Black backdrop on the root so any uncovered gap during a track-change
    // recomposition reads as black rather than as the Scaffold background
    // (which on a light system theme would flash white). AmbientBg's
    // BlurredCover holds its own held-bitmap underlay; this is belt and
    // suspenders for the rest of the surface.
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // AmbientBg deliberately full-bleed (outside overscanSafe) so the
        // blurred-cover wash extends to the actual screen edge; nothing
        // critical sits there.
        AmbientBg(artworkSource = artworkSource, accent = animatedAccent, paused = effectivePaused)

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
                    isBuffering = isBuffering,
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
                    accent = animatedAccent,
                    paused = effectivePaused,
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
                trackNumber = stickyTrackNumber,
                trackTotal = stickyTrackTotal,
                accent = animatedAccent,
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

        // 620 dp card. The dark backdrop sits behind the image so that:
        //  - while artwork is loading, the user sees a calm dark surface
        //    that already picks up the current accent rather than a stark
        //    light-gray square that fights the rest of the screen,
        //  - after artwork lands, the AsyncImage paints opaquely over it
        //    and the backdrop is hidden,
        //  - the elevation shadow stays a property of the whole card.
        Box(
            modifier = Modifier
                .size(620.dp)
                .shadow(
                    elevation = 60.dp,
                    shape = RoundedCornerShape(8.dp),
                    clip = false,
                )
                .clip(RoundedCornerShape(8.dp)),
        ) {
            DarkAlbumBackdrop(accent = accent, modifier = Modifier.fillMaxSize())
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha },
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.placeholder_album_simple_dark),
                error = painterResource(R.drawable.placeholder_album_simple_dark),
                fallback = painterResource(R.drawable.placeholder_album_simple_dark),
            )
        }
    }
}

/**
 * Calm dark surface used when there's no artwork to show (track lacks cover,
 * load failed, or queue is between tracks). Renders the same ambient
 * vocabulary as the rest of the focus screen: a near-black base with a
 * soft accent radial offset toward the top-left, a faint hairline border,
 * and the placeholder music note from R.drawable.placeholder_album_simple_dark
 * layered at low alpha so the slot still reads as "album art".
 *
 * Uses Modifier.background for both the base and the accent wash because
 * the Shield Tegra renderer drops Canvas-shader brushes (see AmbientBg.kt
 * for the long version).
 */
@Composable
private fun DarkAlbumBackdrop(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val washBrush = remember(accent, w, h) {
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(w * 0.28f, h * 0.30f),
                radius = maxOf(w, h) * 0.85f,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0810)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(washBrush),
        )
        // Cream hairline so the card still reads as a physical object when
        // the dark backdrop is showing. 6% alpha matches the cover's own
        // box-shadow inner stroke from the design spec.
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            drawRoundRect(
                color = Color(0xFFFAF6F0).copy(alpha = 0.06f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
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

/**
 * Snap each RGB channel to the nearest of [steps] levels. 24 steps per channel
 * yields ~10.6 units of separation on a 0-255 scale -- below the just-
 * noticeable difference for radial gradients with alpha < 0.3 and the
 * [accent, White] horizontal gradient on the progress fill, where the gradient
 * itself smears far more colour than this quantization removes.
 *
 * Used to stabilise the cache key of the brush `remember(accent, ...)` slots
 * downstream of the 250ms animateColorAsState tween in NowPlayingFocus. Alpha
 * is preserved verbatim (the brush sites apply their own alpha at construction
 * via accent.copy(alpha = ...), so quantizing it here would break those).
 */
private fun Color.quantizeAccent(steps: Int = 24): Color {
    val s = steps.coerceAtLeast(2)
    val denom = (s - 1).toFloat()
    val r = ((red * denom + 0.5f).toInt().coerceIn(0, s - 1)) / denom
    val g = ((green * denom + 0.5f).toInt().coerceIn(0, s - 1)) / denom
    val b = ((blue * denom + 0.5f).toInt().coerceIn(0, s - 1)) / denom
    return Color(red = r, green = g, blue = b, alpha = alpha)
}
