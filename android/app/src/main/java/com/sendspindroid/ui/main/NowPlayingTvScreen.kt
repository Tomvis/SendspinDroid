package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Box
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
        // Design is authored in pixels at 1920x1080. On Android TV the platform
        // reports xhdpi (density=2.0) at 1080p, which would double every dp/sp
        // away from the spec. Pin density to 1.0 here so 1dp = 1px = 1sp, keeping
        // the user's fontScale preference intact.
        val platformDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = 1f, fontScale = platformDensity.fontScale),
        ) {
            Box(modifier = modifier.fillMaxSize()) {
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

