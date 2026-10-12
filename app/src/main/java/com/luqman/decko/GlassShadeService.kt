package com.luqman.decko

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Glass's notification and control centers over every app. Android hands a pull that starts on the status
 * bar straight to the system shade; only an accessibility overlay sits above the status bar, so this
 * service lays an invisible strip there and opens [GlassPanels] in a full-screen overlay when it's pulled.
 * Active only while Glass is the chosen style, and never on the lock screen.
 */
class GlassShadeService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var strip: View? = null
    private var host: FrameLayout? = null
    private var panels: GlassPanels? = null

    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> update() }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        Prefs.observe(this, prefsChanged)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        update()
    }

    override fun onDestroy() {
        if (::wm.isInitialized) {
            Prefs.unobserve(this, prefsChanged)
            runCatching { unregisterReceiver(screen) }
            closeNow()
            removeStrip()
        }
        super.onDestroy()
    }

    /** Another app came forward (Home, a launch from the panel, a notification's tap): the shade gets out of the way. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || panels == null) return
        if (event.packageName != packageName) panels?.close()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the status bar's height; a night-mode switch rebuilds the panes in the new colors.
        closeNow()
        removeStrip()
        update()
    }

    /** The strip is up only with Glass chosen and the phone unlocked: the shade shows notifications. */
    private fun update() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val want = Prefs.homeStyle(this) == Prefs.STYLE_GLASS && !locked && getSystemService(PowerManager::class.java).isInteractive
        if (want && strip == null) addStrip() else if (!want) {
            closeNow()
            removeStrip()
        }
    }

    private fun overlayParams(height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun addStrip() {
        val v = Strip(this)
        v.contentDescription = getString(R.string.shade_strip)
        runCatching { wm.addView(v, overlayParams(barInsets().first, focusable = false)) }.onSuccess { strip = v }
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }

    /** Status bar (and cutout) height on top, navigation bar at the bottom, whether or not they're showing. */
    private fun barInsets(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
            )
            return i.top.coerceAtLeast((MIN_STRIP_DP * density).toInt()) to i.bottom
        }
        @SuppressLint("DiscouragedApi", "InternalInsetResource") // No public API for the bar height before 30.
        fun dimen(name: String) = resources.getIdentifier(name, "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        return dimen("status_bar_height").coerceAtLeast((MIN_STRIP_DP * density).toInt()) to dimen("navigation_bar_height")
    }

    /** Glass's colors, in the light/dark the user fixed for Glass or the system's. */
    private fun glassContext(): Context = ContextThemeWrapper(this, R.style.Theme_Decko_Launcher_Glass).apply {
        val mode = Prefs.glassMode(this@GlassShadeService)
        if (mode != Prefs.MODE_AUTO) {
            applyOverrideConfiguration(Configuration().apply {
                val night = if (mode == Prefs.MODE_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                uiMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
        }
    }

    /** Puts up the full-screen window the panes live in, built fresh so they pick up the current colors. */
    private fun openHost(): GlassPanels {
        panels?.let { return it }
        val ctx = glassContext()
        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) panels?.close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val p = GlassPanels(ctx, frame, onHidden = { frame.post(::closeNow) })
        val (top, bottom) = barInsets()
        p.setInsets(top, bottom)
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, focusable = true)
        if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = (BLUR_RADIUS_DP * resources.displayMetrics.density).toInt()
        }
        wm.addView(frame, params)
        host = frame
        panels = p
        return p
    }

    private fun closeNow() {
        val frame = host ?: return
        host = null
        panels?.closeNow()
        panels = null
        runCatching { wm.removeView(frame) }
    }

    /** The invisible status-bar strip: a downward pull opens the left or right pane, like on Glass home. */
    private inner class Strip(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var pulling = false
        private var tracker: VelocityTracker? = null

        @SuppressLint("ClickableViewAccessibility") // Nothing to click: it only takes pulls.
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    pulling = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    val dy = ev.rawY - downY
                    if (!pulling && dy > slop && dy > abs(ev.rawX - downX)) {
                        pulling = runCatching { openHost().begin(downX, resources.displayMetrics.widthPixels) }.getOrDefault(false)
                        downY = ev.rawY
                    }
                    if (pulling) panels?.drag(ev.rawY - downY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        val v = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                        panels?.release(v, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    }
                    pulling = false
                    tracker?.recycle()
                    tracker = null
                }
            }
            return true
        }
    }

    companion object {
        private const val MIN_STRIP_DP = 24
        private const val BLUR_RADIUS_DP = 24

        fun component(ctx: Context) = ComponentName(ctx, GlassShadeService::class.java)

        /** The user has turned Decko on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
        }
    }
}
