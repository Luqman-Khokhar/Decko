package com.luqman.bigclock

import android.app.Activity
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** Full-screen preview of one design, with the actions to put it on the home screen or wallpaper. */
class PreviewActivity : Activity() {
    private lateinit var screen: ClockScreen
    private lateinit var controls: View
    private lateinit var digitalButton: TextView
    private lateinit var widgetButton: TextView
    private lateinit var wallpaperButton: TextView
    private lateinit var theme: ClockTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = ClockTheme.byId(intent.getStringExtra(EXTRA_THEME)) ?: Prefs.appTheme(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = findViewById<View>(R.id.root)
        screen = ClockScreen(root)
        controls = findViewById(R.id.controls)
        digitalButton = findViewById(R.id.digital_toggle)
        widgetButton = findViewById(R.id.add_widget)
        wallpaperButton = findViewById(R.id.lock_screen)
        findViewById<TextView>(R.id.design_name).setText(theme.title)
        screen.apply(theme)
        controls.visibility = View.VISIBLE
        if (theme.digital != null) digitalButton.visibility = View.GONE

        root.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.preview_full).setOnClickListener { controls.visibility = View.GONE }
        digitalButton.setOnClickListener {
            Prefs.setDigitalTime(this, !Prefs.digitalTime(this))
            screen.start()
            refreshButtons()
        }
        widgetButton.setOnClickListener { onWidgetClick() }
        wallpaperButton.setOnClickListener { onWallpaperClick() }

        Immersive.keepClear(root, controls)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        screen.start()
        refreshButtons()
    }

    override fun onPause() {
        screen.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun widgetOnHome(): Boolean {
        val mgr = AppWidgetManager.getInstance(this)
        return mgr.getAppWidgetIds(ComponentName(this, BigClockWidget::class.java))
            .any { Prefs.widgetTheme(this, it) == theme }
    }

    private fun wallpaperActive() = LiveWallpaper.isActive(this, ClockWallpaperService::class.java)

    private fun wallpaperIsThisDesign() = wallpaperActive() && Prefs.appTheme(this) == theme

    private fun refreshButtons() {
        digitalButton.setText(if (Prefs.digitalTime(this)) R.string.hide_digital else R.string.show_digital)
        widgetButton.setText(if (widgetOnHome()) R.string.remove_widget else R.string.add_widget)
        wallpaperButton.setText(
            when {
                wallpaperIsThisDesign() -> R.string.remove_wallpaper
                wallpaperActive() -> R.string.use_for_wallpaper
                else -> R.string.set_wallpaper
            },
        )
    }

    private fun onWidgetClick() {
        if (widgetOnHome()) {
            // Apps cannot delete their own widgets; only the launcher can.
            Toast.makeText(this, R.string.remove_widget_manual, Toast.LENGTH_LONG).show()
            return
        }
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            Prefs.setPendingWidgetTheme(this, theme)
            mgr.requestPinAppWidget(ComponentName(this, BigClockWidget::class.java), null, PinResultReceiver.callback(this))
        } else {
            Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show()
        }
    }

    private fun onWallpaperClick() {
        if (wallpaperIsThisDesign()) {
            try {
                WallpaperManager.getInstance(this).clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.remove_wallpaper_failed, Toast.LENGTH_LONG).show()
            }
            refreshButtons()
            return
        }
        Prefs.setAppTheme(this, theme)
        BigClockWidget.updateAll(this)
        if (wallpaperActive()) {
            refreshButtons()
            return
        }
        openWallpaperPicker()
    }

    private fun openWallpaperPicker() = LiveWallpaper.openPicker(this, ClockWallpaperService::class.java)

    private fun hideSystemBars() = Immersive.hideSystemBars(window)

    companion object {
        const val EXTRA_THEME = "theme"
    }
}
