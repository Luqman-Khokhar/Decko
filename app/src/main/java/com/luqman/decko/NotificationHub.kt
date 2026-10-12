package com.luqman.decko

import android.app.Activity
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notifications for Glass's notification center. The listener ships disabled, so Decko doesn't appear in
 * Android's notification-access list (or trip Play Protect) until the user turns the center on.
 */
object NotificationHub {
    /** Shown notifications: no group summaries, newest first. */
    var items: List<StatusBarNotification> = emptyList()
        private set

    private var listener: DeckoNotificationListener? = null
    private val observers = LinkedHashSet<() -> Unit>()

    fun component(ctx: Context) = ComponentName(ctx, DeckoNotificationListener::class.java)

    fun observe(cb: () -> Unit) { observers += cb }

    fun unobserve(cb: () -> Unit) { observers -= cb }

    val isConnected get() = listener != null

    /** The user turned the center on and Android granted access. */
    fun isGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
    }

    /** Turns the listener on and opens Android's access screen for it. */
    fun requestAccess(activity: Activity) {
        activity.packageManager.setComponentEnabledSetting(
            component(activity), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(activity).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        runCatching { activity.startActivity(intent) }.onFailure { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    fun dismiss(sbn: StatusBarNotification) {
        listener?.cancelNotification(sbn.key)
    }

    fun dismissAll() {
        listener?.cancelAllNotifications()
    }

    internal fun attach(l: DeckoNotificationListener?) {
        listener = l
        refresh(l)
    }

    internal fun refresh(l: DeckoNotificationListener?) {
        val active = runCatching { l?.activeNotifications }.getOrNull().orEmpty()
        items = active.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
        observers.toList().forEach { it() }
    }
}

/** Mirrors the phone's notifications into [NotificationHub] while the user has granted access. */
class DeckoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = NotificationHub.attach(this)

    override fun onListenerDisconnected() = NotificationHub.attach(null)

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotificationHub.refresh(this)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotificationHub.refresh(this)
}
