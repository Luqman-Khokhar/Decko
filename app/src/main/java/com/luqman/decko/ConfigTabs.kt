package com.luqman.decko

import android.app.Activity
import android.content.Intent
import android.widget.TextView

/** Four-tab bar shared by the gallery, wallpapers, widgets and header screens. Each tab is its own activity; switching skips the transition. */
object ConfigTabs {
    const val CLOCKS = 0
    const val WALLPAPERS = 1
    const val WIDGETS = 2
    const val HEADER = 3

    fun bind(activity: Activity, selected: Int) {
        val tabs = listOf(R.id.tab_clocks, R.id.tab_wallpapers, R.id.tab_widgets, R.id.tab_header).map { activity.findViewById<TextView>(it) }
        tabs[selected].setBackgroundResource(R.drawable.tab_selected)
        tabs[selected].isSelected = true
        val targets = listOf(MainActivity::class.java, WallpapersActivity::class.java, PhotoWidgetConfigActivity::class.java, HeaderActivity::class.java)
        for (i in tabs.indices) {
            if (i == selected) continue
            tabs[i].setOnClickListener {
                val flags = Intent.FLAG_ACTIVITY_NO_ANIMATION or if (i == CLOCKS) Intent.FLAG_ACTIVITY_CLEAR_TOP else 0
                activity.startActivity(Intent(activity, targets[i]).addFlags(flags))
                if (i != CLOCKS) activity.finish()
            }
        }
    }
}
