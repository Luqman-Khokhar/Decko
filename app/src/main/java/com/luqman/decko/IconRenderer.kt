package com.luqman.decko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer

/** Draws one app icon through an [IconPack] into a square bitmap. Pure drawing: safe off the main thread. */
object IconRenderer {
    /** Legacy (non-adaptive) icons sit inside the shape at this scale, like Launcher3's icon wrapping. */
    private const val LEGACY_SCALE = 0.7f

    /** Adaptive layers are 108dp for a 72dp visible area, so they extend 1/4 of the icon past every edge. */
    private const val ADAPTIVE_BLEED = 0.25f

    /** Foregrounds covering less than this share of the visible icon are glyphs; more is a picture. */
    private const val GLYPH_COVERAGE = 0.55f

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    /**
     * [fullBleed] is for the user's own pictures: they fill the whole shape instead of being wrapped.
     * SYSTEM has no shape of its own, so a full-bleed picture falls back to ROUNDED.
     */
    fun render(icon: Drawable, size: Int, pack: IconPack, theme: ClockTheme, fullBleed: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val shape = (if (fullBleed && pack == IconPack.SYSTEM) IconPack.ROUNDED else pack).shape(size.toFloat())
        if (shape == null) {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            return bmp
        }
        when {
            fullBleed -> drawScaled(canvas, icon, size, 1f)
            pack == IconPack.THEMED -> drawThemed(canvas, icon, size, theme)
            icon is AdaptiveIconDrawable -> {
                icon.background?.let { drawLayer(canvas, it, size) }
                icon.foreground?.let { drawLayer(canvas, it, size) }
            }
            else -> {
                canvas.drawColor(Color.WHITE)
                drawScaled(canvas, icon, size, LEGACY_SCALE)
            }
        }
        // Mask after drawing: Canvas.clipPath is aliased on bitmaps. Clearing the inverse of the shape with an
        // AA paint gives smooth edges; a xfermode only reaches pixels the path covers, hence the inverse fill.
        shape.fillType = Path.FillType.INVERSE_WINDING
        canvas.drawPath(shape, clearPaint)
        return bmp
    }

    /**
     * Single-color look in the clock design's colors. Android 13+ apps ship a monochrome layer; for the rest a
     * glyph-like foreground becomes a silhouette, and a picture-like icon becomes a hand-on-face duotone.
     */
    private fun drawThemed(canvas: Canvas, icon: Drawable, size: Int, theme: ClockTheme) {
        canvas.drawColor(theme.face)
        val glyph = (if (Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable) icon.monochrome else null)
            ?: (icon as? AdaptiveIconDrawable)?.foreground?.takeIf { coverage(it, size) < GLYPH_COVERAGE }
        if (glyph != null) {
            glyph.mutate().colorFilter = PorterDuffColorFilter(theme.hourHand, PorterDuff.Mode.SRC_IN)
            drawLayer(canvas, glyph, size)
            return
        }
        val duotone = duotone(theme.hourHand, theme.face)
        if (icon is AdaptiveIconDrawable) {
            icon.background?.let { it.mutate().colorFilter = duotone; drawLayer(canvas, it, size) }
            icon.foreground?.let { it.mutate().colorFilter = duotone; drawLayer(canvas, it, size) }
        } else {
            icon.mutate().colorFilter = duotone
            drawScaled(canvas, icon, size, LEGACY_SCALE)
        }
    }

    /** Share of the visible icon area the layer paints with real opacity. */
    private fun coverage(layer: Drawable, size: Int): Float {
        val probe = size.coerceAtMost(48)
        val bmp = Bitmap.createBitmap(probe, probe, Bitmap.Config.ALPHA_8)
        drawLayer(Canvas(bmp), layer, probe)
        // Rows may be padded past the width, so size the buffer by the bitmap and skip the padding.
        val alpha = ByteArray(bmp.byteCount)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(alpha))
        val stride = bmp.rowBytes
        bmp.recycle()
        var opaque = 0
        for (y in 0 until probe) for (x in 0 until probe) if ((alpha[y * stride + x].toInt() and 0xFF) > 128) opaque++
        return opaque / (probe * probe).toFloat()
    }

    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int) {
        val bleed = (size * ADAPTIVE_BLEED).toInt()
        layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
        layer.draw(canvas)
    }

    private fun drawScaled(canvas: Canvas, icon: Drawable, size: Int, scale: Float) {
        val inset = ((size - size * scale) / 2).toInt()
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(canvas)
    }

    /** Brightness mapped onto two colors: black becomes [dark], white becomes [light]. */
    private fun duotone(dark: Int, light: Int): ColorMatrixColorFilter {
        fun row(d: Int, l: Int): FloatArray {
            val span = (l - d) / 255f
            return floatArrayOf(0.2126f * span, 0.7152f * span, 0.0722f * span, 0f, d.toFloat())
        }
        return ColorMatrixColorFilter(ColorMatrix(
            row(Color.red(dark), Color.red(light)) +
                row(Color.green(dark), Color.green(light)) +
                row(Color.blue(dark), Color.blue(light)) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ))
    }
}
