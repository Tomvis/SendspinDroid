package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.main.components.nowplaying.NowPlayingFocus
import com.sendspindroid.ui.main.components.nowplaying.NowPlayingIdleScreen
import com.sendspindroid.ui.main.components.nowplaying.NowPlayingTvTokens
import com.sendspindroid.ui.theme.SendSpinTvTheme

/**
 * Fallback accent used when the VM has not computed an artwork-derived
 * accent yet (or on the idle screen where no artwork exists). Drives
 * progress bar gradient + glow, chip borders, album-art halo, source-badge
 * dot (when paused), idle wordmark + pulsing colons, and ambient wash.
 */
private val FallbackAccent: Color = Color(0xFF8DB0BD) // home dark primary (HW-48)

/**
 * TV Now Playing surface. Deliberately display-only: the screen is driven
 * entirely by the D-pad transport keys handled in AppShell, so it takes no
 * click callbacks and renders no controls.
 */
@Composable
internal fun NowPlayingTv(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    accentColor: Color?,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    audioSpec: AudioStreamSpec? = null,
    connectionState: AppConnectionState? = null,
    modifier: Modifier = Modifier
) {
    val accent = accentColor ?: FallbackAccent
    SendSpinTvTheme {
        // Design is authored at 1920x1080 in pixel units, the canvas named by
        // NowPlayingTvTokens.DesignCanvas. Instead of pinning
        // density to 1.0 (which assumed a 1080p window), scale density so that
        // our dp values map to the "1920x1080 design canvas" regardless of the
        // actual surface size Android hands us. On a 1080p window this
        // resolves to density=1.0 (identical to before). On a 4K window it
        // becomes density=2.0, keeping every element's on-screen proportion
        // identical while taking advantage of the extra pixels. fontScale is
        // preserved from the platform so user accessibility settings apply.
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // Skip rendering until layout has resolved real constraints --
            // BoxWithConstraints can transiently emit maxWidth/maxHeight == 0
            // during configuration changes or before first measurement, and
            // a zero-density CompositionLocalProvider would render every dp
            // value at sub-pixel size for one frame.
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) {
                return@BoxWithConstraints
            }
            val designScale = minOf(
                constraints.maxWidth.toFloat() / NowPlayingTvTokens.DesignCanvas.WidthPx,
                constraints.maxHeight.toFloat() / NowPlayingTvTokens.DesignCanvas.HeightPx,
            )
            val platformFontScale = LocalDensity.current.fontScale
            CompositionLocalProvider(
                LocalDensity provides Density(density = designScale, fontScale = platformFontScale),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (metadata.isEmpty) {
                        NowPlayingIdleScreen(
                            accent = accent,
                            groupLabel = groupName,
                            connectionState = connectionState,
                        )
                    } else {
                        NowPlayingFocus(
                            metadata = metadata,
                            artworkSource = artworkSource,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            positionUpdatedAt = positionUpdatedAt,
                            isPlaying = isPlaying,
                            accent = accent,
                            groupLabel = groupName,
                            audioSpec = audioSpec,
                            isBuffering = isBuffering,
                        )
                    }
                }
            }
        }
    }
}

