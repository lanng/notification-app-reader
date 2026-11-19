package br.com.c6tag.diagnostico.remote.probe

import android.content.Context

internal object ProbeDependencies {
    @Volatile
    var controllerFactory: (Context) -> ProbeNotificationController = { context ->
        ProbeNotificationController(context, ProbeSessionStore(context.applicationContext))
    }
}
