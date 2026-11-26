package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.CollectorApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SupportAlertMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext as? CollectorApplication ?: return
        app.onFcmTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext as? CollectorApplication ?: return
        app.onFcmMessageReceived()
    }
}
