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
import br.com.c6tag.diagnostico.remote.access.HttpsPairingAccessTransport
import br.com.c6tag.diagnostico.remote.access.KeystoreRemoteHostSessionStore
import br.com.c6tag.diagnostico.remote.access.KeystoreRemoteSupportSessionStore
import br.com.c6tag.diagnostico.remote.access.OwnerAccessClient
import br.com.c6tag.diagnostico.remote.access.OwnerIdentity
import br.com.c6tag.diagnostico.remote.access.PairingAccessClient
import br.com.c6tag.diagnostico.remote.access.RemoteHostSessionStore
import br.com.c6tag.diagnostico.remote.access.RemoteRoleGuard
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionStore
import br.com.c6tag.diagnostico.remote.sync.GuestMonitorRepository
import br.com.c6tag.diagnostico.remote.sync.HostMonitorSyncController
import br.com.c6tag.diagnostico.remote.sync.HttpsSharedMonitorSyncClient
import br.com.c6tag.diagnostico.remote.sync.SharedMonitorSyncClient
import br.com.c6tag.diagnostico.remote.sync.SharedPreferencesGuestLocalStateStore
import br.com.c6tag.diagnostico.remote.sync.SupportAlertNotifier
import br.com.c6tag.diagnostico.remote.sync.WorkManagerGuestSyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        hostMonitorSyncController.start()
        applicationScope.launch {
            try {
                guestMonitorRepository.refreshRoleOnly()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep safe state on startup.
            }
        }
    }

    internal fun receiveGuestAlertAction(intent: Intent, finished: () -> Unit) {
        applicationScope.launch {
            try {
                if (intent.action == br.com.c6tag.diagnostico.remote.sync.SupportAlertActionReceiver.ACTION_ACKNOWLEDGE) {
                    guestMonitorRepository.acknowledgeLocalAlert()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Safe absorption.
            } finally {
                finished()
            }
        }
    }

    internal fun receiveCriticalAlertAcknowledge(
        isGuest: Boolean,
        cycleId: String?,
        finished: () -> Unit,
    ) {
        applicationScope.launch {
            try {
                if (isGuest) {
                    guestMonitorRepository.acknowledgeLocalAlert()
                } else if (!cycleId.isNullOrBlank()) {
                    monitorCoordinator.acknowledge(cycleId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Safe absorption.
            } finally {
                finished()
            }
        }
    }

    internal fun onFcmTokenRefreshed(token: String) {
        applicationScope.launch {
            try {
                guestMonitorRepository.onFcmTokenRefreshed(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Safe absorption.
            }
        }
    }

    internal fun onFcmMessageReceived() {
        applicationScope.launch {
            try {
                guestMonitorRepository.syncNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Safe absorption.
            }
        }
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

    internal val remoteSupportSessionStore: RemoteSupportSessionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeystoreRemoteSupportSessionStore(applicationContext)
    }

    internal val pairingAccessClient: PairingAccessClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PairingAccessClient(HttpsPairingAccessTransport(BuildConfig.OWNER_API_ORIGIN))
    }

    internal val remoteRoleGuard: RemoteRoleGuard by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteRoleGuard(
            hostPresence = { remoteHostSessionStore.presence() },
            supportPresence = { remoteSupportSessionStore.presence() },
        )
    }

    internal val sharedMonitorSyncClient: SharedMonitorSyncClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HttpsSharedMonitorSyncClient(BuildConfig.OWNER_API_ORIGIN)
    }

    internal val hostMonitorSyncController: HostMonitorSyncController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HostMonitorSyncController(
            monitorCoordinator = monitorCoordinator,
            hostSessionStore = remoteHostSessionStore,
            syncClient = sharedMonitorSyncClient,
            scope = applicationScope,
        )
    }

    internal val supportAlertNotifier: SupportAlertNotifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SupportAlertNotifier(applicationContext)
    }

    internal val guestMonitorRepository: GuestMonitorRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GuestMonitorRepository(
            supportSessionStore = remoteSupportSessionStore,
            syncClient = sharedMonitorSyncClient,
            localStateStore = SharedPreferencesGuestLocalStateStore(applicationContext),
            alertNotifier = supportAlertNotifier,
            scheduler = WorkManagerGuestSyncScheduler(WorkManager.getInstance(applicationContext)),
            fcmTokenProvider = ::fetchFcmTokenBestEffort,
        )
    }

    private suspend fun fetchFcmTokenBestEffort(): String? =
        try {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (continuation.isActive) {
                        val token = if (task.isSuccessful) task.result else null
                        continuation.resume(token)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
}
