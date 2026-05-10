package ai.opencray.app.execution

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class OpenTHUNotificationListenerService : NotificationListenerService() {

    companion object {
        var instance: OpenTHUNotificationListenerService? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    fun getUnreadNotifications(): List<StatusBarNotification> {
        return try {
            activeNotifications?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
