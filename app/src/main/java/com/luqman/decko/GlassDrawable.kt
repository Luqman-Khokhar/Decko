package com.luqman.decko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.random.Random

/**
 * A pane of frosted glass. Looks through [GlassBackdrop] at the blurred wallpaper behind its own screen
 * position; without a backdrop, a denser tint washed with the wallpaper's colors. Then a top sheen, fine
 * grain and a light-catching edge. Activated state turns it into a danger tint (the remove target).
 */
class GlassDrawable(
    context: Context,
    cornerDp: Float,
    private val topOnly: Boolean = false,
    /** False for panes that move with scrolling content, where an aligned backdrop would lag; they use the frosted look. */
    private val sampleBackdrop: Boolean = true,
    /** Sheets carry text over whatever is behind, so their tint is denser than an accent pane's. */
    private val sheet: Boolean = false,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius = cornerDp * density
    private val tint = context.getColor(R.color.glass_tint)
    private val tintSolid = context.getColor(if (sheet) R.color.glass_sheet else R.color.glass_tint_solid)
    private val danger = context.getColor(R.color.glass_danger)
    private val sheen = context.getColor(R.color.glass_sheen)
    private val edgeHi = context.getColor(R.color.glass_edge_hi)
    private val edgeLo = context.getColor(R.color.glass_edge_lo)

    private val path = Path()
    private val edgePath = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    private val pos = IntArray(2)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint().apply { shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private var activated = false
    private var washColors: IntArray? = null

    init {
        GlassBackdrop.register(this)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        val r = radius
        val radii = if (topOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        // The edge stroke sits half a pixel inside, so it isn't clipped away.
        val half = density / 2
        val er = (r - half).coerceAtLeast(0f)
        val edgeRadii = if (topOnly) floatArrayOf(er, er, er, er, 0f, 0f, 0f, 0f) else FloatArray(8) { er }
        edgePath.reset()
        edgePath.addRoundRect(RectF(rect).apply { inset(half, half) }, edgeRadii, Path.Direction.CW)
        sheenPaint.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f, sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        washColors = null
        edge.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, edgeHi, edgeLo, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.save()
        canvas.clipPath(path)
        val backdrop = GlassBackdrop.bitmap.takeIf { sampleBackdrop }
        val host = callback as? View
        if (backdrop != null && host != null) {
            // Line the backdrop up with the real wallpaper behind this view's spot on screen.
            host.getLocationOnScreen(pos)
            val s = GlassBackdrop.screenScale
            matrix.setScale(s, s)
            matrix.postTranslate(-pos[0].toFloat(), -pos[1].toFloat())
            canvas.drawBitmap(backdrop, matrix, backdropPaint)
            fill.color = if (activated) danger else if (sheet) tintSolid else tint
        } else {
            val colors = GlassBackdrop.colors
            if (colors != null) {
                if (colors !== washColors) {
                    washColors = colors
                    wash.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, withAlpha(colors[0], WASH_ALPHA), withAlpha(colors[1], WASH_ALPHA), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(rect, wash)
            }
            fill.color = if (activated) danger else tintSolid
        }
        canvas.drawRect(rect, fill)
        canvas.drawRect(rect, sheenPaint)
        canvas.drawRect(rect, grain)
        canvas.restore()
        canvas.drawPath(edgePath, edge)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_activated in state
        if (now == activated) return false
        activated = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Required override; glass is always see-through.")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val WASH_ALPHA = 0x38

        /** Faint monochrome grain, the texture that sells frosted glass. */
        val noise: Bitmap by lazy {
            val side = 64
            val rnd = Random(7)
            val px = IntArray(side * side) { Color.argb(rnd.nextInt(6, 16), 255, 255, 255) }
            Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
        }
    }
}
