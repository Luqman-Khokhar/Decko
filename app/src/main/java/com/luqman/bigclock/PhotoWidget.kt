package com.luqman.bigclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

/** Home-screen widget showing one of the user's library items. Resizable; the image is re-rendered to the new size. */
class PhotoWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        update(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) Prefs.clearPhotoWidget(ctx, id)
    }

    companion object {
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            // Launchers differ on whether they run the configure screen for a pinned widget, so adopt the settings picked in the app.
            val config = Prefs.photoWidget(ctx, id)
                ?: Prefs.takePendingPhotoWidget(ctx)?.also { Prefs.setPhotoWidget(ctx, id, it) }
                ?: return
            val item = Library.find(ctx, config.itemId) ?: return
            if (PhotoRenderer.canLoop(item)) {
                updateLooping(ctx, mgr, id)
                return
            }
            val (w, h) = sizePx(ctx, mgr, id)
            val bitmap = PhotoRenderer.render(ctx, item, w, h, PhotoRenderer.Style(config.fit, config.radiusDp)) ?: return
            val views = RemoteViews(ctx.packageName, R.layout.widget_photo)
            views.setImageViewBitmap(R.id.photo_image, bitmap)
            views.setOnClickPendingIntent(
                R.id.photo_root,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, WallpapersActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            mgr.updateAppWidget(id, views)
        }

        private fun loopLayout(ms: Int) = when (ms) {
            40 -> R.layout.widget_photo_loop_40
            50 -> R.layout.widget_photo_loop_50
            60 -> R.layout.widget_photo_loop_60
            70 -> R.layout.widget_photo_loop_70
            80 -> R.layout.widget_photo_loop_80
            130 -> R.layout.widget_photo_loop_130
            200 -> R.layout.widget_photo_loop_200
            else -> R.layout.widget_photo_loop_100
        }

        /** GIFs: a system-driven flipper cycles pre-rendered frames, so nothing here runs between frames. */
        private fun updateLooping(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, loopLayout(Prefs.loopInterval(ctx, id)))
            val service = Intent(ctx, PhotoFrameService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.photo_flipper, service)
            views.setPendingIntentTemplate(
                R.id.photo_flipper,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, WallpapersActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            mgr.updateAppWidget(id, views)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.photo_flipper)
        }

        /** Pixel size of the widget as currently placed. Launchers report portrait as (min width, max height) and landscape as (max width, min height). */
        fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val d = ctx.resources.displayMetrics.density
            val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val w = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160)
            val h = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160)
            return (w * d).toInt().coerceAtLeast(1) to (h * d).toInt().coerceAtLeast(1)
        }

        fun componentName(ctx: Context) = ComponentName(ctx, PhotoWidget::class.java)
    }
}
