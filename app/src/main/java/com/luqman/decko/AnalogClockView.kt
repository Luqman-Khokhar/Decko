package com.luqman.decko

import android.content.Context
import android.graphics.Canvas
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var clockTheme: ClockTheme = ClockTheme.CLASSIC
        set(value) { field = value; requestLayout(); invalidate() }

    /** Frozen time for design previews; null means live. */
    private var fixed: Triple<Int, Int, Int>? = null
    private var lastMinute = -1
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    fun showFixedTime(h: Int, m: Int, s: Int) {
        fixed = Triple(h, m, s)
        updateTicking()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        ticking = isVisible
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun updateTicking() {
        removeCallbacks(ticker)
        if (ticking && fixed == null) post(ticker)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = when {
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            else -> minOf(w, h)
        }
        val aspect = clockTheme.aspect
        val wide = aspect < 1f && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        setMeasuredDimension(
            resolveSize(side, widthMeasureSpec),
            resolveSize(if (wide) (w * aspect).toInt() else side, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        val half = minOf(availW, availH / clockTheme.aspect) / 2f
        if (half <= 0) return

        val (h, m, s) = fixed ?: Calendar.getInstance().let {
            Triple(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.SECOND))
        }
        if (fixed == null && m != lastMinute) {
            lastMinute = m
            contentDescription = DateFormat.getTimeFormat(context).format(System.currentTimeMillis())
        }
        ClockRenderer.drawDial(canvas, cx, cy, half, clockTheme)
        ClockRenderer.drawHands(canvas, cx, cy, half, clockTheme, h, m, s.toFloat(), is24 = DateFormat.is24HourFormat(context))
    }
}
