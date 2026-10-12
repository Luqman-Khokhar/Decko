package com.luqman.decko

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Control center card: battery level and charging, as words and a bar; tapping opens battery usage. */
class DeviceCard(private val kit: CcKit, onTap: () -> Unit) {
    private val ui = kit.ui
    private val status = kit.text(12f, kit.muted)
    private val fill = View(ui).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ui.getColor(R.color.cc_charge), ui.getColor(R.color.cc_charge_deep))).apply {
            cornerRadius = kit.dp(3).toFloat()
        }
    }
    private val rest = View(ui)
    private val bolt = kit.icon(R.drawable.ic_cc_bolt, 0, null).apply { setColorFilter(ui.getColor(R.color.cc_charge)) }

    val view: View = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = kit.pane(26f)
        setPadding(kit.dp(12), kit.dp(14), kit.dp(12), kit.dp(14))
        addView(kit.icon(R.drawable.ic_cc_phone, 14), LinearLayout.LayoutParams(kit.dp(50), kit.dp(50)))
        addView(kit.column(
            kit.text(15f, medium = true).apply { setText(R.string.cc_my_device) },
            status,
            LinearLayout(ui).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(ui).apply {
                    background = GradientDrawable().apply { cornerRadius = kit.dp(3).toFloat(); setColor(ui.getColor(R.color.cc_track)) }
                    clipToOutline = true
                    weightSum = 100f
                    addView(fill, LinearLayout.LayoutParams(0, -1, 0f))
                    addView(rest, LinearLayout.LayoutParams(0, -1, 100f))
                }, LinearLayout.LayoutParams(0, kit.dp(6), 1f))
                addView(bolt, LinearLayout.LayoutParams(kit.dp(14), kit.dp(14)).apply { marginStart = kit.dp(6) })
            }.also { it.setPadding(0, kit.dp(8), 0, 0) },
        ), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(12); marginEnd = kit.dp(6) })
        addView(kit.chevron(), LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)))
        kit.pressable(this, onClick = onTap)
    }

    fun bind(pct: Int, charging: Boolean) {
        status.text = ui.getString(if (charging) R.string.cc_charging else R.string.cc_battery_line, pct)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        fill.requestLayout()
        // Keeps its space while hidden, so the bar doesn't jump when the charger goes in or out.
        bolt.visibility = if (charging) View.VISIBLE else View.INVISIBLE
        view.contentDescription = ui.getString(R.string.cc_my_device) + ", " + status.text
    }
}
