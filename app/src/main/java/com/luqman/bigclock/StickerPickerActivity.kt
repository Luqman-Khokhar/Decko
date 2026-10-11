package com.luqman.bigclock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Lists stickers found in the device's media: WebP and GIF files plus anything in a folder with "sticker" in its
 * path (where WhatsApp, Telegram and sticker apps save them). Picking one imports it and returns its library id.
 */
class StickerPickerActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var message: TextView
    private lateinit var allow: View

    private class Found(val uri: Uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stickers)
        grid = findViewById(R.id.sticker_grid)
        message = findViewById(R.id.message)
        allow = findViewById(R.id.allow)
        allow.setOnClickListener { requestPermissions(arrayOf(permission), REQ_PERMISSION) }
        if (hasPermission()) scan() else showPermissionPrompt()
    }

    private val permission
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPrompt() {
        message.setText(R.string.stickers_permission)
        message.visibility = View.VISIBLE
        allow.visibility = View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        if (hasPermission()) {
            allow.visibility = View.GONE
            message.visibility = View.GONE
            scan()
        }
    }

    private fun scan() {
        Library.io.execute {
            val found = query()
            runOnUiThread { if (!isDestroyed) show(found) }
        }
    }

    @Suppress("DEPRECATION")
    private fun query(): List<Found> {
        val out = ArrayList<Found>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.MIME_TYPE} IN ('image/webp','image/gif') OR ${MediaStore.Images.Media.DATA} LIKE '%ticker%'"
        try {
            contentResolver.query(
                collection, arrayOf(MediaStore.Images.Media._ID), selection, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_RESULTS) out += Found(ContentUris.withAppendedId(collection, c.getLong(0)))
            }
        } catch (e: Exception) {
            // Treated as "nothing found".
        }
        return out
    }

    private fun show(found: List<Found>) {
        grid.removeAllViews()
        if (found.isEmpty()) {
            message.setText(R.string.stickers_empty)
            message.visibility = View.VISIBLE
            return
        }
        val density = resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val cell = (resources.displayMetrics.widthPixels - (32 * density).toInt()) / grid.columnCount - 2 * gap
        for (sticker in found) {
            val tile = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(gap, gap, gap, gap)
                contentDescription = getString(R.string.sticker_item)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(getColor(R.color.tile_bg))
                }
                setOnClickListener { pick(sticker) }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = cell
                height = cell
                setMargins(gap, gap, gap, gap)
            })
            Library.io.execute {
                val bmp = thumbnail(sticker.uri, cell)
                runOnUiThread { if (!isDestroyed) tile.setImageBitmap(bmp) }
            }
        }
    }

    private fun thumbnail(uri: Uri, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(px, px), null)
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun pick(sticker: Found) {
        val busy = AlertDialog.Builder(this).setMessage(R.string.photo_widget_adding).setCancelable(false).show()
        Library.io.execute {
            val result = Library.import(this, sticker.uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy.dismiss()
                val item = result.item
                if (item == null || item.video) {
                    item?.let { Library.delete(it) }
                    AlertDialog.Builder(this).setMessage(if (item == null) result.error else R.string.import_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ITEM_ID, item.id))
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        private const val REQ_PERMISSION = 1
        private const val MAX_RESULTS = 400
    }
}
