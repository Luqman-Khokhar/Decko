package com.luqman.bigclock

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Live wallpaper that plays an animated GIF or WebP from the user's library. Same battery rules as
 * the video wallpaper: animates only while visible, shows the first frame in battery saver, and
 * frees the decoded animation after 30 s hidden.
 */
@TargetApi(28)
class AnimatedWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AnimatedEngine()

    private inner class AnimatedEngine : Engine(), Drawable.Callback {
        private val handler = Handler(Looper.getMainLooper())
        private var drawable: Drawable? = null
        private var shownId: String? = null
        private var loadingId: String? = null
        private var visible = false
        private var surfaceReady = false
        private var listening = false

        private val powerSave get() = getSystemService(PowerManager::class.java).isPowerSaveMode
        private val releaseLater = Runnable { releaseDrawable() }
        private val powerChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            update()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releaseDrawable()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible && !listening) {
                registerReceiver(powerChanged, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
                listening = true
            } else if (!isVisible) {
                stopListening()
            }
            update()
        }

        override fun onDestroy() {
            handler.removeCallbacks(releaseLater)
            stopListening()
            releaseDrawable()
            super.onDestroy()
        }

        private fun stopListening() {
            if (listening) unregisterReceiver(powerChanged)
            listening = false
        }

        private fun update() {
            if (!surfaceReady) return
            handler.removeCallbacks(releaseLater)
            val current = drawable
            if (!visible) {
                (current as? AnimatedImageDrawable)?.stop()
                handler.postDelayed(releaseLater, 30_000)
                return
            }
            val id = Prefs.animatedWallpaper(this@AnimatedWallpaperService)
            if (current == null || shownId != id) {
                load(id)
            } else if (powerSave) {
                (current as? AnimatedImageDrawable)?.stop()
                draw()
            } else {
                (current as? AnimatedImageDrawable)?.start()
            }
        }

        private fun load(id: String?) {
            if (id == null || loadingId == id) return
            releaseDrawable()
            loadingId = id
            val dm = resources.displayMetrics
            Library.io.execute {
                val item = Library.find(this@AnimatedWallpaperService, id)
                val loaded = item?.let { Library.decodeAnimated(it.file, dm.widthPixels, dm.heightPixels) }
                handler.post {
                    if (loadingId == id && surfaceReady) {
                        loadingId = null
                        attach(loaded, id)
                    } else {
                        if (loadingId == id) loadingId = null
                        (loaded as? AnimatedImageDrawable)?.stop()
                    }
                }
            }
        }

        private fun attach(d: Drawable?, id: String) {
            if (d == null) return
            drawable = d
            shownId = id
            d.callback = this
            (d as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            draw()
            if (visible && !powerSave) (d as? AnimatedImageDrawable)?.start()
        }

        private fun releaseDrawable() {
            drawable?.let {
                (it as? AnimatedImageDrawable)?.stop()
                it.callback = null
            }
            drawable = null
            shownId = null
            loadingId = null
        }

        private fun draw() {
            val d = drawable ?: return
            val holder = surfaceHolder
            val c = try { holder.lockHardwareCanvas() } catch (e: Exception) {
                try { holder.lockCanvas() } catch (e2: Exception) { null }
            } ?: return
            try {
                c.drawColor(Color.BLACK)
                val w = c.width.toFloat()
                val h = c.height.toFloat()
                val iw = d.intrinsicWidth.coerceAtLeast(1)
                val ih = d.intrinsicHeight.coerceAtLeast(1)
                // Center-crop to fill the screen.
                val scale = maxOf(w / iw, h / ih)
                val dw = (iw * scale).toInt()
                val dh = (ih * scale).toInt()
                val left = ((w - dw) / 2).toInt()
                val top = ((h - dh) / 2).toInt()
                d.setBounds(left, top, left + dw, top + dh)
                d.draw(c)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }

        override fun invalidateDrawable(who: Drawable) = draw()

        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMs: Long) {
            handler.postAtTime(what, who, whenMs)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what, who)
        }
    }
}
