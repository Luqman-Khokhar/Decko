package com.luqman.decko

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.text.Collator
import java.util.concurrent.Executors

/**
 * Every widget installed on the phone, grouped by app with Decko's own first. Picking one returns
 * its provider to Decko Home, which binds, configures and places it.
 */
class WidgetPickerActivity : Activity() {
    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val info: AppWidgetProviderInfo, val label: String, val size: String) : Row()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val previews = HashMap<AppWidgetProviderInfo, Drawable?>()
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_picker)
        val list = findViewById<ListView>(R.id.widgets)
        val adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position] as? Row.Item ?: return@setOnItemClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PROVIDER, row.info.provider).putExtra(EXTRA_PROFILE, row.info.profile))
            finish()
        }
        val (cellW, cellH) = intent.getFloatExtra(EXTRA_CELL_W, 1f) to intent.getFloatExtra(EXTRA_CELL_H, 1f)
        worker.execute {
            val loaded = runCatching { load(cellW, cellH) }
            main.post {
                if (isDestroyed) return@post
                findViewById<ProgressBar>(R.id.progress).visibility = View.GONE
                rows = loaded.getOrDefault(emptyList())
                findViewById<TextView>(R.id.empty).apply {
                    visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    setText(if (loaded.isFailure) R.string.widgets_error else R.string.widgets_empty)
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load(cellW: Float, cellH: Float): List<Row> {
        val manager = Widgets.manager(this)
        val users = getSystemService(UserManager::class.java).userProfiles
        val infos = users.flatMap { manager.getInstalledProvidersForProfile(it) }
        val collator = Collator.getInstance()
        val byApp = infos.groupBy { it.provider.packageName to it.profile }
            .map { (key, group) ->
                val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(key.first, 0)).toString() }
                    .getOrDefault(key.first)
                Triple(key.first, appLabel, group)
            }
            // Decko first: its clock is the widget people come here for.
            .sortedWith(compareBy<Triple<String, String, List<AppWidgetProviderInfo>>> { it.first != packageName }.thenBy(collator) { it.second })
        return byApp.flatMap { (_, appLabel, group) ->
            listOf(Row.Header(appLabel)) + group.map {
                val (sx, sy) = Widgets.spanFor(it, cellW, cellH)
                Row.Item(it, it.loadLabel(packageManager), getString(R.string.widget_size, sx, sy))
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Row.Header -> (convertView ?: layoutInflater.inflate(R.layout.item_widget_header, parent, false)).apply {
                (this as TextView).text = row.title
            }
            is Row.Item -> (convertView ?: layoutInflater.inflate(R.layout.item_widget, parent, false)).apply {
                findViewById<TextView>(R.id.label).text = row.label
                findViewById<TextView>(R.id.size).text = row.size
                contentDescription = getString(R.string.widget_row_description, row.label, row.size)
                val image = findViewById<ImageView>(R.id.preview)
                image.tag = row.info
                if (previews.containsKey(row.info)) {
                    image.setImageDrawable(previews[row.info])
                } else {
                    image.setImageDrawable(null)
                    val density = resources.displayMetrics.densityDpi
                    worker.execute {
                        val d = runCatching { row.info.loadPreviewImage(this@WidgetPickerActivity, density) ?: row.info.loadIcon(this@WidgetPickerActivity, density) }.getOrNull()
                        main.post {
                            previews[row.info] = d
                            if (image.tag == row.info) image.setImageDrawable(d)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CELL_W = "cell_w"
        const val EXTRA_CELL_H = "cell_h"
    }
}
