package com.luqman.decko

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.hypot

/** Decko Home's app widget host: one per process, listening while the home screen is started. */
object Widgets {
    const val HOST_ID = 0x0DEC

    private var host: DeckoWidgetHost? = null

    fun host(ctx: Context): DeckoWidgetHost = host ?: DeckoWidgetHost(ctx.applicationContext).also { host = it }

    fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)

    /** Cells a new widget asks for: Android 12+ states it; older widgets give a minimum size in pixels. */
    fun spanFor(info: AppWidgetProviderInfo, cellW: Float, cellH: Float): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, HomeLayout.COLUMNS) to info.targetCellHeight.coerceIn(1, HomeLayout.ROWS)
        }
        return cells(info.minWidth, cellW, HomeLayout.COLUMNS) to cells(info.minHeight, cellH, HomeLayout.ROWS)
    }

    /** Smallest and largest spans the widget allows when resized. */
    fun resizeLimits(info: AppWidgetProviderInfo, cellW: Float, cellH: Float): IntArray {
        val minX = cells(info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth, cellW, HomeLayout.COLUMNS)
        val minY = cells(info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight, cellH, HomeLayout.ROWS)
        var maxX = HomeLayout.COLUMNS
        var maxY = HomeLayout.ROWS
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.maxResizeWidth > 0) maxX = cells(info.maxResizeWidth, cellW, HomeLayout.COLUMNS).coerceAtLeast(minX)
            if (info.maxResizeHeight > 0) maxY = cells(info.maxResizeHeight, cellH, HomeLayout.ROWS).coerceAtLeast(minY)
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun cells(px: Int, cell: Float, max: Int) = ceil(px / cell.coerceAtLeast(1f)).toInt().coerceIn(1, max)

    /** Tells the widget the size it now has, so it can pick a layout for it. */
    fun updateSize(view: AppWidgetHostView, widthPx: Float, heightPx: Float) {
        val density = view.resources.displayMetrics.density
        val w = widthPx / density
        val h = heightPx / density
        if (Build.VERSION.SDK_INT >= 31) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
        }
    }

    /** Settings activity the widget wants before it's placed, unless Android 12+ marks it optional. */
    fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0) return false
        return true
    }
}

class DeckoWidgetHost(ctx: Context) : AppWidgetHost(ctx, Widgets.HOST_ID) {
    /** A widget app was updated or removed; the home screen rebuilds its views. */
    var onProvidersChanged: (() -> Unit)? = null

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView = WidgetView(context)

    override fun onProvidersChanged() {
        onProvidersChanged?.invoke()
    }
}

/**
 * A hosted widget. Widgets often handle touches themselves, so the long-press that picks a widget up
 * is detected here, ahead of its content, the way launchers do it.
 */
class WidgetView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val check = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return fired
    }

    @Suppress("ClickableViewAccessibility") // Long-press also reaches the widget menu through the accessibility long-click below.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    override fun performLongClick(): Boolean {
        onLongPress?.invoke()
        return true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                fired = false
                removeCallbacks(check)
                postDelayed(check, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(check)
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(check)
    }

    override fun getErrorView(): View = TextView(context).apply {
        setText(R.string.widget_error)
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.controls_text))
        setBackgroundResource(R.drawable.search_bg)
    }
}
