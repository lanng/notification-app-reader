package br.com.c6tag.diagnostico.monitor.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.c6tag.diagnostico.CollectorApplication
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.MonitorResult

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (cycleId(intent) == null) return
        val application = context.applicationContext as? CollectorApplication ?: return
        val pending = goAsync() ?: return
        application.receiveAlertAction(Intent(intent)) { pending.finish() }
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "br.com.c6tag.diagnostico.ACKNOWLEDGE_ALERT"
        const val EXTRA_CYCLE_ID = "cycle_id"

        internal suspend fun dispatch(intent: Intent, coordinator: MonitorCoordinator): MonitorResult? {
            val id = cycleId(intent) ?: return null
            return coordinator.acknowledge(id)
        }

        private fun cycleId(intent: Intent): String? {
            if (intent.action != ACTION_ACKNOWLEDGE ||
                intent.component?.className != AlertActionReceiver::class.java.name) return null
            val uri = intent.data ?: return null
            if (uri.scheme != "c6tag-monitor" || uri.authority != "alert" ||
                uri.pathSegments.size != 2 || uri.pathSegments[1] != "acknowledge") return null
            val id = uri.pathSegments[0]
            if (id.isBlank() || id.length > 200 || intent.getStringExtra(EXTRA_CYCLE_ID) != id) return null
            return id
        }
    }
}
