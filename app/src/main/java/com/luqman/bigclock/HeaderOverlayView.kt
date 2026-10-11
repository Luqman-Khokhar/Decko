package com.luqman.bigclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.graphics.BitmapFactory
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the header animation: a lighting effect along the strip's bottom edge and a moving character.
 * Used both by the overlay window and by the preview in the Header tab, so the two always match.
 */
class HeaderOverlayView(ctx: Context) : View(ctx) {
    private var config: HeaderConfig = Prefs.header(ctx)
    private var character: Drawable? = null
    private var loadedItem: String? = null
    private var running = false

    /** Camera-hole position for the pulse effect; the strip's centre when the phone has no cutout. */
    var cutoutCenterX = -1f
    var cutoutCenterY = -1f

    private var x = 0f
    private var dir = 1
    private var lastFrameNs = 0L
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()
    private var sweepShader: LinearGradient? = null
    private var sweepColor = 0

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
            // 30 fps is plenty for a status-bar strip and halves the battery cost.
            if (dt >= 1f / 30f || lastFrameNs == 0L) {
                lastFrameNs = frameTimeNanos
                step(dt)
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun apply(newConfig: HeaderConfig) {
        val moveChanged = newConfig.movement != config.movement
        config = newConfig
        if (moveChanged) { x = 0f; dir = 1 }
        sweepShader = null
        loadCharacter(newConfig.itemId)
        invalidate()
    }

    fun setActive(active: Boolean) {
        if (active == running) return
        running = active
        if (Build.VERSION.SDK_INT >= 28) (character as? AnimatedImageDrawable)?.let { if (active) it.start() else it.stop() }
        if (active) {
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(ticker)
        } else {
            Choreographer.getInstance().removeFrameCallback(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        setActive(false)
        super.onDetachedFromWindow()
    }

    private fun loadCharacter(id: String?) {
        if (id == loadedItem) return
        loadedItem = id
        character?.callback = null
        if (Build.VERSION.SDK_INT >= 28) (character as? AnimatedImageDrawable)?.stop()
        character = null
        if (id == null) return
        Library.io.execute {
            val drawable = decodeCharacter(id)
            post {
                if (loadedItem != id) return@post
                character = drawable?.also {
                    it.callback = this
                    if (Build.VERSION.SDK_INT >= 28 && it is AnimatedImageDrawable) {
                        it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        if (running) it.start()
                    }
                }
                invalidate()
            }
        }
    }

    private fun decodeCharacter(id: String): Drawable? {
        val item = Library.find(context, id) ?: return null
        val target = (MAX_DECODE_DP * density).toInt()
        if (item.animated && Build.VERSION.SDK_INT >= 28) {
            try {
                return ImageDecoder.decodeDrawable(ImageDecoder.createSource(item.file)) { decoder, info, _ ->
                    val k = minOf(1f, target / maxOf(info.size.width, info.size.height).toFloat())
                    decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
            } catch (e: Exception) {
                // Falls through to the still thumbnail.
            }
        }
        val bmp = if (item.video || item.animated) BitmapFactory.decodeFile(item.thumb.path) else Library.decode(item.file, target)
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    override fun verifyDrawable(who: Drawable) = who === character || super.verifyDrawable(who)

    private fun charSizePx() = config.sizeDp * density

    private fun charWidthPx(d: Drawable): Float {
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        return if (w > 0 && h > 0) charSizePx() * w / h else charSizePx()
    }

    private fun step(dt: Float) {
        val d = character ?: return
        val cw = charWidthPx(d)
        val range = (width - cw).coerceAtLeast(0f)
        val pxPerSec = config.speed * 12f * density
        when (config.movement) {
            HeaderConfig.MOVE_STAY -> x = range * config.xPercent / 100f
            HeaderConfig.MOVE_WALK -> {
                x += pxPerSec * dt
                if (x > width) x = -cw
            }
            else -> {
                x += dir * pxPerSec * dt
                if (x >= range) { x = range; dir = -1 }
                if (x <= 0f) { x = 0f; dir = 1 }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawEffect(canvas)
        val d = character ?: return
        if (config.movement == HeaderConfig.MOVE_STAY && !running) x = (width - charWidthPx(d)).coerceAtLeast(0f) * config.xPercent / 100f
        val w = charWidthPx(d)
        val h = charSizePx()
        val top = (height - h) / 2f
        canvas.save()
        if (dir < 0 && config.movement == HeaderConfig.MOVE_BOUNCE) canvas.scale(-1f, 1f, x + w / 2f, top + h / 2f)
        d.setBounds(x.toInt(), top.toInt(), (x + w).toInt(), (top + h).toInt())
        d.draw(canvas)
        canvas.restore()
    }

    private fun drawEffect(canvas: Canvas) {
        if (config.effect == HeaderConfig.EFFECT_NONE) return
        val t = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f * (0.3f + 0.25f * config.effectSpeed)
        val w = width.toFloat()
        val h = height.toFloat()
        val c = config.color
        when (config.effect) {
            HeaderConfig.EFFECT_GLOW -> {
                val pulse = 0.55f + 0.45f * sin(2f * PI.toFloat() * t * 0.5f)
                val glow = 10f * density
                paint.shader = LinearGradient(0f, h - glow, 0f, h, withAlpha(c, 0f), withAlpha(c, 0.8f * pulse), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h - glow, w, h, paint)
                paint.shader = null
                paint.color = withAlpha(c, pulse)
                canvas.drawRect(0f, h - 1.5f * density, w, h, paint)
            }
            HeaderConfig.EFFECT_PULSE -> {
                val cx = if (cutoutCenterX >= 0f) cutoutCenterX else w / 2f
                val cy = if (cutoutCenterY >= 0f) cutoutCenterY else h / 2f
                val phase = t % 1f
                val r = (4f + phase * 22f) * density
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                paint.color = withAlpha(c, 1f - phase)
                canvas.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
            }
            HeaderConfig.EFFECT_SWEEP -> {
                val band = w * 0.35f
                if (sweepShader == null || sweepColor != c) {
                    sweepShader = LinearGradient(0f, 0f, band, 0f, intArrayOf(withAlpha(c, 0f), withAlpha(c, 0.9f), withAlpha(c, 0f)), null, Shader.TileMode.CLAMP)
                    sweepColor = c
                }
                val left = ((t * 0.4f) % 1.4f - 0.2f) * w - band / 2f
                matrix.setTranslate(left, 0f)
                sweepShader!!.setLocalMatrix(matrix)
                paint.shader = sweepShader
                rect.set(0f, h - 3f * density, w, h)
                canvas.drawRect(rect, paint)
                paint.alpha = 40
                rect.set(0f, 0f, w, h)
                canvas.drawRect(rect, paint)
                paint.shader = null
                paint.alpha = 255
            }
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    companion object {
        /** Characters are decoded once at this height and scaled by drawing, so the size slider needs no reload. */
        private const val MAX_DECODE_DP = 96
    }
}
