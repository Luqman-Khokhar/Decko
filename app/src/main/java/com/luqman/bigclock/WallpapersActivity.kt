package com.luqman.bigclock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/** The user's imported wallpapers: a grid with an add tile. Also the target for images shared or opened from other apps. */
class WallpapersActivity : Activity() {
    private lateinit var grid: GridLayout
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpapers)
        ConfigTabs.bind(this, ConfigTabs.WALLPAPERS)
        grid = findViewById(R.id.wall_grid)
        empty = findViewById(R.id.empty)
        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Plain framework Activity; no AndroidX result API here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            (data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.let { importAndOpen(it) }
        }
    }

    /** An image sent or opened from another app (browser, Files, Gallery) goes straight into the library. */
    private fun handleShared(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) importAndOpen(uri)
    }

    private fun importAndOpen(uri: Uri) {
        val busy = AlertDialog.Builder(this).setMessage(R.string.adding).setCancelable(false).show()
        Library.io.execute {
            val result = Library.import(this, uri)
            val item = result.item
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy.dismiss()
                if (item == null) {
                    AlertDialog.Builder(this).setMessage(result.error).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    refresh()
                    startActivity(Intent(this, WallpaperPreviewActivity::class.java).putExtra(WallpaperPreviewActivity.EXTRA_ID, item.id))
                }
            }
        }
    }

    private fun refresh() {
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val cell = (resources.displayMetrics.widthPixels - (32 * density).toInt()) / grid.columnCount - 2 * gap

        fun params() = GridLayout.LayoutParams().apply {
            width = cell
            height = cell
            setMargins(gap, gap, gap, gap)
        }
        fun background() = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(getColor(R.color.tile_bg))
        }

        grid.addView(TextView(this).apply {
            text = "+"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.config_text_muted))
            this.background = background()
            contentDescription = getString(R.string.add_wallpaper)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")),
                    REQ_PICK,
                )
            }
        }, params())

        val items = Library.list(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = item.id
            }
            val tile = FrameLayout(this).apply {
                this.background = background()
                clipToOutline = true
                contentDescription = getString(if (item.video) R.string.video_item else if (item.animated) R.string.animated_item else R.string.wallpaper_item)
                isClickable = true
                isFocusable = true
                addView(image, FrameLayout.LayoutParams(-1, -1))
                if (item.video || item.animated) {
                    addView(TextView(context).apply {
                        text = item.label
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(gap * 2, gap, gap * 2, gap)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        this.background = GradientDrawable().apply { cornerRadius = 12 * density; setColor(0x99000000.toInt()) }
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, gap * 2, gap * 2) })
                }
                setOnClickListener {
                    startActivity(Intent(this@WallpapersActivity, WallpaperPreviewActivity::class.java).putExtra(WallpaperPreviewActivity.EXTRA_ID, item.id))
                }
            }
            grid.addView(tile, params())
            Library.io.execute {
                val bmp = BitmapFactory.decodeFile(item.thumb.path)
                runOnUiThread { if (image.tag == item.id) image.setImageBitmap(bmp) }
            }
        }
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
