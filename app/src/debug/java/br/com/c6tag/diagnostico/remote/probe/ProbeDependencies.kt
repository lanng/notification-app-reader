package br.com.c6tag.diagnostico.remote.probe

import android.content.Context
import android.app.Activity

internal object ProbeDependencies {
    @Volatile
    var fullScreenAccessFactory: (Context) -> ProbeFullScreenAccess = { context ->
        AndroidProbeFullScreenAccess(context.applicationContext)
    }

    @Volatile
    var controllerFactory: (Context, ProbeFullScreenAccess) -> ProbeNotificationController =
        { context, fullScreenAccess ->
            ProbeNotificationController(
                context,
                ProbeSessionStore(context.applicationContext),
                fullScreenAccessOverride = fullScreenAccess,
            )
        }

    @Volatile
    var delayedTriggerFactory: (Context, () -> Unit) -> ProbeDelayedTrigger = { _, submit ->
        ProbeDelayedTrigger(HandlerProbeDelayScheduler(), submit)
    }

    @Volatile
    var windowPresentationFactory: (Activity) -> ProbeWindowPresentation = { activity ->
        AndroidProbeWindowPresentation(activity)
    }
}
