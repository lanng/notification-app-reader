package br.com.c6tag.diagnostico.remote.probe

import android.Manifest
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
    private lateinit var fullScreenAccess: ProbeFullScreenAccess
    private lateinit var windowPresentation: ProbeWindowPresentation
    private lateinit var delayedTrigger: ProbeDelayedTrigger
    private lateinit var presentationExpiryTrigger: ProbeDelayedTrigger
    private var probeStatus by mutableStateOf<ProbeControllerStatus?>(null)
    private var message by mutableStateOf<String?>(null)
    private var fullScreenOptionEnabled by mutableStateOf(false)
    private var delayedStartPending by mutableStateOf(false)
    private var presentationMode by mutableStateOf(false)
    private var presentationOccurrenceId: String? = null
    private var restoredOccurrenceHint: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredOccurrenceHint = savedInstanceState?.getString(STATE_OCCURRENCE_HINT)
        fullScreenAccess = ProbeDependencies.fullScreenAccessFactory(applicationContext)
        controller = ProbeDependencies.controllerFactory(applicationContext, fullScreenAccess)
        windowPresentation = ProbeDependencies.windowPresentationFactory(this)
        delayedTrigger = ProbeDependencies.delayedTriggerFactory(applicationContext) {
            delayedStartPending = false
            startProbe(fullScreenOptionEnabled)
        }
        presentationExpiryTrigger = ProbeDependencies.delayedTriggerFactory(applicationContext) {
            expirePresentationIfNeeded()
        }
        acceptShowAlertIntent(intent)
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
                fullScreenAccess = ProbeFullScreenAccessStatus.UNKNOWN,
            )
            MonitorTheme {
                AlertProbeScreen(
                    state = AlertProbeScreenState(
                        status = currentStatus,
                        message = message,
                        showPermissionAction = Build.VERSION.SDK_INT >= 33 &&
                            currentStatus.permission != ProbeCapabilityStatus.GRANTED,
                        showFullScreenSettingsAction = Build.VERSION.SDK_INT >= 34,
                        fullScreenOptionEnabled = fullScreenOptionEnabled,
                        delayedStartPending = delayedStartPending,
                        presentationMode = presentationMode,
                    ),
                    actions = AlertProbeScreenActions(
                        onStart = { startProbe(fullScreenOptionEnabled) },
                        onStop = ::stopProbe,
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenSettings = ::openNotificationSettings,
                        onBack = { finish() },
                        onFullScreenOptionChanged = { fullScreenOptionEnabled = it },
                        onOpenFullScreenSettings = ::openFullScreenSettings,
                        onScheduleDelayedStart = ::scheduleDelayedStart,
                        onCancelDelayedStart = ::cancelDelayedStart,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShowAlertIntent(intent)
        refreshStatus()
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

    override fun onDestroy() {
        if (::delayedTrigger.isInitialized) delayedTrigger.cancel()
        if (::presentationExpiryTrigger.isInitialized) presentationExpiryTrigger.cancel()
        if (presentationMode && ::windowPresentation.isInitialized) clearPresentationWindow()
        super.onDestroy()
    }

    private fun startProbe(useFullScreenIntent: Boolean) {
        cancelDelayedStart()
        message = getString(
            when (controller.submitCritical(useFullScreenIntent)) {
                ProbeSubmissionOutcome.ACCEPTED -> R.string.probe_start_requested
                ProbeSubmissionOutcome.PERMISSION_DENIED -> R.string.probe_start_denied
                ProbeSubmissionOutcome.CHANNEL_BLOCKED -> R.string.probe_start_channel_blocked
                ProbeSubmissionOutcome.FAILED -> R.string.probe_start_failed
            },
        )
        refreshStatus()
    }

    private fun stopProbe() {
        val occurrenceId = if (presentationMode) {
            presentationOccurrenceId
        } else {
            probeStatus?.storedOccurrenceId
        } ?: return
        cancelDelayedStart()
        val stopped = controller.stop(occurrenceId)
        message = getString(
            if (stopped) R.string.probe_stop_requested else R.string.probe_stop_failed,
        )
        refreshStatus()
        if (presentationMode && !controller.isCurrentPresentationSession(occurrenceId)) {
            leavePresentation(finishActivity = true)
        }
    }

    private fun scheduleDelayedStart() {
        delayedTrigger.schedule()
        delayedStartPending = delayedTrigger.isPending
        message = getString(R.string.probe_delayed_start_scheduled)
    }

    private fun cancelDelayedStart() {
        if (!::delayedTrigger.isInitialized) return
        delayedTrigger.cancel()
        delayedStartPending = false
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
        } catch (_: android.content.ActivityNotFoundException) {
            message = getString(R.string.probe_settings_unavailable)
        } catch (_: SecurityException) {
            message = getString(R.string.probe_settings_unavailable)
        } catch (_: RuntimeException) {
            message = getString(R.string.probe_settings_unavailable)
        }
    }

    private fun openFullScreenSettings() {
        message = if (fullScreenAccess.openSettings(this)) {
            null
        } else {
            getString(R.string.probe_full_screen_settings_unavailable)
        }
    }

    private fun acceptShowAlertIntent(incoming: Intent?) {
        if (incoming?.action != ProbeNotificationController.SHOW_ALERT_ACTION) return
        if (!controller.isCurrentPresentationIntent(incoming)) {
            if (presentationMode && !hasCurrentPresentationSession()) {
                leavePresentation(finishActivity = true)
            }
            return
        }

        val occurrenceId = incoming.getStringExtra(ProbeNotificationController.EXTRA_OCCURRENCE_ID)
            ?: return
        val remainingMillis = controller.presentationRemainingMillis(occurrenceId)
        if (remainingMillis == null) {
            if (presentationMode && !hasCurrentPresentationSession()) {
                leavePresentation(finishActivity = true)
            }
            return
        }
        presentationOccurrenceId = occurrenceId
        presentationMode = true
        try {
            windowPresentation.show()
            presentationExpiryTrigger.scheduleAfter(remainingMillis)
        } catch (_: RuntimeException) {
            leavePresentation(finishActivity = true)
        }
    }

    private fun expirePresentationIfNeeded() {
        if (!presentationMode) return
        val occurrenceId = presentationOccurrenceId ?: run {
            leavePresentation(finishActivity = true)
            return
        }
        val remainingMillis = controller.presentationRemainingMillis(occurrenceId)
        if (remainingMillis == null) {
            leavePresentation(finishActivity = true)
        } else {
            presentationExpiryTrigger.scheduleAfter(remainingMillis)
        }
    }

    private fun hasCurrentPresentationSession(): Boolean {
        val occurrenceId = presentationOccurrenceId ?: return false
        return controller.isCurrentPresentationSession(occurrenceId)
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
                fullScreenAccess = ProbeFullScreenAccessStatus.UNKNOWN,
            )
        }
        restoredOccurrenceHint = null
        if (presentationMode && !hasCurrentPresentationSession()) {
            leavePresentation(finishActivity = true)
        }
    }

    private fun leavePresentation(finishActivity: Boolean) {
        if (presentationMode) clearPresentationWindow()
        if (::presentationExpiryTrigger.isInitialized) presentationExpiryTrigger.cancel()
        presentationMode = false
        presentationOccurrenceId = null
        if (finishActivity && !isFinishing) finish()
    }

    private fun clearPresentationWindow() {
        try {
            windowPresentation.clear()
        } catch (_: RuntimeException) {
            // Clearing is best-effort; finishing still prevents later presentation.
        }
    }

    private companion object {
        const val STATE_OCCURRENCE_HINT = "probe_occurrence_presentation_hint"
    }
}
