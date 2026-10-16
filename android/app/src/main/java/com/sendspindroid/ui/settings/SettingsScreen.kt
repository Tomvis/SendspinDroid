package com.sendspindroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.sendspindroid.R
import com.sendspindroid.UserSettings
import com.sendspindroid.diagnostics.Telemetry
import com.sendspindroid.logging.LogLevel
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvInitialFocus
import com.sendspindroid.ui.adaptive.TvKeyboardGate
import com.sendspindroid.ui.adaptive.rememberTvKeyboardGate
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.adaptive.TvOverscanVertical
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.theme.SendSpinTheme
import androidx.compose.ui.text.font.FontFamily
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

/**
 * Settings screen composable with all app preferences.
 *
 * @param viewModel SettingsViewModel for state management
 * @param onNavigateBack Called when back navigation is triggered
 * @param onExportLogs Called when export logs is clicked
 * @param onReportProblem Called when "Report a problem" is clicked
 * @param onRestartApp Called when app restart is confirmed
 * @param canShare False when nothing on the device can receive a share, which
 *   hides "Report a problem" and "Export Debug Logs"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onExportLogs: () -> Unit,
    onReportProblem: () -> Unit,
    onRestartApp: () -> Unit,
    canShare: Boolean
) {
    val playerName by viewModel.playerName.collectAsStateWithLifecycle()
    val dynamicPairingCodeEnabled by viewModel.dynamicPairingCodeEnabled.collectAsStateWithLifecycle()
    val pairedServers by viewModel.pairedServers.collectAsStateWithLifecycle()
    val lastPairingOutcome by viewModel.lastPairingOutcome.collectAsStateWithLifecycle()
    val fullscreenMode by viewModel.fullscreenMode.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val layoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val syncOffset by viewModel.syncOffset.collectAsStateWithLifecycle()
    val preferredCodec by viewModel.preferredCodec.collectAsStateWithLifecycle()
    val supportedCodecs by viewModel.supportedCodecs.collectAsStateWithLifecycle()
    val lowMemoryMode by viewModel.lowMemoryMode.collectAsStateWithLifecycle()
    val highPowerMode by viewModel.highPowerMode.collectAsStateWithLifecycle()
    val autoStartOnBoot by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()
    val hasDefaultServer by viewModel.hasDefaultServer.collectAsStateWithLifecycle()
    val defaultServerName by viewModel.defaultServerName.collectAsStateWithLifecycle()
    val batteryOptExempt by viewModel.batteryOptExempt.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logFileStats by viewModel.logFileStats.collectAsStateWithLifecycle()
    val appVersion by viewModel.appVersion.collectAsStateWithLifecycle()

    var showPlayerNameDialog by remember { mutableStateOf(false) }
    var showSyncOffsetDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }

    val isTv = LocalFormFactor.current == FormFactor.TV
    val playerNameFocusRequester = remember { FocusRequester() }
    val dynamicPairingFocusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // A remote has its own Back button, and an arrow that
                    // takes focus first is one reflex OK away from leaving.
                    if (!isTv) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.wizard_back)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = if (isTv) Modifier.padding(top = TvOverscanVertical).tvColumn() else Modifier
            )
        }
    ) { paddingValues ->
        // Here, not above the Scaffold: its content is composed during layout,
        // after an effect outside it would already have run.
        TvInitialFocus(playerNameFocusRequester)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .then(if (isTv) Modifier.padding(bottom = TvOverscanVertical).tvColumn() else Modifier)
                .verticalScroll(rememberScrollState())
        ) {
            // Player Category
            PreferenceCategory(title = stringResource(R.string.pref_category_player))
            TextPreference(
                title = stringResource(R.string.pref_player_name_title),
                summary = playerName.ifEmpty { stringResource(R.string.pref_player_name_summary) },
                onClick = { showPlayerNameDialog = true },
                modifier = Modifier.focusRequester(playerNameFocusRequester)
            )

            // Pairing Category
            PreferenceCategory(title = stringResource(R.string.pairing_category))
            PairingTokenPreference(token = viewModel.pairingToken())
            PairingClientId(clientId = viewModel.clientId)
            PairedServersSection(
                servers = pairedServers,
                lastOutcome = lastPairingOutcome,
                onForget = { viewModel.forgetPairedServer(it.pskId) },
                afterListFocusRequester = dynamicPairingFocusRequester
            )
            SwitchPreference(
                title = stringResource(R.string.pref_dynamic_pairing_code_title),
                summary = stringResource(R.string.pref_dynamic_pairing_code_summary),
                checked = dynamicPairingCodeEnabled,
                onCheckedChange = { viewModel.setDynamicPairingCodeEnabled(it) },
                modifier = Modifier.focusRequester(dynamicPairingFocusRequester)
            )

            // Display Category
            PreferenceCategory(title = stringResource(R.string.pref_category_display))
            // A TV has no system bars to hide and ignores the Head Unit
            // layout, so neither setting is offered there.
            if (!isTv) {
                SwitchPreference(
                    title = stringResource(R.string.pref_full_screen_title),
                    summary = stringResource(R.string.pref_full_screen_summary),
                    checked = fullscreenMode,
                    onCheckedChange = { viewModel.setFullscreenMode(it) }
                )
            }
            SwitchPreference(
                title = stringResource(R.string.pref_keep_screen_on_title),
                summary = stringResource(R.string.pref_keep_screen_on_summary),
                checked = keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) }
            )
            if (!isTv) {
                SegmentedButtonPreference(
                    title = stringResource(R.string.pref_layout_mode_title),
                    summary = stringResource(R.string.pref_layout_mode_summary),
                    options = listOf(
                        stringResource(R.string.pref_layout_mode_auto) to UserSettings.LayoutMode.AUTO,
                        stringResource(R.string.pref_layout_mode_headunit) to UserSettings.LayoutMode.HEADUNIT
                    ),
                    selectedOption = layoutMode,
                    onOptionSelected = { viewModel.setLayoutMode(it) }
                )
            }
            HomeThemePreferences() // Fork (HW-65), above upstream's light/dark row
            SegmentedButtonPreference(
                title = stringResource(R.string.pref_theme_mode_title),
                summary = stringResource(R.string.pref_theme_mode_summary),
                options = listOf(
                    stringResource(R.string.pref_theme_mode_system) to UserSettings.ThemeMode.SYSTEM,
                    stringResource(R.string.pref_theme_mode_light) to UserSettings.ThemeMode.LIGHT,
                    stringResource(R.string.pref_theme_mode_dark) to UserSettings.ThemeMode.DARK
                ),
                selectedOption = themeMode,
                onOptionSelected = { viewModel.setThemeMode(it) }
            )

            // Audio Category
            PreferenceCategory(title = stringResource(R.string.pref_category_audio))
            SyncOffsetPreference(
                offset = syncOffset,
                onDecrease = { viewModel.decreaseSyncOffset() },
                onIncrease = { viewModel.increaseSyncOffset() },
                onValueClick = { showSyncOffsetDialog = true }
            )
            CodecPreference(
                title = stringResource(R.string.pref_codec_title),
                summary = getCodecDisplayName(preferredCodec),
                onClick = { showCodecDialog = true }
            )

            // Performance Category
            PreferenceCategory(title = stringResource(R.string.pref_category_performance))
            SwitchPreference(
                title = stringResource(R.string.pref_low_memory_title),
                summary = stringResource(R.string.pref_low_memory_summary),
                checked = lowMemoryMode,
                onCheckedChange = {
                    if (viewModel.setLowMemoryMode(it)) {
                        showRestartDialog = true
                    }
                }
            )
            SwitchPreference(
                title = stringResource(R.string.pref_high_power_mode_title),
                summary = stringResource(R.string.pref_high_power_mode_summary),
                checked = highPowerMode,
                onCheckedChange = { viewModel.setHighPowerMode(it) }
            )
            if (highPowerMode) {
                val context = LocalContext.current
                TextPreference(
                    title = if (batteryOptExempt) {
                        stringResource(R.string.pref_battery_opt_exempt)
                    } else {
                        stringResource(R.string.pref_battery_opt_not_exempt)
                    },
                    summary = "",
                    enabled = !batteryOptExempt,
                    onClick = {
                        openBatteryOptimizationSettings(context)
                        // Refresh status when user returns
                        viewModel.refreshBatteryOptExempt()
                    }
                )
            }
            // Waiting for a server needs no default server to start for;
            // searching connects to the default one.
            val waitsForServer = !UserSettings.searchForServers
            SwitchPreference(
                title = stringResource(R.string.pref_auto_start_title),
                summary = if (waitsForServer) {
                    stringResource(
                        if (autoStartOnBoot) R.string.pref_auto_start_summary_waiting_on
                        else R.string.pref_auto_start_summary_waiting_off
                    )
                } else if (!hasDefaultServer) {
                    stringResource(R.string.pref_auto_start_no_default)
                } else if (autoStartOnBoot) {
                    stringResource(R.string.pref_auto_start_summary_on, defaultServerName)
                } else {
                    stringResource(R.string.pref_auto_start_summary_off)
                },
                checked = autoStartOnBoot,
                onCheckedChange = {
                    if (waitsForServer || hasDefaultServer) {
                        viewModel.setAutoStartOnBoot(it)
                    }
                }
            )

            // Debug Category
            PreferenceCategory(title = stringResource(R.string.pref_category_debug))

            // Anonymous, opt-in telemetry (off by default).
            var telemetryEnabled by remember { mutableStateOf(Telemetry.isEnabled()) }
            SwitchPreference(
                title = stringResource(R.string.pref_telemetry_title),
                summary = stringResource(R.string.pref_telemetry_summary),
                checked = telemetryEnabled,
                onCheckedChange = {
                    telemetryEnabled = it
                    Telemetry.setEnabled(it)
                }
            )
            if (telemetryEnabled) {
                TextPreference(
                    title = stringResource(R.string.pref_telemetry_reset_title),
                    summary = stringResource(R.string.pref_telemetry_reset_summary),
                    onClick = { Telemetry.resetInstallId() }
                )
            }

            // Log level selector (6-option segmented row)
            val levels = listOf(
                LogLevel.OFF to R.string.log_level_off,
                LogLevel.ERROR to R.string.log_level_error,
                LogLevel.WARN to R.string.log_level_warn,
                LogLevel.INFO to R.string.log_level_info,
                LogLevel.DEBUG to R.string.log_level_debug,
                LogLevel.VERBOSE to R.string.log_level_verbose,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.pref_log_level_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    levels.forEachIndexed { index, (lvl, labelRes) ->
                        SegmentedButton(
                            selected = logLevel == lvl,
                            onClick = { viewModel.setLogLevel(lvl) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size)
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val (sizeKb, fileCount) = logFileStats
                Text(
                    text = stringResource(
                        R.string.pref_log_level_summary,
                        stringResource(levels.first { it.first == logLevel }.second),
                        sizeKb,
                        fileCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (canShare) {
                TextPreference(
                    title = stringResource(R.string.pref_report_problem_title),
                    summary = stringResource(R.string.pref_report_problem_summary),
                    onClick = onReportProblem
                )

                TextPreference(
                    title = stringResource(R.string.pref_export_logs_title),
                    summary = if (logFileStats.second > 0) {
                        stringResource(R.string.pref_export_logs_summary)
                    } else {
                        stringResource(R.string.pref_export_logs_summary_empty)
                    },
                    enabled = logFileStats.second > 0,
                    onClick = onExportLogs
                )
            }

            TextPreference(
                title = stringResource(R.string.pref_clear_logs_title),
                summary = stringResource(R.string.pref_clear_logs_summary),
                enabled = logFileStats.second > 0,
                onClick = { viewModel.clearLogs() }
            )

            // About Category
            PreferenceCategory(title = stringResource(R.string.pref_category_about))
            TextPreference(
                title = stringResource(R.string.pref_version_title),
                summary = appVersion,
                enabled = false,
                onClick = {}
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Player Name Dialog
    if (showPlayerNameDialog) {
        PlayerNameDialog(
            currentName = playerName,
            onConfirm = { name ->
                viewModel.setPlayerName(name)
                showPlayerNameDialog = false
            },
            onDismiss = { showPlayerNameDialog = false }
        )
    }

    // Sync Offset Dialog
    if (showSyncOffsetDialog) {
        SyncOffsetDialog(
            currentOffset = syncOffset,
            onConfirm = { offset ->
                viewModel.setSyncOffset(offset)
                showSyncOffsetDialog = false
            },
            onDismiss = { showSyncOffsetDialog = false }
        )
    }

    // Restart Dialog
    if (showRestartDialog) {
        RestartAppDialog(
            onConfirm = {
                showRestartDialog = false
                onRestartApp()
            },
            onDismiss = { showRestartDialog = false }
        )
    }

    // Codec Selection Dialog
    if (showCodecDialog) {
        CodecSelectionDialog(
            title = stringResource(R.string.pref_codec_title),
            currentCodec = preferredCodec,
            supportedCodecs = supportedCodecs,
            onSelect = { codec ->
                viewModel.setPreferredCodec(codec)
                showCodecDialog = false
            },
            onDismiss = { showCodecDialog = false }
        )
    }
}

/**
 * The column the bar and the list keep to on a TV. A phone list stretched
 * across 960dp puts its labels and switches at the edges of the screen,
 * which a TV may cut off, and is hard to follow from across a room.
 */
