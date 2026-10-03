package com.sendspindroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sendspindroid.R
import com.sendspindroid.ui.theme.HomeThemeController
import home.theme.HomeTheme
import home.theme.HomeThemes

/**
 * Fork-only (HW-65): "Follow home theme" or any home theme, plus light/dark. Stored for the MA user
 * when the MA API is signed in (same choice MA web and MA mobile show), else on this device only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeThemePreferences() {
    val effective by HomeThemeController.effective.collectAsStateWithLifecycle()
    val server by HomeThemeController.server.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val claim = server?.claim
    val home = HomeThemes.byId(claim?.theme)
    val follow = stringResource(R.string.home_theme_follow, home.name, modeLabel(claim?.mode ?: "automatic"))
    val current = HomeThemes.byId(effective.theme)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.home_theme_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (server != null && !effective.fromApp) follow else current.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (server != null) R.string.home_theme_summary_ma else R.string.home_theme_summary_local,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Swatch(current)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.home_theme_mode_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            HomeThemeController.MODES.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = effective.mode == mode,
                    onClick = { HomeThemeController.choose(effective.theme, mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, HomeThemeController.MODES.size),
                ) { Text(modeLabel(mode)) }
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.home_theme_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (server != null) {
                        item {
                            ThemeRow(home, follow, selected = !effective.fromApp) {
                                HomeThemeController.choose(null, effective.mode)
                                picking = false
                            }
                        }
                    }
                    items(HomeThemes.all, key = { it.id }) { theme ->
                        val chosen = (effective.fromApp || server == null) && effective.theme == theme.id
                        ThemeRow(theme, theme.name, selected = chosen) {
                            HomeThemeController.choose(theme.id, effective.mode)
                            picking = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ThemeRow(theme: HomeTheme, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Swatch(theme)
    }
}

/** Light background with its primary, then the dark pair. */
@Composable
private fun Swatch(theme: HomeTheme) {
    Row {
        listOf(theme.light.background, theme.light.primary, theme.dark.background, theme.dark.primary).forEach {
            Spacer(
                Modifier
                    .size(14.dp)
                    .background(Color(it), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(
    when (mode) {
        "light" -> R.string.home_theme_mode_light
        "dark" -> R.string.home_theme_mode_dark
        else -> R.string.home_theme_mode_automatic
    },
)
