package com.luqman.bigclock

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

/** Draws a library item into a widget-sized bitmap. Corners are baked in because RemoteViews cannot clip. */
object PhotoRenderer {
    /** Longest side of any bitmap handed to RemoteViews. */
    private const val MAX_SIDE = 1200

    /** All frames of a looping widget are held in memory at once; resolution is lowered only when they would not fit. */
    private const val FRAME_BUDGET_BYTES = 64L * 1024 * 1024
    private const val MAX_FRAMES = 60

    /** Flip intervals the widget layouts are built for (ms). The nearest to the animation's own frame delay is used. */
    val INTERVALS = intArrayOf(40, 50, 60, 70, 80, 100, 130, 200)
    const val DEFAULT_INTERVAL = 100

    class Style(val fit: Boolean, val radiusDp: Int)

    class Loop(val frames: List<Bitmap>, val intervalMs: Int)

    fun render(ctx: Context, item: Library.Item, widthPx: Int, heightPx: Int, style: Style): Bitmap? {
        val src = source(item, minOf(maxOf(widthPx, heightPx), MAX_SIDE)) ?: return null
        val (w, h) = outputSize(src.width, src.height, widthPx, heightPx, style.fit, 1)
        return compose(ctx, src, w, h, widthPx, style)
    }

    /** True when [item] can play as a widget loop: GIF and animated WebP. */
    fun canLoop(item: Library.Item) = item.animated

    /** Frames spaced at the animation's real frame rate, so speed matches the original. Falls back to one still. */
    fun loop(ctx: Context, item: Library.Item, widthPx: Int, heightPx: Int, style: Style): Loop? {
        val still = { render(ctx, item, widthPx, heightPx, style)?.let { Loop(listOf(it), DEFAULT_INTERVAL) } }
        return (if (item.file.extension == "webp") webpLoop(ctx, item, widthPx, heightPx, style) else gifLoop(ctx, item, widthPx, heightPx, style))
            ?: still()
    }

    private fun webpLoop(ctx: Context, item: Library.Item, widthPx: Int, heightPx: Int, style: Style): Loop? {
        val anim = WebpFrames.open(item.file) ?: return null
        val interval = pickInterval(anim.durationMs, anim.typicalDelayMs)
        val count = (anim.durationMs / interval).coerceIn(1, MAX_FRAMES)
        val (w, h) = outputSize(anim.width, anim.height, widthPx, heightPx, style.fit, count)
        val k = sourceScale(anim.width, anim.height, w, h, style.fit)
        val shots = anim.snapshots(count, (anim.width * k).toInt().coerceAtLeast(1), (anim.height * k).toInt().coerceAtLeast(1))
        if (shots.isEmpty()) return null
        return Loop(shots.map { compose(ctx, it, w, h, widthPx, style) }, interval)
    }

