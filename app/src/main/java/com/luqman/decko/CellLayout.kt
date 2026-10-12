package com.luqman.decko

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** A grid of equal cells; each child sits at a cell and may span several. Shows where a dragged icon will land. */
class CellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** A block of cells: anchor [x],[y] and size [w]×[h]. */
    data class Span(val x: Int, val y: Int, val w: Int = 1, val h: Int = 1)

    class LayoutParams(val x: Int, val y: Int, val spanX: Int = 1, val spanY: Int = 1) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var columns = HomeLayout.COLUMNS
    var rows = HomeLayout.ROWS

    /** Cells outlined as the drop spot (a widget's whole footprint), or null. */
    var dropHint: Span? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Occupied cell the dragged app would join as a folder, drawn as a filled circle; or null. */
    var mergeHint: Pair<Int, Int>? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val hintRect = RectF()
    private val hintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.drop_hint) }
    private val hintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.getColor(R.color.controls_text)
    }

    init {
        setWillNotDraw(false)
    }

    val cellW get() = (width - paddingLeft - paddingRight) / columns.toFloat()
    val cellH get() = (height - paddingTop - paddingBottom) / rows.toFloat()

    /** Cell under a point in this view's coordinates, clamped to the grid. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        ((x - paddingLeft) / cellW).toInt().coerceIn(0, columns - 1) to ((y - paddingTop) / cellH).toInt().coerceIn(0, rows - 1)

    /** True in the middle of a cell, where hovering means "make a folder" rather than "land next to it". */
    fun isNearCenter(x: Float, y: Float): Boolean {
        val fx = ((x - paddingLeft) / cellW) % 1f
        val fy = ((y - paddingTop) / cellH) % 1f
        return fx in MERGE_ZONE && fy in MERGE_ZONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight) / columns.toFloat()
        val ch = (h - paddingTop - paddingBottom) / rows.toFloat()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec((cw * lp.spanX).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((ch * lp.spanY).toInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = (paddingLeft + lp.x * cellW).toInt()
            val top = (paddingTop + lp.y * cellH).toInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    private companion object {
        val MERGE_ZONE = 0.2f..0.8f
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams

    override fun generateDefaultLayoutParams() = LayoutParams(0, 0)

    /** Child at a cell, if any; spans count. */
    fun childAt(x: Int, y: Int): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull {
        val lp = it.layoutParams as LayoutParams
        x in lp.x until lp.x + lp.spanX && y in lp.y until lp.y + lp.spanY
    }

    override fun onDraw(canvas: Canvas) {
        mergeHint?.let { (x, y) ->
            val cx = paddingLeft + (x + 0.5f) * cellW
            val cy = paddingTop + (y + 0.5f) * cellH
            val r = minOf(cellW, cellH) * 0.46f
            canvas.drawCircle(cx, cy, r, hintFill)
            canvas.drawCircle(cx, cy, r, hintStroke)
        }
        val hint = dropHint ?: return
        val inset = 4 * density
        hintRect.set(
            paddingLeft + hint.x * cellW + inset, paddingTop + hint.y * cellH + inset,
            paddingLeft + (hint.x + hint.w) * cellW - inset, paddingTop + (hint.y + hint.h) * cellH - inset,
        )
        val radius = 16 * density
        canvas.drawRoundRect(hintRect, radius, radius, hintFill)
        canvas.drawRoundRect(hintRect, radius, radius, hintStroke)
    }
}
