package com.luqman.bigclock

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Supplies the pre-rendered GIF frames that a looping photo widget flips through. */
class PhotoFrameService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Factory(private val ctx: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var frames: List<Bitmap> = emptyList()

        override fun onCreate() {}

        /** Runs off the main thread, so decoding every frame here is fine. */
        override fun onDataSetChanged() {
            frames = emptyList()
            val config = Prefs.photoWidget(ctx, widgetId) ?: return
            val item = Library.find(ctx, config.itemId) ?: return
            val (w, h) = PhotoWidget.sizePx(ctx, AppWidgetManager.getInstance(ctx), widgetId)
            val loop = PhotoRenderer.loop(ctx, item, w, h, PhotoRenderer.Style(config.fit, config.radiusDp)) ?: return
            frames = loop.frames
            // The flip speed lives in the widget layout, so switch layouts when this animation wants a different one.
            if (loop.intervalMs != Prefs.loopInterval(ctx, widgetId)) {
                Prefs.setLoopInterval(ctx, widgetId, loop.intervalMs)
                PhotoWidget.update(ctx, AppWidgetManager.getInstance(ctx), widgetId)
            }
        }

        override fun onDestroy() {
            frames = emptyList()
        }

        override fun getCount() = frames.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_photo_frame)
            frames.getOrNull(position)?.let { views.setImageViewBitmap(R.id.photo_frame, it) }
            views.setOnClickFillInIntent(R.id.photo_frame, Intent())
            return views
        }

        /** Blank instead of the launcher's default "Loading…" text. */
        override fun getLoadingView(): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_photo_frame)
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
