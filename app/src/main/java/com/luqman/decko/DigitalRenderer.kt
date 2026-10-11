package com.luqman.decko

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

enum class DigitalStyle(val seconds: Boolean) { CLASSIC(false), MODERN(false), MINIMAL(false), HACKER(true), PUNK(false), NEON(false) }

/**
 * Draws the digital designs. Same split as the analog renderer: [drawFace] is the static card and
 * decoration (also used as the widget background), [drawTime] is the digits. Sizes are fractions of
 * the card height. Fonts and colors are mirrored in tools/gen_widget_res.py for the widget's TextClock.
 */
object DigitalRenderer {
    /** Card height / card width. */
    const val ASPECT = 0.55f
    private const val FACE = 0.96f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val path = Path()

    private val faces = mapOf(
        DigitalStyle.CLASSIC to Typeface.create(Typeface.SERIF, Typeface.BOLD),
        DigitalStyle.MODERN to Typeface.create("sans-serif-medium", Typeface.NORMAL),
        DigitalStyle.MINIMAL to Typeface.create("sans-serif-light", Typeface.NORMAL),
        DigitalStyle.HACKER to Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        DigitalStyle.PUNK to Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC),
        DigitalStyle.NEON to Typeface.create("sans-serif-medium", Typeface.NORMAL),
    )

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private fun border(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, inset: Float, width: Float, color: Int, radius: Float) {
        rect.set(cx - w / 2 + inset, cy - h / 2 + inset, cx + w / 2 - inset, cy + h / 2 - inset)
        stroke.color = color
        stroke.strokeWidth = width
        c.drawRoundRect(rect, radius, radius, stroke)
    }

    fun drawFace(c: Canvas, cx: Float, cy: Float, half: Float, t: ClockTheme) {
        val style = t.digital ?: return
        val w = 2 * half * FACE
        val h = w * ASPECT
        val rad = h * 0.12f
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = t.face
        c.drawRoundRect(rect, rad, rad, fill)

        when (style) {
            DigitalStyle.CLASSIC -> {
                border(c, w, h, cx, cy, h * 0.0125f, h * 0.025f, t.rim, rad)
                border(c, w, h, cx, cy, h * 0.07f, h * 0.01f, t.marks, rad * 0.7f)
            }
            DigitalStyle.MODERN -> {
                rect.set(cx - w * 0.05f, cy + h * 0.33f, cx + w * 0.05f, cy + h * 0.33f + h * 0.03f)
                fill.color = t.secondHand
                c.drawRoundRect(rect, h * 0.015f, h * 0.015f, fill)
            }
            DigitalStyle.MINIMAL -> Unit
            DigitalStyle.HACKER -> {
                c.save()
                rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                path.reset()
                path.addRoundRect(rect, rad, rad, Path.Direction.CW)
                c.clipPath(path)
                stroke.color = alpha(t.marks, 40)
                stroke.strokeWidth = h * 0.006f
                var y = cy - h / 2
                while (y < cy + h / 2) {
                    c.drawLine(cx - w / 2, y, cx + w / 2, y, stroke)
                    y += h * 0.05f
                }
                c.restore()
                border(c, w, h, cx, cy, h * 0.006f, h * 0.012f, t.rim, rad)
                val arm = h * 0.16f
                val inset = h * 0.07f
                stroke.color = t.marks
                stroke.strokeWidth = h * 0.02f
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val x = cx + sx * (w / 2 - inset)
                    val y = cy + sy * (h / 2 - inset)
                    c.drawLine(x, y, x - sx * arm, y, stroke)
                    c.drawLine(x, y, x, y - sy * arm, stroke)
                }
            }
            DigitalStyle.PUNK -> {
                c.save()
                rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                path.reset()
                path.addRoundRect(rect, rad, rad, Path.Direction.CW)
                c.clipPath(path)
                fill.color = t.marks
                val strip = h * 0.09f
                for (top in booleanArrayOf(true, false)) {
                    val y = if (top) cy - h / 2 else cy + h / 2 - strip
                    var x = cx - w / 2 - strip
                    while (x < cx + w / 2) {
                        path.reset()
                        path.moveTo(x, y + strip)
                        path.lineTo(x + strip, y + strip)
                        path.lineTo(x + 2 * strip, y)
                        path.lineTo(x + strip, y)
                        path.close()
                        c.drawPath(path, fill)
                        x += 2 * strip
                    }
                }
                c.restore()
                border(c, w, h, cx, cy, h * 0.015f, h * 0.03f, t.rim, rad)
            }
            DigitalStyle.NEON -> {
                val base = h * 0.02f
                val alphas = intArrayOf(255, 80, 50, 30)
                for (k in 3 downTo 0) border(c, w, h, cx, cy, h * 0.05f, base * (1 + k * 1.2f), alpha(t.rim, alphas[k]), rad)
            }
        }
    }

    fun drawTime(c: Canvas, cx: Float, cy: Float, half: Float, t: ClockTheme, h: Int, m: Int, s: Int, showSeconds: Boolean, is24: Boolean) {
        val style = t.digital ?: return
        val w = 2 * half * FACE
        val ch = w * ASPECT
        val hour = if (is24) h else (h % 12).let { if (it == 0) 12 else it }
        val str = buildString {
            append(if (is24) String.format(Locale.ROOT, "%02d", hour) else hour.toString())
            append(String.format(Locale.ROOT, ":%02d", m))
            if (style.seconds && showSeconds) append(String.format(Locale.ROOT, ":%02d", s))
        }

        text.typeface = faces.getValue(style)
        text.letterSpacing = when (style) {
            DigitalStyle.PUNK -> 0.04f
            DigitalStyle.NEON -> 0.06f
            DigitalStyle.HACKER -> 0.02f
            else -> 0f
        }
        text.textSize = ch * 0.52f
        val maxW = w * 0.82f
        val full = text.measureText(str)
        if (full > maxW) text.textSize *= maxW / full
        text.clearShadowLayer()
        when (style) {
            DigitalStyle.HACKER -> text.setShadowLayer(ch * 0.05f, 0f, 0f, t.hourHand)
            DigitalStyle.NEON -> text.setShadowLayer(ch * 0.07f, 0f, 0f, t.rim)
            else -> Unit
        }

        val spacing = text.letterSpacing * text.textSize
        var total = 0f
        for (cc in str) total += text.measureText(cc.toString()) + spacing
        total -= spacing
        val baseline = cy - (text.descent() + text.ascent()) / 2

        c.save()
        if (style == DigitalStyle.PUNK) c.rotate(-4f, cx, cy)
        var x = cx - total / 2
        for (cc in str) {
            text.color = if (cc == ':') t.minuteHand else t.hourHand
            c.drawText(cc.toString(), x, baseline, text)
            x += text.measureText(cc.toString()) + spacing
        }
        c.restore()
        text.clearShadowLayer()
    }
}
