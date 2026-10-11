package com.luqman.decko

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/** Fills a list with one thumbnail card per clock design. Shared by the gallery and the widget picker. */
object DesignList {
    fun populate(activity: Activity, list: LinearLayout, selected: ClockTheme?, onPick: (ClockTheme) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val radius = 20 * density
        for (theme in ClockTheme.entries) {
            val item = activity.layoutInflater.inflate(R.layout.item_design, list, false)
            item.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(theme.background)
                if (theme == selected) setStroke((3 * density).toInt(), theme.text)
                else setStroke(density.toInt().coerceAtLeast(1), (theme.text and 0x00FFFFFF) or 0x40000000)
            }
            item.findViewById<AnalogClockView>(R.id.preview).apply {
                clockTheme = theme
                showFixedTime(10, 10, 30)
            }
            item.findViewById<TextView>(R.id.name).apply {
                text = if (theme == selected) activity.getString(R.string.current_design, activity.getString(theme.title)) else activity.getString(theme.title)
                setTextColor(theme.text)
            }
            item.contentDescription = activity.getString(theme.title)
            item.setOnClickListener { onPick(theme) }
            list.addView(item)
        }
    }
}
