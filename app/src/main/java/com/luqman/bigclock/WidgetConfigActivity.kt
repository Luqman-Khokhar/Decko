package com.luqman.bigclock

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Shown when the widget is placed (and on long-press → reconfigure, Android 12+). */
class WidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Pinned from the preview screen: the design is already chosen.
        Prefs.takePendingWidgetTheme(this)?.let {
            choose(it)
            return
        }
        setContentView(R.layout.activity_widget_config)
        val list = findViewById<LinearLayout>(R.id.design_list)
        DesignList.populate(this, list, Prefs.widgetTheme(this, widgetId), ::choose)
    }

    private fun choose(theme: ClockTheme) {
        Prefs.setWidgetTheme(this, widgetId, theme)
        BigClockWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
