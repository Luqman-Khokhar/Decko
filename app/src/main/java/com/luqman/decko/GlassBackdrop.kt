package com.luqman.decko

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * A blurred copy of the home wallpaper for Glass Home to look through, plus the wallpaper's colors.
 * Apps can't read the system wallpaper on Android 13+, so the image comes from what Decko knows it set:
 * a library picture (confirmed by wallpaper id) or one of Decko's live wallpapers. Anything else
 * leaves [bitmap] null, and glass falls back to a frosted tint in the wallpaper's [colors].
 */
object GlassBackdrop {
    /** Backdrop width as a fraction of the screen; the downscale does half the blurring. */
    private const val SCALE = 1 / 6f
    private const val BLUR_RADIUS = 9
    private const val BLUR_PASSES = 3

    /** Blurred wallpaper at [SCALE]; draw it scaled by [screenScale] to cover the screen. */
    @Volatile var bitmap: Bitmap? = null
        private set
    var screenScale = 1f
        private set

    /** Wallpaper's main and secondary colors, for the frosted fallback; null when unknown. */
    var colors: IntArray? = null
        private set

    /** True when the wallpaper is light enough for dark text and status-bar icons. */
    var prefersDarkText = false
        private set

    private var signature: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val surfaces = mutableListOf<WeakReference<Drawable>>()

    /** Called on the main thread after a rebuild, for things drawables can't update themselves (labels, bar icons). */
    var onChanged: (() -> Unit)? = null

    /** Glass drawables redraw when the backdrop changes. */
    fun register(d: Drawable) {
        surfaces.removeAll { it.get() == null }
        surfaces += WeakReference(d)
    }

    /** Cheap when nothing changed; otherwise rebuilds in the background and redraws registered glass. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val wm = WallpaperManager.getInstance(app)
        val live = wm.wallpaperInfo?.component
        val sig = listOf(live?.flattenToShortString(), wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM), Prefs.homeWallpaperId(app),
            Prefs.staticApplied(app, "home"), Prefs.animatedWallpaper(app), Prefs.videoWallpaper(app), Prefs.appTheme(app).id).joinToString("|")
        if (sig == signature) return
        signature = sig
        worker.execute {
            val size = screenSize(app)
            val source = try { source(app, wm, live, size) } catch (e: Exception) { null }
            val blurred = source?.let { blur(it) }
            val c = if (Build.VERSION.SDK_INT >= 27) try { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) } catch (e: Exception) { null } else null
            main.post {
                bitmap = blurred
                screenScale = if (blurred != null) size.width() / blurred.width.toFloat() else 1f
                colors = (if (Build.VERSION.SDK_INT >= 27 && c != null) intArrayOf(c.primaryColor.toArgb(), (c.secondaryColor ?: c.primaryColor).toArgb()) else null)
                    ?: blurred?.let { intArrayOf(it.getPixel(it.width / 2, it.height / 3), it.getPixel(it.width / 2, it.height * 2 / 3)) }
                prefersDarkText = darkText(c, blurred)
                surfaces.forEach { it.get()?.invalidateSelf() }
                onChanged?.invoke()
            }
        }
    }

    /** Whether a Decko-known image is behind home, without decoding anything. */
    fun hasSource(ctx: Context): Boolean {
        val wm = WallpaperManager.getInstance(ctx)
        val live = wm.wallpaperInfo?.component ?: return staticLibraryItem(ctx, wm) != null
        return live.packageName == ctx.packageName
    }

    private fun staticLibraryItem(ctx: Context, wm: WallpaperManager): Library.Item? {
        if (wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != Prefs.homeWallpaperId(ctx)) return null
        return Library.find(ctx, Prefs.staticApplied(ctx, "home"))
    }

    /** The wallpaper as the home screen shows it (center-cropped to the screen), at backdrop size. */
    private fun source(ctx: Context, wm: WallpaperManager, live: ComponentName?, screen: Rect): Bitmap? {
        val w = (screen.width() * SCALE).toInt().coerceAtLeast(1)
        val h = (screen.height() * SCALE).toInt().coerceAtLeast(1)
        if (live == null) {
            staticLibraryItem(ctx, wm)?.let { return cover(decode(it.file.path, w, h), w, h) }
            // Readable only up to Android 12 with storage access; newer phones throw and fall back to frost.
            return try {
                @Suppress("MissingPermission")
                val d = wm.drawable ?: return null
                val bmp = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(Canvas(bmp))
                cover(bmp, w, h)
            } catch (e: SecurityException) {
                null
            }
        }
        if (live.packageName != ctx.packageName) return null
        return when (live.className) {
            AnimatedWallpaperService::class.java.name -> Library.find(ctx, Prefs.animatedWallpaper(ctx))?.thumb?.path?.let { cover(decode(it, w, h), w, h) }
            VideoWallpaperService::class.java.name -> Library.find(ctx, Prefs.videoWallpaper(ctx))?.thumb?.path?.let { cover(decode(it, w, h), w, h) }
            // The clock wallpaper is mostly its design's background color.
            else -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Prefs.appTheme(ctx).background) }
        }
    }

    private fun decode(path: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Center-crop to fill [w]×[h], like the system draws a static wallpaper that's never scrolled. */
    private fun cover(src: Bitmap?, w: Int, h: Int): Bitmap? {
        src ?: return null
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Separable box blur, three passes ≈ Gaussian. Small bitmap, so plain Kotlin is fast enough. */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(BLUR_PASSES) {
            boxPass(a, b, w, h, horizontal = true)
            boxPass(b, a, w, h, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val r = BLUR_RADIUS
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (i in 0 until len) {
                dst[if (horizontal) line * w + i else i * w + line] = Color.rgb(sr / div, sg / div, sb / div)
                val add = at(i + r + 1)
                val sub = at(i - r)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun darkText(c: WallpaperColors?, blurred: Bitmap?): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && c != null) return c.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        if (Build.VERSION.SDK_INT >= 27 && c != null) return Color.luminance(c.primaryColor.toArgb()) > 0.6f
        val px = blurred?.getPixel(blurred.width / 2, blurred.height / 2) ?: return false
        return Color.luminance(px) > 0.6f
    }

    private fun screenSize(ctx: Context): Rect {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return Rect(0, 0, p.x, p.y)
    }
}
