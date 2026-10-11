package com.luqman.bigclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Home-screen clock. The face is a bitmap drawn once; the hands are the system AnalogClock,
 * which the launcher ticks itself — no alarms, no wakeups, correct 24/7 and across reboots.
 */
class BigClockWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        update(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) Prefs.clearWidget(ctx, id)
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val theme = Prefs.widgetTheme(ctx, id)
            val views = RemoteViews(ctx.packageName, theme.widgetLayout)
            views.setImageViewBitmap(R.id.widget_dial, ClockRenderer.dialBitmap(theme, dialSizePx(ctx, mgr, id, theme)))
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(id, views)
        }

        /** Re-renders widgets that follow the app design. */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, BigClockWidget::class.java))) {
                if (!Prefs.hasWidgetTheme(ctx, id)) update(ctx, mgr, id)
            }
        }

        private fun dialSizePx(ctx: Context, mgr: AppWidgetManager, id: Int, theme: ClockTheme): Int {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            val dp = if (theme.digital != null) w else minOf(w, h)
            return (dp * ctx.resources.displayMetrics.density).toInt().coerceIn(300, 900)
        }
    }
}
