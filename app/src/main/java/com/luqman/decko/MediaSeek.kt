package com.luqman.decko

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The now-playing tile's progress line: a thin track, the played part in a light hue, and a knob. Dragging
 * previews the spot and [onSeek] fires on release (0..1); TalkBack sees a seek control.
 */
@SuppressLint("ViewConstructor")
class MediaSeek(private val kit: CcKit) : View(kit.ui) {
    var fraction = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c == field || dragging) return
            field = c
            invalidate()
        }

    var onSeek: (Float) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.ui.getColor(R.color.cc_track) }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.ui.getColor(R.color.cc_fill_bright) }
    private var dragging = false

    init {
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        played.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, kit.ui.getColor(R.color.cc_on_text), kit.ui.getColor(R.color.cc_fill_cool), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = KNOB_DP * density / 2
        val line = LINE_DP * density / 2
        val cy = height / 2f
        val start = r
        val end = width - r
        val x = start + (end - start) * fraction
        canvas.drawRoundRect(start, cy - line, end, cy + line, line, line, track)
        canvas.drawRoundRect(start, cy - line, x, cy + line, line, line, played)
        if (isEnabled) canvas.drawCircle(x, cy, r, knob)
    }

    @SuppressLint("ClickableViewAccessibility") // Seeking is exposed through accessibility actions below.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val r = KNOB_DP * density / 2
        val f = ((e.x - r) / (width - 2 * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                preview(f)
            }
            MotionEvent.ACTION_MOVE -> preview(f)
            MotionEvent.ACTION_UP -> {
                dragging = false
                onSeek(fraction)
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun preview(f: Float) {
        // Set directly: the public setter ignores playback updates while a finger is on the line.
        val was = dragging
        dragging = false
        fraction = f
        dragging = was
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, fraction * 100)
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { fraction += STEP; onSeek(fraction); true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { fraction -= STEP; onSeek(fraction); true }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    private companion object {
        const val KNOB_DP = 12f
        const val LINE_DP = 3f
        const val STEP = 0.05f
    }
}
