package br.com.c6tag.diagnostico.monitor.alerts

import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import java.util.concurrent.TimeUnit

internal class LowBalanceScheduler(private val workManager: WorkManager) {
    // Called only from the coordinator's IO lane, never the UI/main thread.
    fun synchronize(overview: MonitorOverview) {
        val cycle = overview.alertCycle?.takeIf { it.status != AlertStatus.CLOSED }
        if (cycle == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get(10, TimeUnit.SECONDS)
            return
        }
        val minutes = overview.settings.alerts.repeatMinutes
        require(minutes in setOf(15, 30, 60)) { "Unsupported reminder interval" }
        val current = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get(10, TimeUnit.SECONDS)
            .singleOrNull { !it.state.isFinished }
        val cycleTag = "$CYCLE_TAG_PREFIX${cycle.id}"
        val sameCycle = current != null && cycleTag in current.tags
        if (sameCycle && current!!.periodicityInfo?.repeatIntervalMillis == TimeUnit.MINUTES.toMillis(minutes.toLong())) return
        if (current != null && !sameCycle) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get(10, TimeUnit.SECONDS)
        }
        val request = PeriodicWorkRequestBuilder<LowBalanceWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .addTag(MONITOR_TAG)
            .addTag(cycleTag)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            if (sameCycle) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        ).result.get(10, TimeUnit.SECONDS)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "monitor_low_balance"
        private const val MONITOR_TAG = "monitor_local_balance"
        private const val CYCLE_TAG_PREFIX = "monitor_cycle:"
    }
}
