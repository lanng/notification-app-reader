package br.com.c6tag.diagnostico.remote.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

class ProbeStopReceiver() : BroadcastReceiver() {
    internal var controllerFactory: (Context) -> ProbeNotificationController =
        { context -> ProbeNotificationController(context, ProbeSessionStore(context)) }

    internal constructor(
        controllerFactory: (Context) -> ProbeNotificationController,
    ) : this() {
        this.controllerFactory = controllerFactory
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProbeNotificationController.STOP_ACTION) return
        val occurrenceId = intent.getStringExtra(
            ProbeNotificationController.EXTRA_OCCURRENCE_ID,
        ) ?: return
        if (!occurrenceId.isCanonicalUuid()) return

        controllerFactory(context.applicationContext).stop(occurrenceId)
    }

    private fun String.isCanonicalUuid(): Boolean = runCatching {
        UUID.fromString(this).toString() == this
    }.getOrDefault(false)

    companion object {
        const val ACTION_STOP = ProbeNotificationController.STOP_ACTION
        const val EXTRA_OCCURRENCE_ID = ProbeNotificationController.EXTRA_OCCURRENCE_ID
    }
}