    @Suppress("DEPRECATION")
    private fun gifLoop(ctx: Context, item: Library.Item, widthPx: Int, heightPx: Int, style: Style): Loop? {
        val movie = android.graphics.Movie.decodeFile(item.file.path)
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return null
        val duration = movie.duration().coerceAtLeast(1)
        val interval = pickInterval(duration, gifTypicalDelay(item.file))
        val count = (duration / interval).coerceIn(1, MAX_FRAMES)
        val (w, h) = outputSize(movie.width(), movie.height(), widthPx, heightPx, style.fit, count)
        val k = sourceScale(movie.width(), movie.height(), w, h, style.fit)
        val src = Bitmap.createBitmap((movie.width() * k).toInt().coerceAtLeast(1), (movie.height() * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        val frames = (0 until count).map { i ->
            src.eraseColor(0)
            movie.setTime((i.toLong() * duration / count).toInt())
            canvas.save()
            canvas.scale(k, k)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            compose(ctx, src, w, h, widthPx, style)
        }
        return Loop(frames, interval)
    }

    /** The interval closest to the animation's own frame delay, slowed down only if too many frames would be needed. */
    private fun pickInterval(durationMs: Int, typicalDelayMs: Int): Int {
        var i = INTERVALS.indices.minByOrNull { Math.abs(INTERVALS[it] - typicalDelayMs) } ?: 0
        while (i < INTERVALS.lastIndex && durationMs / INTERVALS[i] > MAX_FRAMES) i++
        return INTERVALS[i]
    }

    /** Most common frame delay in a GIF, read from its graphic-control blocks (stored in 1/100 s). */
    private fun gifTypicalDelay(file: File): Int = try {
        val counts = HashMap<Int, Int>()
        java.io.BufferedInputStream(java.io.FileInputStream(file)).use { input ->
            val buf = ByteArray(8)
            var prev = -1
            var b = input.read()
            // 0x21 0xF9 0x04 flags delayLo delayHi transparentIndex 0x00
            while (b >= 0 && counts.values.sum() < 2000) {
                if (prev == 0x21 && b == 0xF9) {
                    input.mark(8)
                    if (input.read(buf, 0, 6) == 6 && buf[0].toInt() == 4 && buf[5].toInt() == 0) {
                        val cs = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                        val ms = if (cs <= 1) 100 else cs * 10
                        counts[ms] = (counts[ms] ?: 0) + 1
                    } else input.reset()
                }
                prev = b
                b = input.read()
            }
        }
        counts.maxByOrNull { it.value }?.key ?: DEFAULT_INTERVAL
    } catch (e: Exception) {
        DEFAULT_INTERVAL
    }

    /** Scale that draws a source of [sw]×[sh] into [w]×[h], never enlarging it. */
    private fun sourceScale(sw: Int, sh: Int, w: Int, h: Int, fit: Boolean): Float {
        val s = if (fit) minOf(w / sw.toFloat(), h / sh.toFloat()) else maxOf(w / sw.toFloat(), h / sh.toFloat())
        return minOf(1f, s)
    }

    /**
     * Bitmap size for a widget of [widthPx]×[heightPx] showing a [sw]×[sh] source: the widget's own size, reduced when the
     * source is smaller (no point storing upscaled pixels) or when [count] frames would not fit the memory budget.
     */
    private fun outputSize(sw: Int, sh: Int, widthPx: Int, heightPx: Int, fit: Boolean, count: Int): Pair<Int, Int> {
        val s = if (fit) minOf(widthPx / sw.toFloat(), heightPx / sh.toFloat()) else maxOf(widthPx / sw.toFloat(), heightPx / sh.toFloat())
        var w = widthPx.toFloat()
        var h = heightPx.toFloat()
        if (s > 1f) { w /= s; h /= s }
        val budget = FRAME_BUDGET_BYTES / (count * 4f)
        if (w * h > budget) {
            val k = Math.sqrt((budget / (w * h)).toDouble()).toFloat()
            w *= k; h *= k
        }
        val side = maxOf(w, h)
        if (side > MAX_SIDE) { val k = MAX_SIDE / side; w *= k; h *= k }
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    private fun compose(ctx: Context, src: Bitmap, w: Int, h: Int, widthPx: Int, style: Style): Bitmap {
        val scale = if (style.fit) minOf(w / src.width.toFloat(), h / src.height.toFloat())
        else maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val drawnW = src.width * scale
        val drawnH = src.height * scale
        val dx = (w - drawnW) / 2f
        val dy = (h - drawnH) / 2f
        val dst = if (style.fit) RectF(dx, dy, dx + drawnW, dy + drawnH) else RectF(0f, 0f, w.toFloat(), h.toFloat())

        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val radius = style.radiusDp * ctx.resources.displayMetrics.density * (w / widthPx.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(dst, radius, radius, paint)
        return out
    }

    /** First frame for GIF/WebP and video; RemoteViews cannot animate. */
    private fun source(item: Library.Item, target: Int): Bitmap? {
        val bmp = when {
            item.video -> frameOfVideo(item)
            item.animated -> if (Build.VERSION.SDK_INT >= 28) firstFrame(item, target) else null
            else -> Library.decode(item.file, target)
        }
        return bmp ?: BitmapFactory.decodeFile(item.thumb.path)
    }

    @TargetApi(28)
    private fun firstFrame(item: Library.Item, target: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(item.file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / (sample * 2) >= target) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        null
    }

    private fun frameOfVideo(item: Library.Item): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(item.file.path)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
