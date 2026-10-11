package com.luqman.bigclock

import android.media.MediaPlayer
import android.util.Log
import android.view.SurfaceHolder
import java.io.File

/**
 * Muted, looping, hardware-decoded video on a surface, center-cropped to fill it. Plain MediaPlayer:
 * no extra library, no audio focus, no wake lock. Shared by the preview screen and the wallpaper.
 */
class LoopPlayer(private val file: File) {
    private var player: MediaPlayer? = null
    private var wantPlay = true
    private var prepared = false

    /** With [play] false the first frame is shown and playback stays paused (battery saver). */
    fun start(holder: SurfaceHolder, play: Boolean) {
        release()
        wantPlay = play
        prepared = false
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.path)
            mp.setSurface(holder.surface)
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnErrorListener { _, what, extra -> Log.w("BigClockVideo", "player error what=$what extra=$extra"); release(); true }
            mp.setOnInfoListener { p, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !wantPlay) p.pause()
                false
            }
            mp.setOnPreparedListener {
                prepared = true
                it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                it.start()
                if (!wantPlay) it.pause()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("BigClockVideo", "start failed", e)
            release()
        }
    }

    /** Commands before the player is prepared only record the wish; some devices report an error instead of ignoring them. */
    fun resume() {
        wantPlay = true
        if (!prepared) return
        try { player?.start() } catch (e: IllegalStateException) { }
    }

    fun pause() {
        wantPlay = false
        if (!prepared) return
        try { player?.pause() } catch (e: IllegalStateException) { }
    }

    fun release() {
        player?.let {
            it.setOnErrorListener(null)
            it.setOnInfoListener(null)
            it.setOnPreparedListener(null)
            it.release()
        }
        player = null
        prepared = false
    }
}
