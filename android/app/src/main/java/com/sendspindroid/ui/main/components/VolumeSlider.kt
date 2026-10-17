package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.LocalServerColors
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Volume slider with volume icons on either side.
 * Supports custom accent color for dynamic theming based on album art.
 *
 * With [onMuteToggle], the left icon is a mute button. While [muted] it shows
 * the muted speaker, the label says so and the slider's track is greyed; the
 * slider still sets the volume, which mute leaves alone.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color? = null,
    muted: Boolean = false,
    onMuteToggle: (() -> Unit)? = null
) {
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    val activeTrackColor = if (muted) MaterialTheme.colorScheme.outline else accent
    val thumbColor = if (muted) MaterialTheme.colorScheme.outline else accent
    // Dimmed, but not on a server's palette, whose contrast holds only opaque
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = if (LocalServerColors.current) 1f else 0.7f)
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    // The label lines up with the start of the slider
    val leadingWidth = if (onMuteToggle != null) MUTE_BUTTON_SIZE + 4.dp else 20.dp + 12.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        // Label
        Text(
            text = stringResource(if (muted) R.string.device_volume_muted else R.string.device_volume),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = leadingWidth, bottom = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onMuteToggle != null) {
                IconToggleButton(
                    checked = muted,
                    onCheckedChange = { onMuteToggle() },
                    enabled = enabled,
                    modifier = Modifier.size(MUTE_BUTTON_SIZE)
                ) {
                    Icon(
                        painter = painterResource(
                            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_down
                        ),
                        contentDescription = stringResource(R.string.accessibility_mute_button),
                        modifier = Modifier.size(20.dp),
                        tint = if (muted) accent else labelColor
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))
            } else {
                // Volume Down Icon
                Icon(
                    painter = painterResource(R.drawable.ic_volume_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = labelColor
                )

                Spacer(modifier = Modifier.width(12.dp))
            }

            // Volume Slider
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                enabled = enabled,
                modifier = Modifier.weight(1f).upDownMovesFocus(focusManager, view),
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = thumbColor,
                    activeTrackColor = activeTrackColor,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Volume Up Icon
            Icon(
                painter = painterResource(R.drawable.ic_volume_up),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = labelColor
            )
        }
    }
}

private val MUTE_BUTTON_SIZE = 40.dp

@Preview(showBackground = true)
@Composable
private fun VolumeSliderMutedPreview() {
    SendSpinTheme {
        VolumeSlider(
            volume = 0.6f,
            onVolumeChange = {},
            muted = true,
            onMuteToggle = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderPreview() {
    SendSpinTheme {
        var volume by remember { mutableFloatStateOf(0.75f) }
        VolumeSlider(
            volume = volume,
            onVolumeChange = { volume = it }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderCustomColorPreview() {
    SendSpinTheme {
        var volume by remember { mutableFloatStateOf(0.5f) }
        VolumeSlider(
            volume = volume,
            onVolumeChange = { volume = it },
            accentColor = Color(0xFF4CAF50)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VolumeSliderDisabledPreview() {
    SendSpinTheme {
        VolumeSlider(
            volume = 0.75f,
            onVolumeChange = {},
            enabled = false
        )
    }
}
