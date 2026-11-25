package br.com.c6tag.diagnostico.remote.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.c6tag.diagnostico.CollectorApplication
import java.util.concurrent.TimeUnit

internal interface GuestSyncScheduler {
    fun ensurePeriodicSync()
    fun scheduleNextCheck(delayMs: Long)
    fun cancelAll()
}

internal class WorkManagerGuestSyncScheduler(
    private val workManager: WorkManager,
) : GuestSyncScheduler {
    override fun ensurePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SupportSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun scheduleNextCheck(delayMs: Long) {
        val clampedDelay = delayMs.coerceAtLeast(15_000L)
        val request = OneTimeWorkRequestBuilder<SupportSyncWorker>()
            .setInitialDelay(clampedDelay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            NEXT_CHECK_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(NEXT_CHECK_WORK_NAME)
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "support_shared_monitor_periodic_sync"
        const val NEXT_CHECK_WORK_NAME = "support_shared_monitor_next_check"
    }
}

class SupportSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CollectorApplication ?: return Result.success()
        app.guestMonitorRepository.syncNow()
        return Result.success()
    }
}
