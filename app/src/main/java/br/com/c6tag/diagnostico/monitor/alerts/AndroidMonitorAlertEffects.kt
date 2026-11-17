package br.com.c6tag.diagnostico.monitor.alerts

import br.com.c6tag.diagnostico.monitor.MonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.MonitorOverview

internal class AndroidMonitorAlertEffects(
    private val notifier: MonitorNotifier,
    private val scheduler: LowBalanceScheduler,
) : MonitorAlertEffects {
    override fun synchronize(overview: MonitorOverview) {
        if (overview.alertCycle?.status != AlertStatus.ACTIVE) notifier.cancel()
        scheduler.synchronize(overview)
    }

    override fun submit(overview: MonitorOverview, intent: EmissionIntent) = notifier.submit(overview, intent)
}
