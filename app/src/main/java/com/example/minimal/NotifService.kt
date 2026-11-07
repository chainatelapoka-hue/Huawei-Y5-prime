package com.example.minimal

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object Badges {
    @Volatile
    var counts: Map<String, Int> = emptyMap()
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val map = HashMap<String, Int>()
        try {
            activeNotifications?.forEach { map[it.packageName] = (map[it.packageName] ?: 0) + 1 }
        } catch (e: Exception) {
        }
        Badges.counts = map
    }
}
