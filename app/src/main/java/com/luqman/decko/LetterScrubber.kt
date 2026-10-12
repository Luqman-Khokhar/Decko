package com.luqman.decko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The A–Z strip beside the app list. Sliding a finger along it jumps the list letter by letter with a
 * light tick; [onLetter] gets the letter and the finger's y (for the preview bubble), or null on release.
 */
class LetterScrubber @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Letters present in the list, in order; only these are shown. */
    var letters: List<Char> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }

    var onLetter: (Char?, Float) -> Unit = { _, _ -> }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var active = -1

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    var activeColor = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // The list itself is fully navigable.
    }

    private fun step() = if (letters.isEmpty()) 1f else (height - paddingTop - paddingBottom) / letters.size.toFloat()

    override fun onDraw(canvas: Canvas) {
        val step = step()
        val baseline = (paint.textSize - paint.descent()) / 2
        val normal = paint.color
        letters.forEachIndexed { i, c ->
            paint.color = if (i == active) activeColor else normal
            canvas.drawText(c.toString(), width / 2f, paddingTop + step * (i + 0.5f) + baseline, paint)
        }
        paint.color = normal
    }

    @SuppressLint("ClickableViewAccessibility") // Hidden from accessibility; the list scrolls normally.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((e.y - paddingTop) / step()).toInt().coerceIn(0, letters.size - 1)
                if (i != active) {
                    active = i
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                onLetter(letters[i], e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
                onLetter(null, e.y)
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((WIDTH_DP * density).toInt(), widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private companion object {
        const val WIDTH_DP = 24
    }
}
