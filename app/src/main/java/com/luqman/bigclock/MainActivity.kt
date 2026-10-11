package com.luqman.bigclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Gallery: one thumbnail per clock design, with a tab to the user's own wallpapers. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        val list = findViewById<LinearLayout>(R.id.design_list)
        DesignList.populate(this, list, null) { theme ->
            startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_THEME, theme.id))
        }
        ConfigTabs.bind(this, ConfigTabs.CLOCKS)
    }
}
