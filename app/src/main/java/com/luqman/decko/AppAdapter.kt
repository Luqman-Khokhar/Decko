package com.luqman.decko

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/** App drawer cells: pack-rendered icon over the label. */
class AppAdapter(private val activity: Activity) : BaseAdapter() {
    var apps: List<AppEntry> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    private val iconPx = (ICON_DP * activity.resources.displayMetrics.density).toInt()

    override fun getCount() = apps.size
    override fun getItem(position: Int) = apps[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
        val app = apps[position]
        view.findViewById<TextView>(R.id.label).text = app.label
        view.contentDescription = if (app.isWork) activity.getString(R.string.work_app, app.label) else app.label
        Icons.load(activity, app, iconPx, view.findViewById<ImageView>(R.id.icon))
        return view
    }

    companion object {
        const val ICON_DP = 52
    }
}
