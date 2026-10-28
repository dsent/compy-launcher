/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */
package toys.compy.launcher

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Current notification presence for the maintenance bell. */
class MaintenanceNotificationListener : NotificationListenerService() {
    private var connected = false

    override fun onListenerConnected() {
        connected = true
        refreshPresence()
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        refreshPresence()
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        refreshPresence()
    }

    override fun onListenerDisconnected() {
        connected = false
        hasActiveNotifications = null
        requestRebind(ComponentName(this, MaintenanceNotificationListener::class.java))
    }

    override fun onDestroy() {
        connected = false
        hasActiveNotifications = null
        super.onDestroy()
    }

    private fun refreshPresence() {
        if (!connected) return
        hasActiveNotifications = try {
            activeNotifications?.isNotEmpty()
        } catch (error: RuntimeException) {
            Log.w("CompyNotifications", "Could not read notification presence", error)
            null
        }
    }

    companion object {
        @Volatile var hasActiveNotifications: Boolean? = null
            private set
    }
}
