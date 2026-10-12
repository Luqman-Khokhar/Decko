package com.luqman.decko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A tall glass pill that fills from the bottom, like iOS's brightness and volume sliders. Drag anywhere
 * on it to set [value] 0..1; [onChange] fires while dragging. TalkBack sees it as a seek control.
 * With [trackInsetDp] the glass is a card and the fill runs in a narrower pill inside it, shaded top to
 * bottom by [fillColors].
 */
@SuppressLint("ViewConstructor")
class GlassSlider(
    context: Context,
    private val icon: Drawable?,
    label: String,
    private val trackInsetDp: Float = 0f,
    private val fillColors: IntArray? = null,
) : View(context) {
    var value = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c == field) return
            field = c
            invalidate()
        }

    var onChange: (Float) -> Unit = {}

    /** Asked before the first change; return false to block it (e.g. while a permission is missing). */
    var canChange: () -> Boolean = { true }

    private val density = resources.displayMetrics.density
    private val clip = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.glass_text) }
    private val iconInk = fill.color
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.cc_track) }
    private val inset = trackInsetDp > 0f
    private var startValue = 0f
    private var startY = 0f
    private var lastTick = -1

    init {
        background = GlassDrawable(context, if (inset) CARD_RADIUS_DP else RADIUS_DP, sampleBackdrop = false)
        contentDescription = label
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        var r = RADIUS_DP * density
        if (inset) {
            rect.inset(trackInsetDp * density, trackInsetDp * TRACK_INSET_Y * density)
            r = rect.width() / 2
        }
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        fillColors?.let { fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, it[0], it[1], Shader.TileMode.CLAMP) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        if (inset) canvas.drawRect(rect, track)
        val level = rect.bottom - rect.height() * value
        canvas.drawRect(rect.left, level, rect.right, rect.bottom, fill)
        canvas.restore()
        icon?.let {
            val s = ((if (inset) INSET_ICON_DP else ICON_DP) * density).toInt()
            val left = (width - s) / 2
            val top = rect.bottom.toInt() - s - ((if (inset) 12 else 16) * density).toInt()
            it.setBounds(left, top, left + s, top + s)
            // Drawn twice: ink over the glass, inverse where the fill covers it, so it reads at any level.
            it.setTint(iconInk)
            it.draw(canvas)
            canvas.save()
            canvas.clipRect(rect.left, level, rect.right, rect.bottom)
            it.setTint(context.getColor(R.color.glass_inverse))
            it.draw(canvas)
            canvas.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Value changes are exposed through accessibility actions below.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!canChange()) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                startValue = value
                startY = e.y
                animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
            }
            MotionEvent.ACTION_MOVE -> set(startValue + (startY - e.y) / rect.height().coerceAtLeast(1f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        return true
    }

    private fun set(v: Float) {
        value = v
        val tick = (value * 10).toInt()
        if (tick != lastTick) {
            lastTick = tick
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onChange(value)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value * 100)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!canChange()) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { set(value + 0.1f); true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { set(value - 0.1f); true }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    private companion object {
        const val RADIUS_DP = 30f
        const val ICON_DP = 26
        const val CARD_RADIUS_DP = 26f
        const val INSET_ICON_DP = 20
        const val TRACK_INSET_Y = 0.75f
    }
}
