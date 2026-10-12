package com.luqman.decko

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream

/** Full-screen preview of one library item (image or video loop), with set, remove and delete. */
class WallpaperPreviewActivity : Activity() {
    private lateinit var item: Library.Item
    private lateinit var controls: View
    private lateinit var setButton: TextView
    private lateinit var removeButton: TextView
    private var player: LoopPlayer? = null
    private var animation: Drawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = Library.find(this, intent.getStringExtra(EXTRA_ID)) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_wallpaper_preview)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = findViewById<View>(R.id.root)
        controls = findViewById(R.id.controls)
        setButton = findViewById(R.id.set_static)
        removeButton = findViewById(R.id.remove_static)
        Immersive.keepClear(root, controls)

        if (item.video) showVideo() else if (item.animated) showAnimated() else showImage()

        root.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.preview_full).setOnClickListener { controls.visibility = View.GONE }
        setButton.setOnClickListener { if (item.video || item.animated) applyLive() else chooseWhere() }
        removeButton.setOnClickListener { remove() }
        findViewById<View>(R.id.delete_item).setOnClickListener { confirmDelete() }
    }

    private fun showImage() {
        val image = findViewById<ImageView>(R.id.image)
        val metrics = resources.displayMetrics
        Library.io.execute {
            val bmp = Library.decode(item.file, maxOf(metrics.widthPixels, metrics.heightPixels))
            runOnUiThread { image.setImageBitmap(bmp) }
        }
    }

    private fun showAnimated() {
        val image = findViewById<ImageView>(R.id.image)
        setButton.setText(R.string.set_live)
        val dm = resources.displayMetrics
        Library.io.execute {
            val d = if (Build.VERSION.SDK_INT >= 28) Library.decodeAnimated(item.file, dm.widthPixels, dm.heightPixels) else null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.file.path, bounds)
            val heavy = item.file.length() > 10L * 1024 * 1024 || maxOf(bounds.outWidth, bounds.outHeight) > 1920
            val text = getString(R.string.animated_info, bounds.outWidth, bounds.outHeight, Formatter.formatShortFileSize(this, item.file.length())) +
                if (heavy) "\n" + getString(R.string.animated_heavy_warning) else ""
            runOnUiThread {
                animation = d
                image.setImageDrawable(d)
                if (Build.VERSION.SDK_INT >= 28) (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                findViewById<TextView>(R.id.video_info).apply {
                    this.text = text
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showVideo() {
        findViewById<View>(R.id.image).visibility = View.GONE
        val surface = findViewById<SurfaceView>(R.id.video).apply { visibility = View.VISIBLE }
        setButton.setText(R.string.set_live)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player = LoopPlayer(item.file).also { it.start(holder, true) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.release()
                player = null
            }
        })

        val info = findViewById<TextView>(R.id.video_info)
        Library.io.execute {
            val retriever = MediaMetadataRetriever()
            val text = try {
                retriever.setDataSource(item.file.path)
                fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
                val rotated = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
                val w = meta(if (rotated) MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT else MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = meta(if (rotated) MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH else MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val seconds = meta(MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000
                val heavy = maxOf(w, h) > 1920 || seconds > 20
                getString(R.string.video_info, w, h, seconds, Formatter.formatShortFileSize(this, item.file.length())) +
                    if (heavy) "\n" + getString(R.string.video_heavy_warning) else ""
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
            runOnUiThread {
                if (text != null) {
                    info.text = text
                    info.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.hideSystemBars(window)
        if (::item.isInitialized) {
            player?.resume()
            if (Build.VERSION.SDK_INT >= 28) (animation as? AnimatedImageDrawable)?.start()
            refreshButtons()
        }
    }

    override fun onPause() {
        player?.pause()
        if (Build.VERSION.SDK_INT >= 28) (animation as? AnimatedImageDrawable)?.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.hideSystemBars(window)
    }

    /** A live wallpaper replaces any static image, so the saved id only counts while no live one is active. */
    private fun liveActive(flag: Int): Boolean {
        val wm = WallpaperManager.getInstance(this)
        return if (Build.VERSION.SDK_INT >= 34) wm.getWallpaperInfo(flag) != null else wm.wallpaperInfo != null
    }

    private fun appliedFlags(): Int {
        var flags = 0
        if (Prefs.staticApplied(this, "home") == item.id && !liveActive(WallpaperManager.FLAG_SYSTEM)) flags = flags or WallpaperManager.FLAG_SYSTEM
        if (Prefs.staticApplied(this, "lock") == item.id && !liveActive(WallpaperManager.FLAG_LOCK)) flags = flags or WallpaperManager.FLAG_LOCK
        return flags
    }

    private fun liveService(): Class<*> = if (item.video) VideoWallpaperService::class.java else AnimatedWallpaperService::class.java

    private fun liveApplied() =
        (if (item.video) Prefs.videoWallpaper(this) else Prefs.animatedWallpaper(this)) == item.id &&
            LiveWallpaper.isActive(this, liveService())

    private fun refreshButtons() {
        val applied = if (item.video || item.animated) liveApplied() else appliedFlags() != 0
        removeButton.visibility = if (applied) View.VISIBLE else View.GONE
    }

    private fun applyLive() {
        if (item.video) Prefs.setVideoWallpaper(this, item.id) else Prefs.setAnimatedWallpaper(this, item.id)
        // Already the active live wallpaper: the service picks up the new item on its own.
        if (LiveWallpaper.isActive(this, liveService())) {
            Toast.makeText(this, R.string.wallpaper_set, Toast.LENGTH_SHORT).show()
            refreshButtons()
        } else {
            LiveWallpaper.openPicker(this, liveService())
        }
    }

    private fun chooseWhere() {
        val options = arrayOf(getString(R.string.where_home), getString(R.string.where_lock), getString(R.string.where_both))
        AlertDialog.Builder(this)
            .setTitle(R.string.set_where_title)
            .setItems(options) { _, which ->
                apply(
                    when (which) {
                        0 -> WallpaperManager.FLAG_SYSTEM
                        1 -> WallpaperManager.FLAG_LOCK
                        else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    },
                )
            }
            .show()
    }

    private fun apply(flags: Int) {
        val appCtx = applicationContext
        Library.io.execute {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.file.path, bounds)
                val dm = resources.displayMetrics
                val screen = dm.widthPixels.toFloat() / dm.heightPixels
                // Crop the image to the screen's shape around its center, so the system doesn't zoom oddly.
                val cropW = minOf(bounds.outWidth, (bounds.outHeight * screen).toInt())
                val cropH = minOf(bounds.outHeight, (bounds.outWidth / screen).toInt())
                val left = (bounds.outWidth - cropW) / 2
                val top = (bounds.outHeight - cropH) / 2
                val hint = Rect(left, top, left + cropW, top + cropH)
                val wm = WallpaperManager.getInstance(appCtx)
                FileInputStream(item.file).use { wm.setStream(it, hint, true, flags) }
                if (flags and WallpaperManager.FLAG_SYSTEM != 0) {
                    Prefs.setStaticApplied(appCtx, "home", item.id)
                    // Lets Glass Home tell later whether this image is still the wallpaper, without reading it back.
                    Prefs.setHomeWallpaperId(appCtx, wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
                }
                if (flags and WallpaperManager.FLAG_LOCK != 0) Prefs.setStaticApplied(appCtx, "lock", item.id)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.wallpaper_set else R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
                refreshButtons()
            }
        }
    }

    private fun remove() {
        try {
            val wm = WallpaperManager.getInstance(this)
            if (item.video || item.animated) {
                wm.clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            } else {
                val flags = appliedFlags()
                wm.clear(flags)
                if (flags and WallpaperManager.FLAG_SYSTEM != 0) Prefs.setStaticApplied(this, "home", null)
                if (flags and WallpaperManager.FLAG_LOCK != 0) Prefs.setStaticApplied(this, "lock", null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.remove_wallpaper_failed, Toast.LENGTH_LONG).show()
        }
        refreshButtons()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                player?.release()
                player = null
                Library.delete(item)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
