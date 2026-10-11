package com.luqman.decko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import java.util.Calendar

/**
 * Live wallpaper so the clock shows on the lock screen (and home screen). Phones no longer
 * allow lock-screen widgets, so this is the supported route. Redraws once a minute while
 * visible, without a second hand, so it costs almost no battery.
 */
class ClockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var visible = false
        private var listening = false

        private val minuteTick = object : Runnable {
            override fun run() {
                draw()
                handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
            }
        }

        private val timeChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = draw()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(minuteTick)
            if (isVisible) {
                minuteTick.run()
                if (!listening) {
                    registerReceiver(timeChanged, IntentFilter().apply {
                        addAction(Intent.ACTION_TIME_CHANGED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    })
                    listening = true
                }
            } else {
                stopListening()
            }
        }

        override fun onSurfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (visible) draw()
        }

        override fun onDestroy() {
            handler.removeCallbacks(minuteTick)
            stopListening()
            super.onDestroy()
        }

        private fun stopListening() {
            if (listening) unregisterReceiver(timeChanged)
            listening = false
        }

        private fun draw() {
            val holder = surfaceHolder
            val c = try { holder.lockCanvas() } catch (e: IllegalStateException) { null } ?: return
            try {
                render(c)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }

        private fun render(c: Canvas) {
            val t = Prefs.appTheme(this@ClockWallpaperService)
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            c.drawColor(t.background)

            // Below the system lock-screen clock, which sits in the top quarter.
            val half = minOf(w * 0.42f, h * 0.26f)
            val cx = w / 2
            val cy = h * 0.47f
            val now = Calendar.getInstance()
            ClockRenderer.drawDial(c, cx, cy, half, t)
            ClockRenderer.drawHands(
                c, cx, cy, half, t, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), 0f, showSeconds = false,
                is24 = android.text.format.DateFormat.is24HourFormat(this@ClockWallpaperService),
            )

            text.color = t.text
            var y = cy + half * t.aspect
            val style = t.dateStyle
            if (Prefs.digitalTime(this@ClockWallpaperService) && t.digital == null) {
                y = line(c, ClockText.time(this@ClockWallpaperService, now), cx, y, half * 0.34f, w, style.weekdayTypeface, 0f)
            }
            y = line(c, style.weekday(now), cx, y, half * 0.30f, w, style.weekdayTypeface, style.spacing)
            line(c, style.date(now), cx, y, half * 0.18f, w, style.dateTypeface, style.spacing * 0.5f)
        }

        /** Draws one centered line below [top], shrunk to fit the width; returns its bottom. */
        private fun line(c: Canvas, s: CharSequence, cx: Float, top: Float, size: Float, w: Float, face: Typeface, spacing: Float): Float {
            text.typeface = face
            text.letterSpacing = spacing
            text.textSize = size
            val width = text.measureText(s, 0, s.length)
            if (width > w * 0.9f) text.textSize = size * w * 0.9f / width
            val baseline = top + text.textSize * 1.25f
            c.drawText(s, 0, s.length, cx, baseline, text)
            return baseline
        }
    }
}
