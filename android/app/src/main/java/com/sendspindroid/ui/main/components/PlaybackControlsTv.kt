package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import com.sendspindroid.R

/**
 * TV-native transport controls using androidx.tv.material3.IconButton.
 * Built-in focus glow + scale from the library replace the hand-rolled
 * .tvFocusable() used by the Material3 [PlaybackControls] variant.
 *
 * Scope is narrower than [PlaybackControls]: the TV Now Playing surface
 * renders its own secondary row, so this composable only provides the
 * primary prev / play / next transport row.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaybackControlsTv(
    isPlaying: Boolean,
    isEnabled: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    playButtonSize: Dp,
    controlButtonSize: Dp,
    buttonGap: Dp,
    playFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val playIconSize = playButtonSize * 0.67f
    val controlIconSize = controlButtonSize * 0.5f

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPreviousClick,
            enabled = isEnabled,
            modifier = Modifier.size(controlButtonSize),
            colors = IconButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.accessibility_previous_button),
                modifier = Modifier.size(controlIconSize)
            )
        }

        Spacer(modifier = Modifier.width(buttonGap))

        IconButton(
            onClick = onPlayPauseClick,
            enabled = isEnabled,
            modifier = Modifier
                .size(playButtonSize)
                .focusRequester(playFocusRequester),
            colors = IconButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = stringResource(
                    if (isPlaying) R.string.accessibility_pause_button
                    else R.string.accessibility_play_button
                ),
                modifier = Modifier.size(playIconSize)
            )
        }

        Spacer(modifier = Modifier.width(buttonGap))

        IconButton(
            onClick = onNextClick,
            enabled = isEnabled,
            modifier = Modifier.size(controlButtonSize),
            colors = IconButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.accessibility_next_button),
                modifier = Modifier.size(controlIconSize)
            )
        }
    }
}
