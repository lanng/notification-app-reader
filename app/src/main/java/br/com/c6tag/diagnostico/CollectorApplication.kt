package br.com.c6tag.diagnostico

import android.app.Application
import android.content.Intent
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.capture.NotificationCaptureCoordinator
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticExporter
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.alerts.AndroidMonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.alerts.MonitorNotifier
import br.com.c6tag.diagnostico.monitor.alerts.LowBalanceScheduler
import androidx.work.WorkManager
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import br.com.c6tag.diagnostico.monitor.alerts.AlertActionReceiver
import br.com.c6tag.diagnostico.remote.access.FirebaseOwnerIdentity
import br.com.c6tag.diagnostico.remote.access.HttpsOwnerAccessTransport
import br.com.c6tag.diagnostico.remote.access.KeystoreRemoteHostSessionStore
import br.com.c6tag.diagnostico.remote.access.OwnerAccessClient
import br.com.c6tag.diagnostico.remote.access.OwnerIdentity
import br.com.c6tag.diagnostico.remote.access.RemoteHostSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CollectorApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diagnosticDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DiagnosticDatabase(applicationContext)
    }

    internal val captureStatus: CaptureStatus by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CaptureStatus(BuildConfig.C6_PACKAGE_NAME)
    }

    internal val diagnosticRecorder: DiagnosticRecorder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DiagnosticRecorder(
            store = diagnosticDatabase,
            allowedPackage = BuildConfig.C6_PACKAGE_NAME,
            scope = applicationScope,
        )
    }

    private val monitorStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SqliteMonitorStore(diagnosticDatabase)
    }

    internal val monitorNotifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MonitorNotifier(applicationContext)
    }

    private val monitorScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LowBalanceScheduler(WorkManager.getInstance(applicationContext))
    }

    internal val monitorCoordinator: MonitorCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MonitorCoordinator(
            recorder = diagnosticRecorder,
            store = monitorStore,
            effects = AndroidMonitorAlertEffects(monitorNotifier, monitorScheduler),
            scope = applicationScope,
            clock = System::currentTimeMillis,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Construction is lightweight; the bootstrap is enqueued before capture/UI use.
        monitorCoordinator
    }

    internal fun receiveAlertAction(intent: Intent, finished: () -> Unit) {
        applicationScope.launch {
            try {
                AlertActionReceiver.dispatch(intent, monitorCoordinator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The recorder/coordinator retain safe error state; do not log notification data.
            } finally {
                finished()
            }
        }
    }

    internal val diagnosticExporter: DiagnosticExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DiagnosticExporter(
            recorder = diagnosticRecorder,
            targetPackage = BuildConfig.C6_PACKAGE_NAME,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    internal val captureCoordinator: NotificationCaptureCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NotificationCaptureCoordinator(
            targetPackage = BuildConfig.C6_PACKAGE_NAME,
            recorder = diagnosticRecorder,
            captureStatus = captureStatus,
            scope = applicationScope,
        )
    }

    internal val ownerIdentity: OwnerIdentity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FirebaseOwnerIdentity(applicationContext)
    }

    internal val ownerAccessClient: OwnerAccessClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OwnerAccessClient(HttpsOwnerAccessTransport(BuildConfig.OWNER_API_ORIGIN))
    }

    internal val remoteHostSessionStore: RemoteHostSessionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeystoreRemoteHostSessionStore(applicationContext)
    }
}
