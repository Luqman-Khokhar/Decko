package com.luqman.decko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * App icons as Decko Home shows them: the app's own icon redrawn through the chosen pack,
 * or the user's per-app override. Rendered off the main thread and kept in a memory cache.
 */
object Icons {
    private const val CUSTOM_SIDE = 256
    private const val PACK_PREFIX = "pack:"
    private const val IMAGE_PREFIX = "image:"

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Shows [app]'s icon in [into], from cache or rendered in the background. [forced] previews one pack
     * and ignores the app's override. Recycled views are safe: a late result only lands if the tag still matches.
     */
    fun load(ctx: Context, app: AppEntry, sizePx: Int, into: ImageView, forced: IconPack? = null) {
        val choice = if (forced != null) PACK_PREFIX + forced.id else Prefs.iconOverride(ctx, app.key)
        val key = "${Prefs.iconPack(ctx).id}|${Prefs.appTheme(ctx).id}|$choice|${app.key}|$sizePx"
        into.tag = key
        cache.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        val appCtx = ctx.applicationContext
        worker.execute {
            val bmp = try { render(appCtx, app, sizePx, choice) } catch (e: Exception) { null } ?: return@execute
            cache.put(key, bmp)
            main.post { if (into.tag == key) into.setImageBitmap(bmp) }
        }
    }

    fun overridePack(ctx: Context, app: AppEntry, pack: IconPack) = Prefs.setIconOverride(ctx, app.key, PACK_PREFIX + pack.id)

    fun clearOverride(ctx: Context, appKey: String) {
        Prefs.setIconOverride(ctx, appKey, null)
        customFile(ctx, appKey).delete()
    }

    fun clearAllOverrides(ctx: Context) {
        Prefs.clearIconOverrides(ctx)
        File(ctx.filesDir, "icons").listFiles()?.forEach { it.delete() }
    }

    fun isOverriddenWith(ctx: Context, app: AppEntry, pack: IconPack?): Boolean {
        val o = Prefs.iconOverride(ctx, app.key)
        return if (pack == null) o?.startsWith(IMAGE_PREFIX) == true else o == PACK_PREFIX + pack.id
    }

    /** Blocking; call off the main thread. Center-crops the picture to a square and makes it [appKey]'s icon. */
    fun saveCustom(ctx: Context, appKey: String, uri: Uri): Boolean = try {
        val src = decode(ctx, uri)
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, CUSTOM_SIDE, CUSTOM_SIDE, true)
        val file = customFile(ctx, appKey).apply { parentFile?.mkdirs() }
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The stamp changes the cache key, so a second custom picture replaces the first on screen.
        Prefs.setIconOverride(ctx, appKey, IMAGE_PREFIX + System.currentTimeMillis())
        true
    } catch (e: Exception) {
        false
    }

    private fun render(ctx: Context, app: AppEntry, size: Int, choice: String?): Bitmap {
        val theme = Prefs.appTheme(ctx)
        val custom = if (choice?.startsWith(IMAGE_PREFIX) == true) customFile(ctx, app.key).takeIf { it.exists() } else null
        val bmp = if (custom != null) {
            val picture = BitmapDrawable(ctx.resources, BitmapFactory.decodeFile(custom.path))
            IconRenderer.render(picture, size, Prefs.iconPack(ctx), theme, fullBleed = true)
        } else {
            val pack = choice?.takeIf { it.startsWith(PACK_PREFIX) }?.let { IconPack.byId(it.removePrefix(PACK_PREFIX)) } ?: Prefs.iconPack(ctx)
            IconRenderer.render(app.info.getIcon(ctx.resources.displayMetrics.densityDpi), size, pack, theme)
        }
        return if (app.isWork) badge(ctx, app, bmp, size) else bmp
    }

    /** Work-profile apps keep the system briefcase badge whatever the pack. */
    private fun badge(ctx: Context, app: AppEntry, bmp: Bitmap, size: Int): Bitmap {
        val badged: Drawable = ctx.packageManager.getUserBadgedIcon(BitmapDrawable(ctx.resources, bmp), app.user)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        badged.setBounds(0, 0, size, size)
        badged.draw(Canvas(out))
        return out
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF rotation and decodes straight to a small size.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val scale = CUSTOM_SIDE * 2f / minOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CUSTOM_SIDE * 2) sample *= 2
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unreadable image")
    }

    private fun customFile(ctx: Context, appKey: String) =
        File(File(ctx.filesDir, "icons"), appKey.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
}
