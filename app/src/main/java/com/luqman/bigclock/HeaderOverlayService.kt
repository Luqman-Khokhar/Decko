package com.luqman.bigclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Keeps the header animation on screen: a click-through overlay window over the status-bar strip.
 * It pauses itself when the screen is off, the trigger (charging / notification) is not met, or a fullscreen app hides the bar.
 */
class HeaderOverlayService : Service() {
    private lateinit var wm: WindowManager
    private var view: HeaderOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private var screenOn = true
    private var charging = false
    private var flashUntil = 0L
    private var barVisible = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                ACTION_FLASH -> {
                    flashUntil = SystemClock.uptimeMillis() + FLASH_MS
                    handler.postDelayed({ updateVisibility() }, FLASH_MS + 100)
                }
            }
            updateVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        charging = getSystemService(BatteryManager::class.java).isCharging
        screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        startAsForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_FLASH)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        listener = Prefs.onHeaderChanged(this) { handler.post { onConfigChanged() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDraw(this) || !Prefs.header(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) addOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        listener?.let { Prefs.removeHeaderListener(this, it) }
        runCatching { unregisterReceiver(receiver) }
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.header_channel), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HeaderActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.header_notification_title))
            .setContentText(getString(R.string.header_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    private fun stripHeightPx(config: HeaderConfig) =
        maxOf(statusBarHeightPx(), ((config.sizeDp + 4) * resources.displayMetrics.density).toInt())

    private fun addOverlay() {
        val config = Prefs.header(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeightPx(config),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "BigClock header"
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = HeaderOverlayView(this)
        v.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.boundingRects?.firstOrNull { it.top <= 0 }?.let {
                    (target as HeaderOverlayView).cutoutCenterX = it.exactCenterX()
                    target.cutoutCenterY = it.exactCenterY()
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val visible = insets.isVisible(WindowInsets.Type.statusBars())
                if (visible != barVisible) {
                    barVisible = visible
                    handler.post { updateVisibility() }
                }
            }
            insets
        }
        v.apply(config)
        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        view = v
        layoutParams = params
        updateVisibility()
    }

    private fun removeOverlay() {
        view?.let { v ->
            v.setActive(false)
            runCatching { wm.removeView(v) }
        }
        view = null
    }

    private fun onConfigChanged() {
        val config = Prefs.header(this)
        if (!config.enabled || !canDraw(this)) {
            stopSelf()
            return
        }
        val v = view ?: return
        v.apply(config)
        layoutParams?.let {
            val h = stripHeightPx(config)
            if (it.height != h) {
                it.height = h
                runCatching { wm.updateViewLayout(v, it) }
            }
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val v = view ?: return
        val config = Prefs.header(this)
        val triggered = when (config.trigger) {
            HeaderConfig.TRIGGER_CHARGING -> charging
            HeaderConfig.TRIGGER_NOTIFICATION -> SystemClock.uptimeMillis() < flashUntil
            else -> true
        }
        val show = screenOn && triggered && (barVisible || !config.hideFullscreen)
        v.visibility = if (show) android.view.View.VISIBLE else android.view.View.INVISIBLE
        v.setActive(show)
    }

    companion object {
        const val ACTION_FLASH = "com.luqman.bigclock.HEADER_FLASH"
        private const val CHANNEL = "header"
        private const val NOTIFICATION_ID = 7
        private const val FLASH_MS = 6000L

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        /** Starts or stops the service to match the saved setting. Safe to call from boot and from the UI. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, HeaderOverlayService::class.java)
            if (Prefs.header(ctx).enabled && canDraw(ctx)) {
                runCatching { ctx.startForegroundService(intent) }
            } else {
                ctx.stopService(intent)
            }
        }

        fun overlayPermissionIntent(ctx: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    }
}
