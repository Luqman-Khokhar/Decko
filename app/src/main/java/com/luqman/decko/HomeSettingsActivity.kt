package com.luqman.decko

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The Home tab: make Decko the home app and choose the icon pack its app drawer uses. */
class HomeSettingsActivity : Activity() {
    private lateinit var packs: GridLayout
    private var samples: List<AppEntry> = emptyList()

    /** Set once the role dialog comes back refused: cancelled, or silently denied by the OEM build. */
    private var roleRefused = false
    private val density get() = resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ConfigTabs.bind(this, ConfigTabs.HOME)
        packs = findViewById(R.id.packs)
        findViewById<Button>(R.id.set_default).setOnClickListener {
            if (roleRefused) HomeSetup.openSettings(this) else HomeSetup.request(this, REQ_HOME_ROLE)
        }
        findViewById<Button>(R.id.open_home).setOnClickListener { startActivity(Intent(this, LauncherActivity::class.java)) }
        findViewById<Button>(R.id.reset_custom).setOnClickListener {
            Icons.clearAllOverrides(this)
            refresh()
        }
        Apps.io.execute {
            val apps = runCatching { Apps.load(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                samples = pickSamples(apps)
                buildPacks()
            }
        }
        buildPacks()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // A cancel is a choice; don't push Settings at the user, offer it on the button instead.
        if (requestCode == REQ_HOME_ROLE && !HomeSetup.isDefaultHome(this)) {
            roleRefused = true
            refresh()
        }
    }

    private fun refresh() {
        val isDefault = HomeSetup.isDefaultHome(this)
        findViewById<TextView>(R.id.home_status).setText(if (isDefault) R.string.home_is_default else R.string.home_not_default)
        findViewById<Button>(R.id.set_default).apply {
            visibility = if (isDefault) View.GONE else View.VISIBLE
            setText(if (roleRefused) R.string.home_choose_in_settings else R.string.home_set_default)
        }
        val custom = Prefs.iconOverrideCount(this)
        findViewById<TextView>(R.id.custom_count).text = resources.getQuantityString(R.plurals.icon_custom_count, custom, custom)
        findViewById<View>(R.id.reset_custom).visibility = if (custom == 0) View.GONE else View.VISIBLE
    }

    /** Decko itself first, then apps spread across the alphabet, so each card previews a varied set. */
    private fun pickSamples(apps: List<AppEntry>): List<AppEntry> {
        val own = apps.filter { it.component.packageName == packageName && !it.isWork }.take(1)
        val rest = apps.filter { !it.isWork && it.component.packageName != packageName }
        val step = (rest.size / (SAMPLE_COUNT - own.size).coerceAtLeast(1)).coerceAtLeast(1)
        return (own + rest.filterIndexed { i, _ -> i % step == 0 }).take(SAMPLE_COUNT)
    }

    private fun buildPacks() {
        packs.removeAllViews()
        val selected = Prefs.iconPack(this)
        val iconPx = dp(SAMPLE_DP)
        for (pack in IconPack.entries) {
            val chosen = pack == selected
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (i in 0 until SAMPLE_COUNT) {
                val icon = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                samples.getOrNull(i)?.let { Icons.load(this, it, iconPx, icon, forced = pack) }
                row.addView(icon, LinearLayout.LayoutParams(iconPx, iconPx).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
            val title = getString(pack.title)
            val label = TextView(this).apply {
                text = if (chosen) getString(R.string.current_design, title) else title
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(getColor(R.color.config_text))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(16), dp(8), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(getColor(R.color.tile_bg))
                    if (chosen) setStroke(dp(3), getColor(R.color.icon_bg))
                }
                foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
                isClickable = true
                isFocusable = true
                isSelected = chosen
                contentDescription = label.text
                addView(row)
                addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                setOnClickListener {
                    Prefs.setIconPack(this@HomeSettingsActivity, pack)
                    buildPacks()
                }
            }
            packs.addView(card, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
    }

    private companion object {
        const val REQ_HOME_ROLE = 1
        const val SAMPLE_COUNT = 4
        const val SAMPLE_DP = 32
    }
}
