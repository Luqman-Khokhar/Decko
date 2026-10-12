package com.luqman.decko

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Long-press → Change icon: the app drawn in every built-in pack, the user's own picture, or back to the Home tab's pack. */
object IconPicker {
    private const val PREVIEW_DP = 56
    private const val COLUMNS = 3

    fun show(activity: Activity, app: AppEntry, onPickImage: () -> Unit, onChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val previewPx = (PREVIEW_DP * density).toInt()
        val grid = GridLayout(activity).apply {
            columnCount = COLUMNS
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        lateinit var dialog: AlertDialog
        val hasOverride = Prefs.iconOverride(activity, app.key) != null

        fun cell(label: String, selected: Boolean, bind: (ImageView) -> Unit, onClick: () -> Unit) {
            val icon = ImageView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            bind(icon)
            val text = TextView(activity).apply {
                this.text = if (selected) activity.getString(R.string.current_design, label) else label
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
                if (selected) background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setStroke((2 * density).toInt(), activity.getColor(R.color.controls_text_muted))
                }
                contentDescription = text.text
                isSelected = selected
                addView(icon, LinearLayout.LayoutParams(previewPx, previewPx))
                addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
                setOnClickListener { dialog.dismiss(); onClick() }
            }
            grid.addView(item, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }

        for (pack in IconPack.entries) {
            cell(activity.getString(pack.title), Icons.isOverriddenWith(activity, app, pack), { Icons.load(activity, app, previewPx, it, forced = pack) }) {
                Icons.overridePack(activity, app, pack)
                onChanged()
            }
        }
        cell(activity.getString(R.string.icon_from_image), Icons.isOverriddenWith(activity, app, null), {
            it.setImageResource(R.drawable.ic_image)
            it.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.controls_text_muted))
        }, onPickImage)
        cell(activity.getString(R.string.icon_default), !hasOverride, { Icons.load(activity, app, previewPx, it, forced = Prefs.iconPack(activity)) }) {
            Icons.clearOverride(activity, app.key)
            onChanged()
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.change_icon_for, app.label))
            .setView(ScrollView(activity).apply { addView(grid) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
