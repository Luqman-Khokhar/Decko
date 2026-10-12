package com.luqman.decko

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/** Home pages side by side; a horizontal swipe moves between them and snaps to the nearest page. */
class HomePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Called with the scroll position in pages, e.g. 1.5 = halfway from page 1 to page 2. */
    var onScroll: (Float) -> Unit = {}

    var currentPage = 0
        private set

    /** Styles move pages in depth as they scroll; called with each page and its offset from center. */
    var pageTransform: ((View, Float) -> Unit)? = null

    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val flingVelocity = FLING_DP_PER_S * resources.displayMetrics.density
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (changed && scroller.isFinished) scrollTo(currentPage * w, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollTo(currentPage * w, 0)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) {
            val position = l / width.toFloat()
            onScroll(position)
            pageTransform?.let { t -> for (i in 0 until childCount) t(getChildAt(i), i - position) }
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    fun snapTo(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        currentPage = target
        val dx = target * width - scrollX
        scroller.forceFinished(true)
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || width == 0) {
            scrollTo(target * width, 0)
        } else {
            scroller.startScroll(scrollX, 0, dx, 0, (SNAP_MS * abs(dx) / width.toFloat()).toInt().coerceAtLeast(MIN_SNAP_MS))
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    @Suppress("ClickableViewAccessibility") // Pages are also reachable by focus traversal.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                // Catching a page mid-snap continues it as a drag.
                dragging = !scroller.isFinished
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - downX
                if (!dragging && abs(dx) > slop && abs(dx) > abs(ev.y - downY) && childCount > 1) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val max = (childCount - 1) * width
                    var delta = lastX - ev.x
                    // Rubber-band past the first and last page.
                    if (scrollX + delta < 0 || scrollX + delta > max) delta *= EDGE_RESISTANCE
                    scrollBy(delta.roundToInt(), 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && width > 0) {
                    val v = tracker?.run { computeCurrentVelocity(1000); xVelocity } ?: 0f
                    val nearest = (scrollX / width.toFloat()).roundToInt()
                    snapTo(when {
                        v < -flingVelocity -> (scrollX / width) + 1
                        v > flingVelocity -> (scrollX + width - 1) / width - 1
                        else -> nearest
                    })
                }
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
    }

    private companion object {
        const val FLING_DP_PER_S = 400
        const val SNAP_MS = 320
        const val MIN_SNAP_MS = 120
        const val EDGE_RESISTANCE = 0.35f
    }
}

/** One dot per home page; the current one is filled, and the fill slides with the swipe. */
class PageIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var count = 1
        set(value) {
            field = value.coerceAtLeast(1)
            if (hideWhenSingle) visibility = if (field < 2) INVISIBLE else VISIBLE
            requestLayout()
            invalidate()
        }

    /** Glass shows the dots in a pill, which shouldn't float empty over a single page. */
    var hideWhenSingle = false

    /** Scroll position in pages, from [HomePager.onScroll]. */
    var position = 0f
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.controls_text_muted) }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.controls_text) }

    fun setColors(dotColor: Int, activeColor: Int) {
        dot.color = dotColor
        active.color = activeColor
        invalidate()
    }

    /** Wrap-content width fits the dots and padding, for a pill around them. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = ((count - 1) * GAP_DP + 2 * DOT_DP * 1.4f) * density + paddingLeft + paddingRight
        setMeasuredDimension(resolveSize(wanted.toInt(), widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val r = DOT_DP * density
        val gap = GAP_DP * density
        val start = (width - gap * (count - 1)) / 2
        val cy = height / 2f
        for (i in 0 until count) canvas.drawCircle(start + i * gap, cy, r, dot)
        canvas.drawCircle(start + position.coerceIn(0f, count - 1f) * gap, cy, r * 1.4f, active)
    }

    private companion object {
        const val DOT_DP = 3f
        const val GAP_DP = 12f
    }
}
