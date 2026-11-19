package br.com.c6tag.diagnostico.remote.probe

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme

internal class AlertProbeActivity : ComponentActivity() {
    private lateinit var controller: ProbeNotificationController
    private var probeStatus by mutableStateOf<ProbeControllerStatus?>(null)
    private var message by mutableStateOf<String?>(null)
    private var restoredOccurrenceHint: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredOccurrenceHint = savedInstanceState?.getString(STATE_OCCURRENCE_HINT)
        controller = ProbeDependencies.controllerFactory(applicationContext)
        refreshStatus()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                refreshStatus()
            }
            val currentStatus = probeStatus ?: ProbeControllerStatus(
                permission = ProbeCapabilityStatus.UNKNOWN,
                appNotifications = ProbeCapabilityStatus.UNKNOWN,
                channel = ProbeChannelStatus.UNKNOWN,
                sessionReadStatus = ProbeSessionReadStatus.UNKNOWN,
                storedOccurrenceId = null,
            )
            MonitorTheme {
                AlertProbeScreen(
                    state = AlertProbeScreenState(
                        status = currentStatus,
                        message = message,
                        showPermissionAction = Build.VERSION.SDK_INT >= 33 &&
                            currentStatus.permission != ProbeCapabilityStatus.GRANTED,
                    ),
                    actions = AlertProbeScreenActions(
                        onStart = ::startProbe,
                        onStop = ::stopProbe,
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenSettings = ::openNotificationSettings,
                        onBack = { finish() },
                    ),
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (probeStatus?.storedOccurrenceId ?: restoredOccurrenceHint)?.let {
            outState.putString(STATE_OCCURRENCE_HINT, it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) refreshStatus()
    }

    private fun startProbe() {
        message = getString(
            when (controller.submitCritical()) {
                ProbeSubmissionOutcome.ACCEPTED -> R.string.probe_start_requested
                ProbeSubmissionOutcome.PERMISSION_DENIED -> R.string.probe_start_denied
                ProbeSubmissionOutcome.CHANNEL_BLOCKED -> R.string.probe_start_channel_blocked
                ProbeSubmissionOutcome.FAILED -> R.string.probe_start_failed
            },
        )
        refreshStatus()
    }

    private fun stopProbe() {
        val occurrenceId = probeStatus?.storedOccurrenceId ?: return
        message = getString(
            if (controller.stop(occurrenceId)) {
                R.string.probe_stop_requested
            } else {
                R.string.probe_stop_failed
            },
        )
        refreshStatus()
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, ProbeNotificationController.CHANNEL_ID)
                },
            )
            message = null
        } catch (_: ActivityNotFoundException) {
            message = getString(R.string.probe_settings_unavailable)
        } catch (_: SecurityException) {
            message = getString(R.string.probe_settings_unavailable)
        } catch (_: RuntimeException) {
            message = getString(R.string.probe_settings_unavailable)
        }
    }

    private fun refreshStatus() {
        val previousOccurrenceId = probeStatus?.storedOccurrenceId ?: restoredOccurrenceHint
        probeStatus = try {
            controller.readStatus(previousOccurrenceId)
        } catch (_: Exception) {
            ProbeControllerStatus(
                permission = ProbeCapabilityStatus.UNKNOWN,
                appNotifications = ProbeCapabilityStatus.UNKNOWN,
                channel = ProbeChannelStatus.UNKNOWN,
                sessionReadStatus = ProbeSessionReadStatus.UNKNOWN,
                storedOccurrenceId = previousOccurrenceId,
            )
        }
        restoredOccurrenceHint = null
    }

    private companion object {
        const val STATE_OCCURRENCE_HINT = "probe_occurrence_presentation_hint"
    }
}
