package br.com.c6tag.diagnostico

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import br.com.c6tag.diagnostico.ui.DiagnosticScreen
import br.com.c6tag.diagnostico.ui.DiagnosticScreenActions
import br.com.c6tag.diagnostico.ui.DiagnosticViewModel
import br.com.c6tag.diagnostico.ui.monitor.*
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import br.com.c6tag.diagnostico.monitor.alerts.MonitorNotifier

class MainActivity : ComponentActivity() {
    private var noticePermissionRequested = false
    private val noticePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPlatformState()
    }
    private val monitorViewModel: MonitorViewModel by lazy(LazyThreadSafetyMode.NONE) {
        val collectorApplication = application as CollectorApplication
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MonitorViewModel::class.java))
                return MonitorViewModel(collectorApplication.monitorCoordinator, collectorApplication.captureStatus) as T
            }
        })[MonitorViewModel::class.java]
    }
    private val diagnosticViewModel: DiagnosticViewModel by lazy(LazyThreadSafetyMode.NONE) {
        val collectorApplication = application as CollectorApplication
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(DiagnosticViewModel::class.java))
                    return DiagnosticViewModel(
                        recorder = collectorApplication.diagnosticRecorder,
                        exporter = collectorApplication.diagnosticExporter,
                        captureStatus = collectorApplication.captureStatus,
                        targetPackage = BuildConfig.C6_PACKAGE_NAME,
                    ) as T
                }
            },
        )[DiagnosticViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noticePermissionRequested = savedInstanceState?.getBoolean("noticePermissionRequested") ?: false
        setContent {
            val monitorState by monitorViewModel.uiState.collectAsStateWithLifecycle()
            MonitorTheme(preference = monitorState.overview.settings.theme) {
                MonitorRoot(
                    state = monitorState,
                    actions = MonitorActions(
                        onReference = monitorViewModel::beginReference,
                        onAcknowledge = monitorViewModel::acknowledge,
                        onResume = monitorViewModel::resume,
                        onOpenCaptureSettings = ::openNotificationListenerSettings,
                        onRetry = monitorViewModel::refresh,
                        onTheme = monitorViewModel::changeTheme,
                    ),
                    referenceActions = ReferenceActions(
                        onBalance = monitorViewModel::updateBalance,
                        onLimit = monitorViewModel::updateLimit,
                        onReview = monitorViewModel::reviewReference,
                        onDismissConfirmation = monitorViewModel::dismissReferenceConfirmation,
                        onConfirm = monitorViewModel::confirmReference,
                        onCancel = monitorViewModel::cancelReference,
                    ),
                    onEnableNotices = ::enableNotices,
                    diagnostic = { DiagnosticContent() },
                )
            }
        }
    }

    @Composable
    private fun DiagnosticContent() {
        val state by diagnosticViewModel.uiState.collectAsStateWithLifecycle()
        val appContentResolver = applicationContext.contentResolver
        val createDocumentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri == null) {
                diagnosticViewModel.onExportDestinationCancelled()
            } else {
                // The callback retains only the application resolver and immutable SAF Uri.
                diagnosticViewModel.exportToDestination {
                    appContentResolver.openOutputStream(uri, "w")
                }
            }
        }
        DiagnosticScreen(
            state = state,
            actions = DiagnosticScreenActions(
                onOpenSettings = ::openNotificationListenerSettings,
                onLoadPrevious = diagnosticViewModel::loadPrevious,
                onRetryHistory = diagnosticViewModel::retryHistory,
                onOpenEvent = diagnosticViewModel::openEvent,
                onCloseDetail = diagnosticViewModel::closeDetail,
                onRequestClear = diagnosticViewModel::requestClear,
                onCancelClear = diagnosticViewModel::cancelClear,
                onConfirmClear = diagnosticViewModel::confirmClear,
                onRequestExport = diagnosticViewModel::requestExport,
                onCancelExport = diagnosticViewModel::cancelExport,
                onConfirmExport = {
                    if (diagnosticViewModel.confirmExport()) {
                        try {
                            createDocumentLauncher.launch(diagnosticFilename())
                        } catch (_: RuntimeException) {
                            diagnosticViewModel.reportExportLaunchFailure()
                        }
                    }
                },
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        diagnosticViewModel.updateAuthorization(checkNotificationListenerAccess())
        monitorViewModel.refresh()
        refreshPlatformState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("noticePermissionRequested", noticePermissionRequested)
        super.onSaveInstanceState(outState)
    }

    private fun refreshPlatformState() {
        val authorization = checkNotificationListenerAccess()
        val collectorApplication = application as CollectorApplication
        lifecycleScope.launch {
            val notices = withContext(Dispatchers.IO) {
                try {
                    val notifier = collectorApplication.monitorNotifier
                    notifier.ensureChannel()
                    val capability = notifier.capabilities()
                    val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(MonitorNotifier.CHANNEL_ID)
                    when {
                        !capability.permissionGranted || !capability.appNotificationsEnabled -> NoticeAccess.DENIED
                        !capability.channelExists || capability.channelBlocked -> NoticeAccess.CHANNEL_BLOCKED
                        channel?.sound == null || channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> NoticeAccess.SILENT
                        else -> NoticeAccess.AVAILABLE
                    }
                } catch (_: Exception) {
                    NoticeAccess.UNKNOWN
                }
            }
            monitorViewModel.updatePlatform(authorization, notices)
        }
    }

    private fun enableNotices() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!noticePermissionRequested || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                noticePermissionRequested = true
                noticePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, MonitorNotifier.CHANNEL_ID)
                })
            }
        } catch (_: RuntimeException) {
            monitorViewModel.reportPlatformFailure()
        }
    }

    private fun checkNotificationListenerAccess(): AuthorizationStatus =
        try {
            if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
                AuthorizationStatus.AUTHORIZED
            } else {
                AuthorizationStatus.NOT_AUTHORIZED
            }
        } catch (_: RuntimeException) {
            AuthorizationStatus.CHECK_FAILED
        }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            diagnosticViewModel.reportSettingsLaunchSuccess()
        } catch (_: ActivityNotFoundException) {
            diagnosticViewModel.reportSettingsLaunchFailure()
            monitorViewModel.reportPlatformFailure()
        } catch (_: SecurityException) {
            diagnosticViewModel.reportSettingsLaunchFailure()
            monitorViewModel.reportPlatformFailure()
        }
    }

    private fun diagnosticFilename(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return "c6tag-diagnostico-$date.json"
    }
}
