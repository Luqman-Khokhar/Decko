package com.luqman.bigclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Live wallpaper that loops a video from the user's library. Battery rules: plays only while the
 * wallpaper is visible, shows a still first frame in battery saver, and frees the decoder after
 * 30 s hidden (the last frame stays on screen until it resumes).
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var player: LoopPlayer? = null
        private var playingId: String? = null
        private var visible = false
        private var surfaceReady = false
        private var listening = false

        private val powerSave get() = getSystemService(PowerManager::class.java).isPowerSaveMode
        private val releaseLater = Runnable { releasePlayer() }
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

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releasePlayer()
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
            releasePlayer()
            super.onDestroy()
        }

        private fun stopListening() {
            if (listening) unregisterReceiver(powerChanged)
            listening = false
        }

        private fun update() {
            if (!surfaceReady) return
            handler.removeCallbacks(releaseLater)
            if (!visible) {
                player?.pause()
                handler.postDelayed(releaseLater, 30_000)
                return
            }
            val id = Prefs.videoWallpaper(this@VideoWallpaperService)
            val current = player
            if (current == null || playingId != id) {
                val item = Library.find(this@VideoWallpaperService, id) ?: return
                releasePlayer()
                player = LoopPlayer(item.file).also { it.start(surfaceHolder, !powerSave) }
                playingId = id
            } else if (powerSave) {
                current.pause()
            } else {
                current.resume()
            }
        }

        private fun releasePlayer() {
            player?.release()
            player = null
            playingId = null
        }
    }
}
