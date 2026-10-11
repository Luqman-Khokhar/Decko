package com.luqman.decko

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets the header flash when a notification arrives. Only active once the user grants notification access. */
class HeaderNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if (Prefs.header(this).trigger != HeaderConfig.TRIGGER_NOTIFICATION) return
        sendBroadcast(Intent(HeaderOverlayService.ACTION_FLASH).setPackage(packageName))
    }
}
