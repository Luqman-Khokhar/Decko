package com.luqman.decko

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root of Decko Home. A vertical drag pulls the app drawer up over the home page or pushes it back down;
 * the drawer follows the finger, then settles open or closed by position and fling velocity.
 * Children: R.id.home and R.id.drawer.
 */
class HomeGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** True while the drawer's list can still scroll toward its top, so a downward drag scrolls instead of closing. */
    var drawerCanScrollUp: () -> Boolean = { false }
    var onDrawerStateChanged: (open: Boolean) -> Unit = {}

    /** 0 = home page, 1 = drawer fully open. */
    var progress = 0f
        private set
    val isDrawerOpen get() = progress > 0f

    private lateinit var home: View
    private lateinit var drawer: View
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = FLING_DP_PER_S * resources.displayMetrics.density
    private var downY = 0f
    private var startProgress = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var settledOpen = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        home = findViewById(R.id.home)
        drawer = findViewById(R.id.drawer)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        apply(progress)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    @Suppress("ClickableViewAccessibility") // The drawer also opens from the home page's "Apps" button.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    fun open(animate: Boolean = true) = settle(1f, animate)

    fun close(animate: Boolean = true) = settle(0f, animate)

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                // Catching the drawer mid-animation turns straight into a drag from where it is.
                dragging = animator?.isRunning == true
                animator?.cancel()
                startProgress = progress
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                if (!dragging && abs(dy) > slop && canStartDrag(dy)) {
                    dragging = true
                    startProgress = progress
                    downY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) apply(startProgress - (ev.y - downY) / height.coerceAtLeast(1))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val v = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                    val open = when {
                        ev.actionMasked == MotionEvent.ACTION_CANCEL -> progress > 0.5f
                        v < -flingVelocity -> true
                        v > flingVelocity -> false
                        else -> progress > 0.5f
                    }
                    settle(if (open) 1f else 0f, true)
                }
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
    }

    private fun canStartDrag(dy: Float) =
        (progress == 0f && dy < 0) || (progress == 1f && dy > 0 && !drawerCanScrollUp())

    private fun settle(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || progress == target) {
            apply(target)
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (MAX_DURATION_MS * abs(target - progress)).toLong().coerceAtLeast(MIN_DURATION_MS)
            interpolator = EMPHASIZED_DECELERATE
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { animator = null }
            })
            start()
        }
    }

    /** Drawer slides up and fades in; the home page sinks back slightly behind it. */
    private fun apply(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val h = height.toFloat()
        drawer.translationY = (1 - progress) * h
        drawer.alpha = progress
        drawer.visibility = if (progress == 0f) INVISIBLE else VISIBLE
        home.alpha = 1 - progress
        home.scaleX = 1 - HOME_SHRINK * progress
        home.scaleY = home.scaleX
        home.translationY = -HOME_LIFT * h * progress
        home.visibility = if (progress == 1f) INVISIBLE else VISIBLE
        if (progress == 1f && !settledOpen) {
            settledOpen = true
            onDrawerStateChanged(true)
        } else if (progress == 0f && settledOpen) {
            settledOpen = false
            onDrawerStateChanged(false)
        }
    }

    private companion object {
        const val FLING_DP_PER_S = 500
        const val MAX_DURATION_MS = 380L
        const val MIN_DURATION_MS = 140L
        const val HOME_SHRINK = 0.06f
        const val HOME_LIFT = 0.04f

        /** Material 3 "emphasized decelerate". */
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
