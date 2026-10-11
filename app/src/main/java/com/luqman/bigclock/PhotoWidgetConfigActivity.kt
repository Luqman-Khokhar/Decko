package com.luqman.bigclock

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Pick the picture, how it fits, and the corner rounding. Runs as the widget's configure screen, and as the
 * Widgets tab (no widget id) where "Add to home screen" pins a new widget with these settings.
 */
class PhotoWidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val standalone get() = widgetId == AppWidgetManager.INVALID_APPWIDGET_ID
    private var selected: Library.Item? = null
    private lateinit var preview: ImageView
    private lateinit var done: Button
    private lateinit var fitGroup: RadioGroup
    private lateinit var corners: SeekBar
    private val tiles = HashMap<String, View>()
    private var renderToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!standalone) {
            // Pinned from the Widgets tab: the settings are already chosen.
            Prefs.takePendingPhotoWidget(this)?.let {
                Prefs.setPhotoWidget(this, widgetId, it)
                PhotoWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
                return
            }
        }
        setContentView(R.layout.activity_photo_widget_config)
        if (standalone) {
            ConfigTabs.bind(this, ConfigTabs.WIDGETS)
            findViewById<Button>(R.id.done).setText(R.string.photo_widget_add)
        } else {
            findViewById<View>(R.id.tab_bar).visibility = View.GONE
        }
        preview = findViewById(R.id.preview)
        done = findViewById(R.id.done)
        fitGroup = findViewById(R.id.fit_group)
        corners = findViewById(R.id.corners)

        val existing = if (standalone) null else Prefs.photoWidget(this, widgetId)
        fitGroup.check(if (existing?.fit == true) R.id.fit_whole else R.id.fit_fill)
        corners.progress = existing?.radiusDp ?: 16
        fitGroup.setOnCheckedChangeListener { _, _ -> renderPreview() }
        corners.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateCornersLabel()
                renderPreview()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        updateCornersLabel()
        findViewById<View>(R.id.find_stickers).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, StickerPickerActivity::class.java), REQ_STICKER)
        }
        done.setOnClickListener { finishWidget() }
        buildGrid(existing?.itemId)
    }

    @Deprecated("Plain framework Activity; no AndroidX result API here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_STICKER) {
            if (resultCode == RESULT_OK) buildGrid(data?.getStringExtra(StickerPickerActivity.EXTRA_ITEM_ID))
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return
        val busy = AlertDialog.Builder(this).setMessage(R.string.photo_widget_adding).setCancelable(false).show()
        Library.io.execute {
            val result = Library.import(this, uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy.dismiss()
                val item = result.item
                if (item == null || item.video) {
                    item?.let { Library.delete(it) }
                    AlertDialog.Builder(this).setMessage(if (item == null) result.error else R.string.import_failed)
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    buildGrid(item.id)
                }
            }
        }
    }

    private fun buildGrid(selectedId: String?) {
        val grid = findViewById<GridLayout>(R.id.pick_grid)
        grid.removeAllViews()
        tiles.clear()
        val items = Library.list(this).filter { !it.video }
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val cell = (resources.displayMetrics.widthPixels - (32 * density).toInt()) / grid.columnCount - 2 * gap
        fun cellParams() = GridLayout.LayoutParams().apply {
            width = cell
            height = cell
            setMargins(gap, gap, gap, gap)
        }
        grid.addView(TextView(this).apply {
            text = "+"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.config_text_muted))
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(getColor(R.color.tile_bg))
            }
            contentDescription = getString(R.string.photo_widget_add_media)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK)
            }
        }, cellParams())
        for (item in items) {
            val tile = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(if (item.video) R.string.video_item else if (item.animated) R.string.animated_item else R.string.wallpaper_item)
                isClickable = true
                isFocusable = true
                clipToOutline = true
                setOnClickListener { select(item) }
            }
            tiles[item.id] = tile
            grid.addView(tile, cellParams())
            Library.io.execute {
                val bmp = BitmapFactory.decodeFile(item.thumb.path)
                runOnUiThread { tile.setImageBitmap(bmp) }
            }
        }
        items.firstOrNull { it.id == selectedId }?.let { select(it) }
    }

    private fun select(item: Library.Item) {
        selected = item
        val density = resources.displayMetrics.density
        for ((id, tile) in tiles) {
            tile.background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(getColor(R.color.tile_bg))
                if (id == item.id) setStroke((3 * density).toInt(), getColor(R.color.icon_bg))
            }
            tile.isSelected = id == item.id
        }
        done.isEnabled = true
        renderPreview()
    }

    private fun updateCornersLabel() {
        findViewById<TextView>(R.id.corners_label).text = getString(R.string.photo_widget_corners, corners.progress)
    }

    private fun style() = PhotoRenderer.Style(fitGroup.checkedRadioButtonId == R.id.fit_whole, corners.progress)

    private fun renderPreview() {
        val item = selected ?: return
        val (w, h) = if (standalone) (160 * resources.displayMetrics.density).toInt().let { it to it }
        else PhotoWidget.sizePx(this, AppWidgetManager.getInstance(this), widgetId)
        val style = style()
        val token = ++renderToken
        Library.io.execute {
            val bmp = PhotoRenderer.render(this, item, w, h, style)
            runOnUiThread { if (token == renderToken && !isDestroyed) preview.setImageBitmap(bmp) }
        }
    }

    private fun finishWidget() {
        val item = selected ?: return
        val style = style()
        val config = Prefs.PhotoWidgetConfig(item.id, style.fit, style.radiusDp)
        if (standalone) {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                Prefs.setPendingPhotoWidget(this, config)
                mgr.requestPinAppWidget(PhotoWidget.componentName(this), null, PinResultReceiver.callback(this))
            } else {
                Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show()
            }
            return
        }
        Prefs.setPhotoWidget(this, widgetId, config)
        PhotoWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_STICKER = 2
    }
}
