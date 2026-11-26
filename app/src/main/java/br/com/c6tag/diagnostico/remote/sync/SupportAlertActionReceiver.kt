package br.com.c6tag.diagnostico.remote.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.c6tag.diagnostico.CollectorApplication

class SupportAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        val app = context.applicationContext as? CollectorApplication ?: return
        val pendingResult = goAsync()
        app.receiveGuestAlertAction(intent, pendingResult::finish)
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "br.com.c6tag.diagnostico.action.GUEST_ALERT_ACKNOWLEDGE"
    }
}
