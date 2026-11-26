package br.com.c6tag.diagnostico.remote.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.c6tag.diagnostico.CollectorApplication

class CriticalAlertStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
        when (intent.action) {
            ACTION_STOP -> {
                CriticalAlertController.stopAndCancel(context, occurrenceId)
            }

            ACTION_ACKNOWLEDGE -> {
                CriticalAlertController.stopAndCancel(context, occurrenceId)
                val app = context.applicationContext as? CollectorApplication ?: return
                val isGuest = intent.getBooleanExtra(EXTRA_IS_GUEST, false)
                val cycleId = intent.getStringExtra(EXTRA_CYCLE_ID)
                val pendingResult = goAsync()
                app.receiveCriticalAlertAcknowledge(isGuest, cycleId, pendingResult::finish)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "br.com.c6tag.diagnostico.action.CRITICAL_ALARM_STOP"
        const val ACTION_ACKNOWLEDGE = "br.com.c6tag.diagnostico.action.CRITICAL_ALARM_ACKNOWLEDGE"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_IS_GUEST = "is_guest"
        const val EXTRA_CYCLE_ID = "cycle_id"
    }
}
