package com.luqman.decko

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Handles on a widget's edges to stretch it cell by cell, within the sizes the widget allows. It covers the
 * home screen while shown; a tap away from the handles finishes. [onCommit] gets the snapped anchor and span
 * and says whether they fit; if not, the frame springs back.
 */
@SuppressLint("ViewConstructor")
class WidgetResizeFrame(
    context: Context,
    private val onCommit: (x: Int, y: Int, spanX: Int, spanY: Int) -> Boolean,
    private val onDone: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.getColor(R.color.controls_text)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.controls_text) }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.getColor(R.color.icon_bg)
    }
    private val rect = RectF()
    private val committed = RectF()
    private var originX = 0f
    private var originY = 0f
    private var cellW = 1f
    private var cellH = 1f
    private var limits = intArrayOf(1, 1, HomeLayout.COLUMNS, HomeLayout.ROWS)
    private var horizontal = true
    private var vertical = true
    private var edge = NONE

    init {
        contentDescription = context.getString(R.string.resize_done)
    }

    /** Frames [widget] (on [page], anchored at cell [x],[y]) inside [root]. */
    fun show(root: ViewGroup, widget: View, page: CellLayout, x: Int, y: Int, spanX: Int, spanY: Int, info: AppWidgetProviderInfo, limits: IntArray) {
        val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
        val pagePos = IntArray(2).also { page.getLocationInWindow(it) }
        originX = (pagePos[0] - rootPos[0] + page.paddingLeft).toFloat()
        originY = (pagePos[1] - rootPos[1] + page.paddingTop).toFloat()
        cellW = page.cellW
        cellH = page.cellH
        this.limits = limits
        horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        committed.set(originX + x * cellW, originY + y * cellH, originX + (x + spanX) * cellW, originY + (y + spanY) * cellH)
        rect.set(committed)
        if (parent == null) root.addView(this, FrameLayout.LayoutParams(-1, -1))
        invalidate()
    }

    fun dismiss() {
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 16 * density
        canvas.drawRoundRect(rect, r, r, stroke)
        val h = HANDLE_DP * density
        if (horizontal) {
            handle(canvas, rect.left, rect.centerY(), h)
            handle(canvas, rect.right, rect.centerY(), h)
        }
        if (vertical) {
            handle(canvas, rect.centerX(), rect.top, h)
            handle(canvas, rect.centerX(), rect.bottom, h)
        }
    }

    private fun handle(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, handleFill)
        canvas.drawCircle(x, y, r, handleRing)
    }

    @SuppressLint("ClickableViewAccessibility") // TalkBack users resize from nothing; "Done resizing" ends it via click.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val grab = GRAB_DP * density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edge = when {
                    horizontal && abs(ev.x - rect.left) < grab && abs(ev.y - rect.centerY()) < rect.height() / 2 + grab -> LEFT
                    horizontal && abs(ev.x - rect.right) < grab && abs(ev.y - rect.centerY()) < rect.height() / 2 + grab -> RIGHT
                    vertical && abs(ev.y - rect.top) < grab && abs(ev.x - rect.centerX()) < rect.width() / 2 + grab -> TOP
                    vertical && abs(ev.y - rect.bottom) < grab && abs(ev.x - rect.centerX()) < rect.width() / 2 + grab -> BOTTOM
                    else -> NONE
                }
                if (edge == NONE) {
                    dismiss()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val minW = limits[0] * cellW
                val minH = limits[1] * cellH
                val maxW = limits[2] * cellW
                val maxH = limits[3] * cellH
                val gridRight = originX + HomeLayout.COLUMNS * cellW
                val gridBottom = originY + HomeLayout.ROWS * cellH
                when (edge) {
                    LEFT -> rect.left = ev.x.coerceIn(maxOf(originX, rect.right - maxW), rect.right - minW)
                    RIGHT -> rect.right = ev.x.coerceIn(rect.left + minW, minOf(gridRight, rect.left + maxW))
                    TOP -> rect.top = ev.y.coerceIn(maxOf(originY, rect.bottom - maxH), rect.bottom - minH)
                    BOTTOM -> rect.bottom = ev.y.coerceIn(rect.top + minH, minOf(gridBottom, rect.top + maxH))
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edge == NONE) return true
                edge = NONE
                val x = ((rect.left - originX) / cellW).roundToInt()
                val y = ((rect.top - originY) / cellH).roundToInt()
                val sx = ((rect.right - originX) / cellW).roundToInt() - x
                val sy = ((rect.bottom - originY) / cellH).roundToInt() - y
                if (sx >= 1 && sy >= 1 && onCommit(x, y, sx, sy)) {
                    committed.set(originX + x * cellW, originY + y * cellH, originX + (x + sx) * cellW, originY + (y + sy) * cellH)
                }
                rect.set(committed)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        dismiss()
        return true
    }

    private companion object {
        const val NONE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val TOP = 3
        const val BOTTOM = 4
        const val HANDLE_DP = 8
        const val GRAB_DP = 28
    }
}
