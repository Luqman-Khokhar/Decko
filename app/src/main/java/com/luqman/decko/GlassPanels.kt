package com.luqman.decko

import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Glass's pull-down panels: pulling down on the left half of home brings the notification center, on the
 * right half the control center. A full-screen frosted pane fades in while the content drops into place
 * on a spring; pulling up, Back, Home or a tap on empty glass sends it away.
 */
class GlassPanels(private val activity: Activity, private val root: HomeGestureLayout) : HomeGestureLayout.PullDown {
    private val overlay = PanelLayout(activity)
    private val notifications = NotificationCenter(activity, ::close)
    private val controls = ControlCenter(activity, ::close)
    private var current: Panel? = null
    private val density = activity.resources.displayMetrics.density
    private val spring = Spring(SPRING_STIFFNESS, SPRING_DAMPING, onUpdate = ::apply, onEnd = { if (progress <= 0f) hide() })

    /** 0 = hidden, 1 = fully down. */
    private var progress = 0f
    val isOpen get() = progress > 0f

    /** One panel's view and its lifecycle while on screen. */
    interface Panel {
        val view: View
        fun onShow()
        fun onHide()
        fun canScrollDown(): Boolean
    }

    init {
        overlay.background = GlassDrawable(activity, 0f)
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.contentDescription = activity.getString(R.string.close_panel)
        overlay.setOnClickListener { close() }
        for (p in listOf<Panel>(notifications, controls)) {
            p.view.visibility = View.GONE
            overlay.addView(p.view, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    fun setInsets(top: Int, bottom: Int) {
        overlay.setPadding(0, top, 0, bottom)
    }

    override fun begin(x: Float, width: Int): Boolean {
        show(if (x < width / 2f) notifications else controls)
        return true
    }

    override fun drag(distance: Float) {
        spring.cancel()
        apply(distance / pullDistance())
    }

    override fun release(velocity: Float, cancelled: Boolean) {
        val fling = FLING_DP_PER_S * density
        val open = !cancelled && (velocity > fling || (velocity > -fling && progress > OPEN_AT))
        spring.animateTo(if (open) 1f else 0f, progress, velocity / pullDistance())
    }

    fun close() {
        if (!isOpen) return
        spring.animateTo(0f, progress)
    }

    fun closeNow() {
        spring.cancel()
        apply(0f)
        hide()
    }

    private fun pullDistance() = root.height * PULL_FRACTION

    private fun show(panel: Panel) {
        if (current !== panel) {
            current?.let { it.view.visibility = View.GONE; it.onHide() }
            current = panel
            panel.view.visibility = View.VISIBLE
            panel.onShow()
        }
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    private fun hide() {
        overlay.visibility = View.GONE
        current?.let { it.view.visibility = View.GONE; it.onHide() }
        current = null
    }

    /** The pane fades in; the content drops from a little above, growing slightly, and fades in a beat later. */
    private fun apply(p: Float) {
        progress = p.coerceAtLeast(0f)
        val shown = progress.coerceAtMost(1f)
        overlay.alpha = shown
        current?.view?.apply {
            translationY = -(1 - shown) * DROP_DP * density + (progress - shown) * OVERPULL_DP * density
            scaleX = 0.94f + 0.06f * shown
            scaleY = scaleX
            alpha = ((shown - 0.15f) / 0.85f).coerceIn(0f, 1f)
        }
        GlassStyle.blurBehind(activity, progress > 0f)
        GlassStyle.coverBars(activity, progress > 0.4f)
    }

    /** Pulling up on an open panel (once its content is scrolled to the end) slides it away. */
    private inner class PanelLayout(activity: Activity) : FrameLayout(activity) {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            track(ev)
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility") // Taps still reach performClick (close) via the click listener.
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            track(ev)
            return super.onTouchEvent(ev) || true
        }

        private fun track(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    val dy = ev.y - downY
                    if (!dragging && dy < -slop && abs(dy) > abs(ev.x - downX) && current?.canScrollDown() != true) {
                        dragging = true
                        downY = ev.y
                        spring.cancel()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) apply(1f + (ev.y - downY) / pullDistance())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val v = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                        release(v, ev.actionMasked == MotionEvent.ACTION_CANCEL && progress < 1f)
                    }
                    dragging = false
                    tracker?.recycle()
                    tracker = null
                }
            }
        }
    }

    private companion object {
        const val PULL_FRACTION = 0.42f
        const val OPEN_AT = 0.35f
        const val FLING_DP_PER_S = 450
        const val DROP_DP = 64
        const val OVERPULL_DP = 40
        const val SPRING_STIFFNESS = 300f
        const val SPRING_DAMPING = 0.84f
    }
}