private fun Modifier.tvColumn(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = TvOverscanHorizontal)
    .wrapContentWidth()
    .widthIn(max = 720.dp)

// ============================================================================
// Preference Components
// ============================================================================

/**
 * The pairing token, rendered for transcription and copying.
 *
 * Deliberately ungrouped. Confirmed against a live Music Assistant in #191:
 * its decoder trims only *surrounding* whitespace, so any separator inserted
 * for legibility - a hyphen every eight characters, say - makes the token fail
 * with an opaque "not valid" error that says nothing about separators. The
 * readability win is not worth an unexplainable failure.
 *
 * Monospace so a user reading it aloud or retyping it can tell O from 0.
 *
 * On a TV there is nothing to copy it to, so the QR is how the token leaves
 * the device: it is larger there, with no Copy button. It is also a focus
 * stop, as nothing else near it takes focus and the D-pad would otherwise
 * scroll past it without ever showing it whole.
 */
@Composable
private fun PairingTokenPreference(
    token: String?,
    modifier: Modifier = Modifier
) {
    val isTv = LocalFormFactor.current == FormFactor.TV
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.pairing_token_copied)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.pairing_token_title),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(2.dp))

        if (token == null) {
            Text(
                text = stringResource(R.string.pairing_token_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Text(
            text = stringResource(R.string.pairing_token_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        PairingQrImage(
            token = token,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .then(if (isTv) Modifier.tvFocusable(shape = RectangleShape) else Modifier),
            size = if (isTv) 300.dp else 220.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                if (isTv) R.string.pairing_token_qr_hint_tv else R.string.pairing_token_qr_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(12.dp))

        SelectionContainerOffTv {
            Text(
                text = token,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pairing_token_secret_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        if (!isTv) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("plain text", token)))
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text(stringResource(R.string.pairing_token_copy))
            }
        }
    }
}

/**
 * [content] in a SelectionContainer, except on a TV. Selecting text takes a
 * pointer; with a remote the container is only a focus stop that shows
 * nothing when focused.
 */
@Composable
internal fun SelectionContainerOffTv(content: @Composable () -> Unit) {
    if (LocalFormFactor.current == FormFactor.TV) {
        content()
    } else {
        SelectionContainer(content = content)
    }
}

@Composable
private fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun SwitchPreference(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun TextPreference(
    title: String,
    summary: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}

@Composable
private fun CodecPreference(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncOffsetPreference(
    offset: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onValueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val value = if (offset >= 0) "+${offset}ms" else "${offset}ms"

    // On a TV the -, value and + buttons sit at the right of the screen, where
    // Down from the row above passes them by. There the row is one stop:
    // Left and Right step the offset, and OK opens the dialog.
    if (LocalFormFactor.current == FormFactor.TV) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onDecrease(); true }
                        Key.DirectionRight -> { onIncrease(); true }
                        else -> false
                    }
                }
                .clickable(onClick = onValueClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pref_sync_offset_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.pref_sync_offset_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.pref_sync_offset_title),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.pref_sync_offset_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // -10ms button
        OutlinedButton(
            onClick = onDecrease,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("-", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Value display (clickable)
        FilledTonalButton(
            onClick = onValueClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // +10ms button
        OutlinedButton(
            onClick = onIncrease,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedButtonPreference(
    title: String,
    summary: String,
    options: List<Pair<String, T>>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    onClick = { onOptionSelected(value) },
                    selected = selectedOption == value
                ) {
                    Text(label)
                }
            }
        }
    }
}

// ============================================================================
// Dialogs
// ============================================================================

// A dialog opens with nothing focused, which leaves a remote nothing to act
// on: on a TV each one focuses its main control, or the safe one.

@Composable
private fun PlayerNameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_player_name_title)) },
        text = {
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.pref_player_name_title),
                onDone = { onConfirm(name) }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun SyncOffsetDialog(
    currentOffset: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var offsetText by remember { mutableStateOf(currentOffset.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_sync_offset_dialog_title)) },
        text = {
            DialogTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                label = stringResource(R.string.pref_sync_offset_title),
                onDone = { offsetText.toIntOrNull()?.let { onConfirm(it) } },
                suffix = { Text("ms") }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    offsetText.toIntOrNull()?.let { onConfirm(it) }
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * The text field of the player name and sync offset dialogs, and on a TV the
 * dialog's initial focus. Done does what the dialog's OK does: with the
 * keyboard up in landscape the dialog's buttons can be out of reach.
 *
 * On a TV the keyboard waits for OK on the field ([TvKeyboardGate]), or it
 * would cover the dialog's OK and Cancel as soon as the dialog opens.
 */
@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onDone: () -> Unit,
    suffix: @Composable (() -> Unit)? = null
) {
    val keyboardGate = rememberTvKeyboardGate()
    val focusRequester = remember { FocusRequester() }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .then(keyboardGate.modifier),
        label = { Text(label) },
        suffix = suffix,
        readOnly = keyboardGate.readOnly,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
    // After the field: focused before it has finished setting up, the field
    // would open the keyboard, read-only or not.
    TvInitialFocus(focusRequester)
}

