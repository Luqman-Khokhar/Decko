package com.luqman.bigclock

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The user's imported wallpapers. Each image is downscaled once at import and stored privately,
 * so the library stays small and nothing depends on the original file or its permissions.
 */
object Library {
    private const val MAX_SIDE = 2560
    private const val THUMB = 320
    private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024
    private const val MAX_VIDEO_SECONDS = 60
    private const val MAX_ANIMATED_BYTES = 50L * 1024 * 1024

    /** One background thread for imports, thumbnails and wallpaper changes. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    class Item(val id: String, val file: File, val thumb: File, val video: Boolean = false, val animated: Boolean = false) {
        /** Short badge text for moving items, e.g. "GIF". */
        val label get() = if (animated) file.extension.uppercase() else "▶"
    }

    /** Result of an import: [item] on success, otherwise [error] is the message to show. */
    class Imported(val item: Item?, val error: Int = R.string.import_failed)

    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, name).apply { mkdirs() }

    fun list(ctx: Context): List<Item> {
        val thumbs = dir(ctx, "thumbs")
        val images = dir(ctx, "wallpapers").listFiles { f -> f.extension == "jpg" || f.extension == "png" }.orEmpty()
            .map { Item(it.nameWithoutExtension, it, File(thumbs, it.name)) }
        val videos = dir(ctx, "videos").listFiles { f -> f.extension == "vid" }.orEmpty()
            .map { Item(it.nameWithoutExtension, it, File(thumbs, it.nameWithoutExtension + ".jpg"), video = true) }
        val animated = dir(ctx, "animated").listFiles { f -> f.extension == "gif" || f.extension == "webp" }.orEmpty()
            .map { Item(it.nameWithoutExtension, it, File(thumbs, it.nameWithoutExtension + ".jpg"), animated = true) }
        return (images + videos + animated).sortedByDescending { it.file.lastModified() }
    }

    fun find(ctx: Context, id: String?): Item? = list(ctx).firstOrNull { it.id == id }

    fun delete(item: Item) {
        item.file.delete()
        item.thumb.delete()
    }

    /** Blocking; call off the main thread. Accepts images and short videos. */
    fun import(ctx: Context, uri: Uri): Imported {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        if (type.startsWith("video/")) return importVideo(ctx, uri)
        if (Build.VERSION.SDK_INT >= 28) sniffAnimated(ctx, uri)?.let { return importAnimated(ctx, uri, it) }
        val image = importImage(ctx, uri)
        if (image != null) return Imported(image)
        return if (type.isEmpty()) importVideo(ctx, uri) else Imported(null)
    }

    private fun importVideo(ctx: Context, uri: Uri): Imported {
        val id = "%x%04x".format(System.currentTimeMillis(), Random.nextInt(0xFFFF))
        val item = Item(id, File(dir(ctx, "videos"), "$id.vid"), File(dir(ctx, "thumbs"), "$id.jpg"), video = true)
        fun fail(error: Int): Imported {
            delete(item)
            return Imported(null, error)
        }
        try {
            when (copyCapped(ctx, uri, item.file, MAX_VIDEO_BYTES)) {
                COPY_TOO_BIG -> return fail(R.string.video_too_large)
                COPY_FAILED -> return fail(R.string.import_failed)
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(item.file.path)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return fail(R.string.import_failed)
                val seconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
                if (seconds > MAX_VIDEO_SECONDS) return fail(R.string.video_too_long)
                val frame = retriever.getFrameAtTime(if (seconds > 1) 500_000L else 0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return fail(R.string.import_failed)
                FileOutputStream(item.thumb).use {
                    ThumbnailUtils.extractThumbnail(frame, THUMB, THUMB).compress(Bitmap.CompressFormat.JPEG, 80, it)
                }
            } finally {
                retriever.release()
            }
            return Imported(item)
        } catch (e: Exception) {
            return fail(R.string.import_failed)
        }
    }

    private const val COPY_OK = 0
    private const val COPY_TOO_BIG = 1
    private const val COPY_FAILED = 2

    /** Streams [uri] into [dest], stopping if it grows past [cap] bytes. */
    private fun copyCapped(ctx: Context, uri: Uri, dest: File, cap: Long): Int {
        var copied = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > cap) return COPY_TOO_BIG
                    out.write(buf, 0, n)
                }
            }
        } ?: return COPY_FAILED
        return COPY_OK
    }

    /** "gif" or "webp" when the file's header says it is an animated image, else null. */
    private fun sniffAnimated(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val h = ByteArray(32)
            val n = s.read(h)
            when {
                n >= 4 && String(h, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "gif"
                n >= 21 && String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WEBP" &&
                    String(h, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (h[20].toInt() and 0x02) != 0 -> "webp"
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun importAnimated(ctx: Context, uri: Uri, ext: String): Imported {
        val id = "%x%04x".format(System.currentTimeMillis(), Random.nextInt(0xFFFF))
        val item = Item(id, File(dir(ctx, "animated"), "$id.$ext"), File(dir(ctx, "thumbs"), "$id.jpg"), animated = true)
        fun fail(error: Int): Imported {
            delete(item)
            return Imported(null, error)
        }
        return try {
            when (copyCapped(ctx, uri, item.file, MAX_ANIMATED_BYTES)) {
                COPY_TOO_BIG -> return fail(R.string.animated_too_large)
                COPY_FAILED -> return fail(R.string.import_failed)
            }
            // ImageDecoder is API 28+; older Android takes the first frame via BitmapFactory.
            val frame = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(item.file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    var sample = 1
                    while (minOf(info.size.width, info.size.height) / (sample * 2) >= THUMB) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            } else {
                decode(item.file, THUMB * 2) ?: return fail(R.string.import_failed)
            }
            FileOutputStream(item.thumb).use {
                ThumbnailUtils.extractThumbnail(frame, THUMB, THUMB).compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
            Imported(item)
        } catch (e: Exception) {
            fail(R.string.import_failed)
        }
    }

    /** Decodes an animated GIF/WebP, sampled down so it is no bigger than needed for a [w]×[h] screen. */
    @TargetApi(28)
    fun decodeAnimated(file: File, w: Int, h: Int): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / (sample * 2) >= w && info.size.height / (sample * 2) >= h) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        null
    }

    private fun importImage(ctx: Context, uri: Uri): Item? {
        val cr = ctx.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val raw = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val orientation = try {
                cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
            } catch (e: Exception) { 1 }

            val m = Matrix()
            when (orientation) {
                2 -> m.postScale(-1f, 1f)
                3 -> m.postRotate(180f)
                4 -> m.postScale(1f, -1f)
                5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.postRotate(90f)
                7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                8 -> m.postRotate(270f)
            }
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
            if (scale < 1f) m.postScale(scale, scale)
            val bmp = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            val id = "%x%04x".format(System.currentTimeMillis(), Random.nextInt(0xFFFF))
            // Stickers and cut-outs keep their transparency as PNG; everything else stays JPEG.
            val ext = if (bmp.hasAlpha()) "png" else "jpg"
            val format = if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val item = Item(id, File(dir(ctx, "wallpapers"), "$id.$ext"), File(dir(ctx, "thumbs"), "$id.$ext"))
            FileOutputStream(item.file).use { bmp.compress(format, 90, it) }
            FileOutputStream(item.thumb).use {
                ThumbnailUtils.extractThumbnail(bmp, THUMB, THUMB).compress(format, 80, it)
            }
            item
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes a library image no larger than about [maxSide] on its long edge. */
    fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
