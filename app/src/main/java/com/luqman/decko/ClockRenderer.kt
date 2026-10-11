package com.luqman.decko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a clock face and hands. All sizes are fractions of the face radius.
 * Hand fractions are mirrored in tools/gen_widget_res.py so the widget matches the app.
 */
object ClockRenderer {
    private const val FACE = 0.96f
    private const val RIM_W = 0.022f
    private const val HOUR_LEN = 0.52f
    private const val HOUR_W = 0.085f
    private const val MIN_LEN = 0.82f
    private const val MIN_W = 0.055f
    private const val SEC_LEN = 0.88f
    private const val SEC_W = 0.02f
    private const val HAND_TAIL = 0.10f
    private const val SEC_TAIL = 0.18f
    private const val CAP = 0.06f

    private val ROMAN = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val sans = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    fun drawDial(c: Canvas, cx: Float, cy: Float, half: Float, t: ClockTheme) {
        if (t.digital != null) return DigitalRenderer.drawFace(c, cx, cy, half, t)
        val r = half * FACE
        fill.color = t.face
        c.drawCircle(cx, cy, r, fill)
        stroke.color = t.rim
        stroke.strokeWidth = r * RIM_W
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2, stroke)

        stroke.color = t.marks
        for (i in 0 until 60) {
            val hour = i % 5 == 0
            if (!hour && !t.minuteTicks) continue
            val quarter = i % 15 == 0
            val len = if (hour) 0.10f else 0.05f
            stroke.strokeWidth = r * when {
                t.numerals == Numerals.QUARTERS && hour && !quarter -> 0.06f
                hour -> 0.026f
                else -> 0.01f
            }
            val a = Math.toRadians(i * 6.0)
            val outer = r * 0.90f
            val inner = r * (0.90f - len)
            c.drawLine(
                cx + inner * sin(a).toFloat(), cy - inner * cos(a).toFloat(),
                cx + outer * sin(a).toFloat(), cy - outer * cos(a).toFloat(), stroke,
            )
        }

        text.color = t.marks
        for (h in 0 until 12) {
            val label: String
            val size: Float
            val radius: Float
            when (t.numerals) {
                Numerals.ARABIC -> { label = (if (h == 0) 12 else h).toString(); size = 0.22f; radius = 0.66f }
                Numerals.ROMAN -> { label = ROMAN[h]; size = 0.17f; radius = 0.66f }
                Numerals.QUARTERS -> {
                    if (h % 3 != 0) continue
                    label = (if (h == 0) 12 else h).toString(); size = 0.36f; radius = 0.58f
                }
            }
            text.typeface = if (t.numerals == Numerals.ROMAN) serif else sans
            text.textSize = r * size
            val a = Math.toRadians(h * 30.0)
            val x = cx + r * radius * sin(a).toFloat()
            val y = cy - r * radius * cos(a).toFloat() - (text.descent() + text.ascent()) / 2
            c.drawText(label, x, y, text)
        }
    }

    fun drawHands(c: Canvas, cx: Float, cy: Float, half: Float, t: ClockTheme, h: Int, m: Int, s: Float, showSeconds: Boolean = true, is24: Boolean = true) {
        if (t.digital != null) return DigitalRenderer.drawTime(c, cx, cy, half, t, h, m, s.toInt(), showSeconds, is24)
        val r = half * FACE
        hand(c, cx, cy, ((h % 12) + m / 60f) * 30f, r * HOUR_LEN, r * HAND_TAIL, r * HOUR_W, t.hourHand)
        hand(c, cx, cy, (m + s / 60f) * 6f, r * MIN_LEN, r * HAND_TAIL, r * MIN_W, t.minuteHand)
        if (t.seconds && showSeconds) hand(c, cx, cy, s.toInt() * 6f, r * SEC_LEN, r * SEC_TAIL, r * SEC_W, t.secondHand)
        fill.color = if (showSeconds) t.cap else t.minuteHand
        c.drawCircle(cx, cy, r * CAP, fill)
    }

    /** Static face for the home-screen widget; the hands there are a live system AnalogClock. */
    fun dialBitmap(t: ClockTheme, sizePx: Int): Bitmap {
        val height = (sizePx * t.aspect).toInt()
        val bmp = Bitmap.createBitmap(sizePx, height, Bitmap.Config.ARGB_8888)
        val half = sizePx / 2f
        drawDial(Canvas(bmp), half, height / 2f, half, t)
        return bmp
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, tail: Float, w: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = w
        c.save()
        c.rotate(deg, cx, cy)
        c.drawLine(cx, cy + tail, cx, cy - len, stroke)
        c.restore()
    }
}
