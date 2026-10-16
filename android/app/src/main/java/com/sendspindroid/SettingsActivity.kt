package com.sendspindroid

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.sendspindroid.diagnostics.DiagnosticSnapshot
import com.sendspindroid.diagnostics.DiagnosticsExport
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.ui.settings.SettingsScreen
import com.sendspindroid.ui.settings.SettingsViewModel
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import com.sendspindroid.ui.adaptive.ProvideTvFormFactor
import com.sendspindroid.ui.adaptive.isTvDevice

/**
 * Activity hosting the Compose-based settings screen.
 * Provides a standard settings screen with back navigation.
 */
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: must be called before super.onCreate. The
        // Material3 Scaffold inside SettingsScreen handles system-bar
        // insets automatically.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A TV may have nothing to share to: on the SHIELD the only target is
        // a stub that does nothing. Read once, as nothing installs a share
        // target while Settings is open.
        val canShare = !isTvDevice() || hasShareTarget(packageManager)

        setContent {
            SendSpinTheme {
                ProvideTvFormFactor {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = { finish() },
                        onExportLogs = { exportDebugLogs() },
                        onReportProblem = { reportProblem() },
                        onRestartApp = { restartApp() },
                        canShare = canShare
                    )
                }
            }
        }
    }

    private fun exportDebugLogs() {
        val shareIntent = DiagnosticsExport.shareIntent(this)

        if (shareIntent != null) {
            val chooserIntent = Intent.createChooser(
                shareIntent,
                getString(R.string.debug_share_chooser_title)
            )
            startActivity(chooserIntent)
            Toast.makeText(this, R.string.debug_log_exported, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.debug_log_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Capture a live diagnostic snapshot, bundle it with the redacted logs, and
     * open the share sheet (the share text carries the environment + a pre-filled
     * GitHub issue link). Snapshot capture is async, so this runs in a coroutine.
     */
    private fun reportProblem() {
        lifecycleScope.launch {
            val snapshot = DiagnosticSnapshot.capture(this@SettingsActivity)
            val shareIntent = DiagnosticsExport.reportIntent(this@SettingsActivity, snapshot)
            if (shareIntent != null) {
                startActivity(
                    Intent.createChooser(shareIntent, getString(R.string.report_chooser_title))
                )
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.debug_log_export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Start over in a new process, so that everything read once at startup
     * is read again: Low Memory Mode decides the image loaders there (#330).
     * Restarting the activities alone leaves the service and the process as
     * they were.
     */
    private fun restartApp() {
        lifecycleScope.launch {
            // Leave the server first: the result comes once the goodbye is out.
            withTimeoutOrNull(EXIT_TIMEOUT_MS) { exitPlaybackService() }
            val launcher = packageManager.getLaunchIntentForPackage(packageName)?.component
            if (launcher != null) startActivity(Intent.makeRestartActivityTask(launcher))
            Runtime.getRuntime().exit(0)
        }
    }

    private suspend fun exitPlaybackService() = suspendCancellableCoroutine { cont ->
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) {
                if (cont.isActive) cont.resume(Unit)
                return@addListener
            }
            val command = SessionCommand(PlaybackService.COMMAND_EXIT, Bundle.EMPTY)
            val result = controller.sendCustomCommand(command, Bundle.EMPTY)
            result.addListener({
                controller.release()
                if (cont.isActive) cont.resume(Unit)
            }, mainExecutor)
        }, mainExecutor)
    }

    private companion object {
        // Past the service's own wait for the goodbye, which ends at 2 s.
        const val EXIT_TIMEOUT_MS = 3_000L
    }
}

/** Android TV's placeholder for the apps a TV leaves out, mail among them. */
private const val TV_STUB_PACKAGE = "com.google.android.tv.frameworkpackagestubs"

/**
 * Whether anything can receive the text shares that Report a problem and
 * Export Debug Logs send. A TV's stub package answers a share and does
 * nothing with it, so it does not count.
 */
@Suppress("DEPRECATION") // The ResolveInfoFlags overload needs API 33.
internal fun hasShareTarget(packageManager: PackageManager): Boolean =
    packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0)
        .any { it.activityInfo.packageName != TV_STUB_PACKAGE }
