package br.com.c6tag.diagnostico

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import br.com.c6tag.diagnostico.remote.access.GoogleCredentialRequestor
import br.com.c6tag.diagnostico.remote.access.RemoteAccessViewModel
import br.com.c6tag.diagnostico.ui.remote.RemoteAccessScreen

class MainActivity : ComponentActivity() {
    private var noticePermissionRequested = false
    private var remoteCredentialRequestLease: Long? = null
    private var remoteCredentialRequestJob: Job? = null
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
    private val remoteAccessViewModel: RemoteAccessViewModel by lazy(LazyThreadSafetyMode.NONE) {
        val collectorApplication = application as CollectorApplication
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(RemoteAccessViewModel::class.java))
                    return RemoteAccessViewModel(
                        identity = collectorApplication.ownerIdentity,
                        ownerAccessClient = collectorApplication.ownerAccessClient,
                        sessionStore = collectorApplication.remoteHostSessionStore,
                    ) as T
                }
            },
        )[RemoteAccessViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noticePermissionRequested = savedInstanceState?.getBoolean("noticePermissionRequested") ?: false
        setContent {
            val appContentResolver = applicationContext.contentResolver
            val createDocumentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json"),
            ) { uri: Uri? ->
                if (uri == null) {
                    diagnosticViewModel.onExportDestinationCancelled()
                } else {
                    diagnosticViewModel.exportToDestination {
                        appContentResolver.openOutputStream(uri, "w")
                    }
                }
            }
            val monitorState by monitorViewModel.uiState.collectAsStateWithLifecycle()
            MonitorTheme(preference = monitorState.overview.settings.theme) {
                MonitorRoot(
                    state = monitorState,
                    actions = MonitorActions(
                        onReference = monitorViewModel::beginReference,
                        onAcknowledge = monitorViewModel::acknowledge,
                        onResume = monitorViewModel::resume,
                        onOpenCaptureSettings = {
                            monitorViewModel.clearCaptureSettingsError()
                            openNotificationListenerSettings()
                        },
                        onHistory = monitorViewModel::retryHistory,
                        onRetry = monitorViewModel::refresh,
                        onTheme = monitorViewModel::changeTheme,
                        onHistoryFilter = monitorViewModel::selectHistoryFilter,
                        onOpenFinancialEntry = monitorViewModel::openFinancialEntry,
                        onCloseFinancialEntry = monitorViewModel::closeFinancialEntry,
                        onLoadMoreHistory = monitorViewModel::loadMoreHistory,
                        onRetryHistory = monitorViewModel::retryHistory,
                        onPendingReview = monitorViewModel::loadPending,
                        onLoadMorePending = monitorViewModel::loadMorePending,
                        onRetryPending = monitorViewModel::retryPending,
                        onOpenPending = monitorViewModel::openPending,
                        onClosePending = monitorViewModel::closePending,
                        onRequestPendingDecision = monitorViewModel::requestPendingDecision,
                        onDismissPendingDecision = monitorViewModel::dismissPendingDecision,
                        onConfirmPendingDecision = monitorViewModel::confirmPendingDecision,
                        onSettingsLimit = { change -> monitorViewModel.editSettingsLimit(change) },
                        onSettingsRepeat = monitorViewModel::editSettingsRepeat,
                        onSettingsPause = monitorViewModel::editSettingsPause,
                        onSaveSettings = monitorViewModel::saveSettings,
                        onOpenNoticeSettings = {
                            monitorViewModel.clearNoticeSettingsError()
                            openNoticeChannelSettings()
                        },
                    ),
                    referenceActions = ReferenceActions(
                        onBalance = { change -> monitorViewModel.updateBalance(change) },
                        onLimit = { change -> monitorViewModel.updateLimit(change) },
                        onReview = monitorViewModel::reviewReference,
                        onDismissConfirmation = monitorViewModel::dismissReferenceConfirmation,
                        onConfirm = monitorViewModel::confirmReference,
                        onCancel = monitorViewModel::cancelReference,
                    ),
                    onEnableNotices = ::enableNotices,
                    diagnostic = {
                        DiagnosticContent { filename ->
                            try {
                                createDocumentLauncher.launch(filename)
                            } catch (_: RuntimeException) {
                                diagnosticViewModel.reportExportLaunchFailure()
                            }
                        }
                    },
                    onOpenRemoteAccess = {},
                    remoteAccess = { onClose -> RemoteAccessContent(onClose) },
                )
            }
        }
    }

    @Composable
    private fun RemoteAccessContent(onClose: () -> Unit) {
        val viewModel = remoteAccessViewModel
        val leaseState = remember(viewModel) { mutableStateOf<Long?>(null) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val clipboard = LocalClipboard.current
        val clipboardScope = rememberCoroutineScope()
        androidx.compose.runtime.DisposableEffect(viewModel) {
            val lease = viewModel.attachSurface()
            leaseState.value = lease
            onDispose {
                viewModel.detachSurface(lease)
                cancelRemoteCredentialRequest(lease)
            }
        }
        val lease = leaseState.value
        if (lease == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            RemoteAccessScreen(
                state = state,
                onSignIn = { requestRemoteGoogleCredential(viewModel, lease, reauthenticate = false) },
                onReauthenticate = {
                    requestRemoteGoogleCredential(viewModel, lease, reauthenticate = true)
                },
                onDiscover = { viewModel.discoverOwnerMonitor(lease) },
                onConfirmActivation = { confirmationId, replacementWarningConfirmed ->
                    viewModel.confirmHostActivation(
                        lease = lease,
                        confirmationId = confirmationId,
                        hostReplacementWarningConfirmed = replacementWarningConfirmed,
                    )
                },
                onCancelConfirmation = { viewModel.cancelConfirmation(lease) },
                onSignOut = { viewModel.signOut(lease) },
                onCopyUid = { uid ->
                    clipboardScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Firebase UID", uid)))
                    }
                },
                onClose = onClose,
            )
        }
    }

    @Composable
    private fun DiagnosticContent(onLaunchExport: (String) -> Unit) {
        val state by diagnosticViewModel.uiState.collectAsStateWithLifecycle()
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
                        onLaunchExport(diagnosticFilename())
                    }
                },
            ),
        )
    }

    private fun requestRemoteGoogleCredential(
        viewModel: RemoteAccessViewModel,
        lease: Long,
        reauthenticate: Boolean,
    ) {
        if (remoteCredentialRequestJob?.isActive == true) {
            return
        }
        remoteCredentialRequestLease = lease
        viewModel.googleCredentialRequestStarted(lease)
        remoteCredentialRequestJob = lifecycleScope.launch {
            try {
                val result = GoogleCredentialRequestor(this@MainActivity).request()
                viewModel.acceptGoogleCredential(lease, result, reauthenticate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                viewModel.googleCredentialRequestFailed(lease)
                throw exception
            } finally {
                if (remoteCredentialRequestLease == lease) {
                    remoteCredentialRequestLease = null
                    remoteCredentialRequestJob = null
                }
            }
        }
    }

    private fun cancelRemoteCredentialRequest(lease: Long) {
        if (remoteCredentialRequestLease == lease) {
            remoteCredentialRequestJob?.cancel()
            remoteCredentialRequestJob = null
            remoteCredentialRequestLease = null
        }
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
            monitorViewModel.reportNoticeSettingsFailure()
        }
    }

    private fun openNoticeChannelSettings() {
        try {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, MonitorNotifier.CHANNEL_ID)
            })
        } catch (_: RuntimeException) {
            monitorViewModel.reportNoticeSettingsFailure()
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
            monitorViewModel.reportCaptureSettingsFailure()
        } catch (_: SecurityException) {
            diagnosticViewModel.reportSettingsLaunchFailure()
            monitorViewModel.reportCaptureSettingsFailure()
        }
    }

    private fun diagnosticFilename(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return "c6tag-diagnostico-$date.json"
    }
}