@Composable
private fun RestartAppDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_low_memory_restart_title)) },
        text = { Text(stringResource(R.string.pref_low_memory_restart_message)) },
        confirmButton = {
            TvInitialFocus(initialFocus)
            Button(onClick = onConfirm, modifier = Modifier.focusRequester(initialFocus)) {
                Text(stringResource(R.string.pref_low_memory_restart_now))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pref_low_memory_restart_later))
            }
        }
    )
}

@Composable
private fun CodecSelectionDialog(
    title: String,
    currentCodec: String,
    supportedCodecs: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val codecs = listOf(
        "opus" to "Opus",
        "flac" to "FLAC",
        "pcm" to "PCM"
    )
    val unavailableHint = stringResource(R.string.pref_codec_unavailable_hint)
    // The selected codec, unless this device cannot play it: its row then
    // cannot take focus, so the first one it can play does.
    val focusedCodec = currentCodec.takeIf { it in supportedCodecs } ?: supportedCodecs.first()
    val initialFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TvInitialFocus(initialFocus)
            Column {
                codecs.forEach { (value, label) ->
                    val supported = value in supportedCodecs
                    // The row is the one control: a RadioButton with its own
                    // onClick would be a second focus stop for the same choice.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (value == focusedCodec) Modifier.focusRequester(initialFocus)
                                else Modifier
                            )
                            .selectable(
                                selected = currentCodec == value,
                                enabled = supported,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) }
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentCodec == value,
                            onClick = null,
                            enabled = supported
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                color = if (supported) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (!supported) {
                                Text(
                                    text = unavailableHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// ============================================================================
// Utility
// ============================================================================

/**
 * Asks to exempt the app from battery optimization. Some devices, TVs among
 * them, have no screen for that request; the list of exemptions is the next
 * best place, and with neither there is nothing to open.
 */
private fun openBatteryOptimizationSettings(context: Context) {
    val request = Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.pref_battery_opt_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun getCodecDisplayName(codec: String): String {
    return when (codec.lowercase()) {
        "opus" -> "Opus"
        "flac" -> "FLAC"
        "pcm" -> "PCM"
        else -> codec
    }
}
