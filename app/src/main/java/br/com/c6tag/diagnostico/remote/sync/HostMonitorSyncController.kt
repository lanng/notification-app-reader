package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.RevisionCause
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.remote.access.RemoteHostSessionLoad
import br.com.c6tag.diagnostico.remote.access.RemoteHostSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes sanitized Host balance, inherited settings, alert state, and recent history
 * to the owner's backend whenever local monitor state changes.
 *
 * Offline or remote errors are absorbed safely so local Host monitoring and notification
 * capture are never disrupted.
 */
internal class HostMonitorSyncController(
    private val monitorCoordinator: MonitorCoordinator,
    private val hostSessionStore: RemoteHostSessionStore,
    private val syncClient: SharedMonitorSyncClient,
    private val scope: CoroutineScope,
) {
    private val syncMutex = Mutex()
    private var started = false

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            monitorCoordinator.revisions.collect { revision ->
                if (revision.cause == RevisionCause.APPEARANCE) {
                    return@collect
                }
                syncNowInternal()
            }
        }
    }

    fun requestSync() {
        scope.launch {
            syncNowInternal()
        }
    }

    suspend fun syncNow(): Boolean = syncNowInternal()

    private suspend fun syncNowInternal(): Boolean = syncMutex.withLock {
        val origin = syncClient.canonicalOrigin ?: return@withLock false
        val session = when (val loaded = hostSessionStore.loadActiveForOrigin(origin)) {
            is RemoteHostSessionLoad.ActiveSession -> loaded.activation
            else -> return@withLock false
        }

        return@withLock try {
            val overview = monitorCoordinator.overview()
            val recentEntries = monitorCoordinator.history(
                beforeId = null,
                limit = SYNC_HISTORY_LIMIT,
                filter = HistoryFilter.ALL,
            )
            val activeCycle = overview.alertCycle?.takeIf { it.status != AlertStatus.CLOSED }
            val limitCents = overview.settings.limitCents
            if (limitCents <= 0L) {
                return@withLock false
            }

            val upload = HostSnapshotUpload(
                balanceCents = overview.balanceCents,
                limitCents = limitCents,
                repeatMinutes = overview.settings.alerts.repeatMinutes,
                pauseMinutes = overview.settings.alerts.pauseMinutes,
                needsReference = overview.needsReference,
                alertCycleId = activeCycle?.id,
                alertOpenedAtMs = activeCycle?.openedAtMs,
                alertEmissionCount = activeCycle?.emissionCount ?: 0,
                lastObservedAtMs = overview.lastObservedAtMs,
                referenceConfirmedAtMs = overview.reference?.confirmedAtMs,
                entries = recentEntries.map { entry ->
                    SharedHistoryEntry(
                        hostEntryId = entry.id,
                        kind = entry.kind,
                        amountCents = entry.amountCents,
                        balanceAfterCents = entry.balanceAfterCents,
                        recordedAtMs = entry.recordedAtMs,
                    )
                },
            )

            syncClient.publishHostSnapshot(
                monitorId = session.monitorId,
                accessToken = session.accessToken,
                upload = upload,
            ) is SharedSyncResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val SYNC_HISTORY_LIMIT = 50
    }
}
