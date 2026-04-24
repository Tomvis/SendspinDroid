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
import com.sendspindroid.ui.main.components.nowplaying.NowPlayingFocus
import com.sendspindroid.ui.main.components.nowplaying.NowPlayingIdleScreen
import com.sendspindroid.ui.queue.QueueViewModel
import com.sendspindroid.ui.theme.SendSpinTvTheme

/**
 * The Now Playing accent color. Edit this to change the accent across every
 * artwork-independent element: progress bar gradient + glow, chip borders,
 * album-art halo, source-badge dot (when paused), idle wordmark + pulsing
 * colons, ambient background wash. Default is the amber from the design
 * tweak panel (#F5A524).
 */
private val AccentColor: Color = Color(0xFFF5A524)

@Composable
internal fun NowPlayingTv(
    metadata: TrackMetadata,
    groupName: String,
    artworkSource: ArtworkSource?,
    @Suppress("UNUSED_PARAMETER") isBuffering: Boolean,
    isPlaying: Boolean,
    @Suppress("UNUSED_PARAMETER") controlsEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") accentColor: Color?,
    @Suppress("UNUSED_PARAMETER") isMaConnected: Boolean,
    positionMs: Long,
    durationMs: Long,
    positionUpdatedAt: Long = 0L,
    audioSpec: AudioStreamSpec? = null,
    @Suppress("UNUSED_PARAMETER") onPreviousClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPlayPauseClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onFavoriteClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") queueViewModel: QueueViewModel?,
    @Suppress("UNUSED_PARAMETER") onBrowseLibrary: () -> Unit,
    @Suppress("UNUSED_PARAMETER") showPlayerButton: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onPlayerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = AccentColor
    SendSpinTvTheme {
        // Design is authored at 1920x1080 in pixel units. Instead of pinning
        // density to 1.0 (which assumed a 1080p window), scale density so that
        // our dp values map to the "1920x1080 design canvas" regardless of the
        // actual surface size Android hands us. On a 1080p window this
        // resolves to density=1.0 (identical to before). On a 4K window it
        // becomes density=2.0, keeping every element's on-screen proportion
        // identical while taking advantage of the extra pixels. fontScale is
        // preserved from the platform so user accessibility settings apply.
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val designScale = minOf(
                constraints.maxWidth.toFloat() / 1920f,
                constraints.maxHeight.toFloat() / 1080f,
            ).coerceAtLeast(0.1f)
            val platformFontScale = LocalDensity.current.fontScale
            CompositionLocalProvider(
                LocalDensity provides Density(density = designScale, fontScale = platformFontScale),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (metadata.isEmpty) {
                        NowPlayingIdleScreen(accent = accent, groupLabel = groupName)
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
                            onSourceBadgeClick = onSwitchGroupClick,
                        )
                    }
                }
            }
        }
    }
}

