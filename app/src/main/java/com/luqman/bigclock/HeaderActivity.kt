package com.luqman.bigclock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** The Header tab: turns the status-bar animation on and tunes it, with a live preview that uses the same drawing code. */
class HeaderActivity : Activity() {
    private lateinit var options: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: HeaderOverlayView
    private lateinit var enable: Switch
    private lateinit var permissionButton: Button
    private lateinit var permissionHint: TextView
    private lateinit var notificationAccess: Button
    private var characterGrid: GridLayout? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var awaitingPermission = false
    private val density get() = resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_header)
        ConfigTabs.bind(this, ConfigTabs.HEADER)
        options = findViewById(R.id.options)
        previewFrame = findViewById(R.id.preview_frame)
        preview = HeaderOverlayView(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        buildOptions()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission && HeaderOverlayService.canDraw(this)) {
            awaitingPermission = false
            setEnabled(true)
        }
        refreshState()
        refreshPreview()
        buildCharacters()
        preview.setActive(true)
        listener = Prefs.onHeaderChanged(this) { runOnUiThread { refreshPreview() } }
    }

    override fun onPause() {
        preview.setActive(false)
        listener?.let { Prefs.removeHeaderListener(this, it) }
        listener = null
        super.onPause()
    }

    private fun refreshPreview() {
        val c = Prefs.header(this)
        preview.apply(c)
        previewFrame.layoutParams = previewFrame.layoutParams.apply { height = maxOf(dp(32), dp(c.sizeDp + 6)) }
    }

    private fun refreshState() {
        val canDraw = HeaderOverlayService.canDraw(this)
        permissionButton.visibility = if (canDraw) View.GONE else View.VISIBLE
        permissionHint.visibility = permissionButton.visibility
        enable.setOnCheckedChangeListener(null)
        enable.isChecked = Prefs.header(this).enabled && canDraw
        enable.setOnCheckedChangeListener { _, checked -> onEnableToggled(checked) }
        notificationAccess.visibility =
            if (Prefs.header(this).trigger == HeaderConfig.TRIGGER_NOTIFICATION && !hasNotificationAccess()) View.VISIBLE else View.GONE
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().contains(packageName)

    private fun onEnableToggled(checked: Boolean) {
        if (checked && !HeaderOverlayService.canDraw(this)) {
            awaitingPermission = true
            enable.setOnCheckedChangeListener(null)
            enable.isChecked = false
            enable.setOnCheckedChangeListener { _, c -> onEnableToggled(c) }
            startActivity(HeaderOverlayService.overlayPermissionIntent(this))
            return
        }
        setEnabled(checked)
    }

    private fun setEnabled(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Prefs.editHeader(this) { putBoolean("header_enabled", on) }
        HeaderOverlayService.sync(this)
    }

    private fun buildOptions() {
        enable = Switch(this).apply {
            text = getString(R.string.header_enable)
            textSize = 18f
            setTextColor(getColor(R.color.config_text))
            minimumHeight = dp(56)
        }
        options.addView(enable, lp(top = 16))

        permissionHint = text(R.string.header_permission_hint, muted = true)
        options.addView(permissionHint, lp(top = 4))
        permissionButton = Button(this).apply {
            setText(R.string.header_permission)
            setOnClickListener {
                awaitingPermission = true
                startActivity(HeaderOverlayService.overlayPermissionIntent(this@HeaderActivity))
            }
        }
        options.addView(permissionButton, lp(top = 4))

        options.addView(Button(this).apply {
            setText(R.string.header_battery)
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, lp(top = 8))

        section(R.string.header_character)
        characterGrid = GridLayout(this).apply { columnCount = 4 }
        options.addView(characterGrid, lp())

        val c = Prefs.header(this)
        slider(R.string.header_size, HeaderConfig.MIN_SIZE_DP, HeaderConfig.MAX_SIZE_DP, c.sizeDp, "header_size") { getString(R.string.header_size, it) }
        slider(R.string.header_position, 0, 100, c.xPercent, "header_x") { getString(R.string.header_position, it) }
        choices(R.string.header_movement, listOf(R.string.header_stay, R.string.header_walk, R.string.header_bounce), c.movement, "header_move")
        slider(R.string.header_speed, 1, 10, c.speed, "header_speed") { getString(R.string.header_speed, it) }

        section(R.string.header_effect)
        choices(null, listOf(R.string.effect_none, R.string.effect_glow, R.string.effect_pulse, R.string.effect_sweep), c.effect, "header_effect")
        section(R.string.header_color)
        options.addView(swatches(c.color), lp())
        slider(R.string.header_effect_speed, 1, 10, c.effectSpeed, "header_effect_speed") { getString(R.string.header_effect_speed, it) }

        choices(R.string.header_show, listOf(R.string.trigger_always, R.string.trigger_charging, R.string.trigger_notification), c.trigger, "header_trigger") {
            refreshState()
        }
        notificationAccess = Button(this).apply {
            setText(R.string.header_notification_access)
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        options.addView(notificationAccess, lp(top = 4))

        val hide = Switch(this).apply {
            text = getString(R.string.header_hide_fullscreen)
            textSize = 16f
            setTextColor(getColor(R.color.config_text))
            minimumHeight = dp(56)
            isChecked = c.hideFullscreen
            setOnCheckedChangeListener { _, on -> Prefs.editHeader(this@HeaderActivity) { putBoolean("header_hide_fullscreen", on) } }
        }
        options.addView(hide, lp(top = 16))
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun text(res: Int, muted: Boolean = false) = TextView(this).apply {
        setText(res)
        textSize = if (muted) 14f else 16f
        setTextColor(getColor(if (muted) R.color.config_text_muted else R.color.config_text))
    }

    private fun section(res: Int) {
        options.addView(text(res).apply {
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(top = 24))
    }

    private fun slider(label: Int, min: Int, max: Int, value: Int, key: String, format: (Int) -> String) {
        val title = text(label).apply { text = format(value) }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            contentDescription = getString(label, value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    title.text = format(progress + min)
                    if (fromUser) Prefs.editHeader(this@HeaderActivity) { putInt(key, progress + min) }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        options.addView(title, lp(top = 16))
        options.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun choices(label: Int?, labels: List<Int>, selected: Int, key: String, after: () -> Unit = {}) {
        if (label != null) section(label)
        val group = RadioGroup(this)
        labels.forEachIndexed { i, res ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                setText(res)
                setTextColor(getColor(R.color.config_text))
                minHeight = dp(48)
                isChecked = i == selected
                setOnClickListener {
                    Prefs.editHeader(this@HeaderActivity) { putInt(key, i) }
                    after()
                }
            }, RadioGroup.LayoutParams(-1, -2))
        }
        options.addView(group, lp(top = 0))
    }

    private fun swatches(selected: Int): View {
        val colors = listOf(0xFF00E5FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFF1744, 0xFFFFC400, 0xFF76FF03, 0xFF00E676, 0xFFFFFFFF).map { it.toInt() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = ArrayList<View>()
        fun style(dot: View, color: Int, on: Boolean) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(if (on) 3 else 1), getColor(if (on) R.color.config_text else R.color.config_text_muted))
            }
        }
        colors.forEach { color ->
            val dot = View(this).apply {
                contentDescription = "#%06X".format(color and 0xFFFFFF)
                isClickable = true
                isFocusable = true
                style(this, color, color == selected)
                setOnClickListener {
                    dots.forEachIndexed { i, d -> style(d, colors[i], colors[i] == color) }
                    Prefs.editHeader(this@HeaderActivity) { putInt("header_color", color) }
                }
            }
            dots += dot
            row.addView(dot, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        }
        return row
    }

    private fun buildCharacters() {
        val grid = characterGrid ?: return
        grid.removeAllViews()
        val selectedId = Prefs.header(this).itemId
        val items = Library.list(this).filter { !it.video }
        val gap = dp(4)
        val cell = (resources.displayMetrics.widthPixels - dp(32)) / grid.columnCount - 2 * gap
        fun params() = GridLayout.LayoutParams().apply {
            width = cell
            height = cell
            setMargins(gap, gap, gap, gap)
        }
        fun tileBackground(on: Boolean) = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(getColor(R.color.tile_bg))
            if (on) setStroke(dp(3), getColor(R.color.icon_bg))
        }
        grid.addView(TextView(this).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.config_text_muted))
            background = tileBackground(selectedId == null)
            contentDescription = getString(R.string.header_none)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Prefs.editHeader(this@HeaderActivity) { remove("header_item") }
                buildCharacters()
            }
        }, params())
        for (item in items) {
            grid.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = tileBackground(item.id == selectedId)
                clipToOutline = true
                contentDescription = getString(if (item.animated) R.string.animated_item else R.string.wallpaper_item)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Prefs.editHeader(this@HeaderActivity) { putString("header_item", item.id) }
                    buildCharacters()
                }
                Library.io.execute {
                    val bmp = BitmapFactory.decodeFile(item.thumb.path)
                    runOnUiThread { setImageBitmap(bmp) }
                }
            }, params())
        }
        if (items.isEmpty()) {
            grid.addView(text(R.string.header_empty_characters, muted = true), GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(1, 3)
            })
        }
    }
}
