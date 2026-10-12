package com.luqman.decko

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/** Fills the home pages and the dock from a [HomeLayout]. Page 0 opens with the clock across its top rows. */
class HomeScreen(
    private val activity: Activity,
    val pager: HomePager,
    val dock: CellLayout,
    private val indicator: PageIndicator,
    private val onLaunch: (AppEntry, View) -> Unit,
    private val onOpenFolder: (HomeLayout.Cell, HomeItem.Folder, View) -> Unit,
    private val onLongPress: (HomeItem, View, HomeLayout.Cell) -> Unit,
) {
    var layout: HomeLayout? = null
        private set

    /** Cell whose icon is being dragged; its view stays invisible until the drag ends. */
    var hiddenCell: HomeLayout.Cell? = null
        set(value) { field = value; applyHidden() }

    val clock = AnalogClockView(activity).apply {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        setOnClickListener { activity.startActivity(Intent(activity, MainActivity::class.java)) }
    }

    private var apps: Map<String, AppEntry> = emptyMap()
    private val density = activity.resources.displayMetrics.density
    private val iconPx = (AppAdapter.ICON_DP * density).toInt()
    private val previewPx = (PREVIEW_DP * density).toInt()

    init {
        dock.columns = HomeLayout.DOCK_SLOTS
        dock.rows = 1
        pager.onScroll = { indicator.position = it }
        bind()
    }

    fun setData(layout: HomeLayout, apps: List<AppEntry>) {
        this.layout = layout
        this.apps = apps.associateBy { it.key }
        bind()
    }

    fun app(key: String) = apps[key]

    /** Folder's shown name: the user's, or the default. */
    fun folderName(folder: HomeItem.Folder) = folder.name.ifEmpty { activity.getString(R.string.folder_default) }

    /** CellLayout for a home page. */
    fun page(index: Int) = pager.getChildAt(index) as CellLayout

    /** Rebuilds every page and the dock; cheap at launcher scale (a few dozen views) and picks up pack changes. */
    fun bind() {
        val l = layout
        val pageCount = l?.pages ?: 1
        (clock.parent as? CellLayout)?.removeView(clock)
        pager.removeAllViews()
        for (p in 0 until pageCount) {
            val page = CellLayout(activity)
            if (p == 0) page.addView(clock, CellLayout.LayoutParams(0, 0, HomeLayout.COLUMNS, HomeLayout.CLOCK_ROWS))
            pager.addView(page)
        }
        dock.removeAllViews()
        l?.items?.forEach { (cell, item) ->
            val view = when (item) {
                is HomeItem.App -> apps[item.key]?.let { appIcon(it, item, cell) }
                is HomeItem.Folder -> folderIcon(item, cell)
            } ?: return@forEach
            (if (cell.dock) dock else page(cell.page)).addView(view, CellLayout.LayoutParams(cell.x, cell.y))
        }
        indicator.count = pageCount
        pager.snapTo(pager.currentPage, animate = false)
        applyHidden()
    }

    /** The view showing [cell], for opening a folder from its icon. */
    fun viewAt(cell: HomeLayout.Cell): View? =
        (if (cell.dock) dock else pager.getChildAt(cell.page) as? CellLayout)?.childAt(cell.x, cell.y)

    private fun applyHidden() {
        val cell = hiddenCell
        val parents = listOf(dock) + (0 until pager.childCount).map { page(it) }
        for (parent in parents) for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag is HomeLayout.Cell) child.visibility = if (child.tag == cell) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun appIcon(app: AppEntry, item: HomeItem.App, cell: HomeLayout.Cell): View {
        val view = activity.layoutInflater.inflate(R.layout.item_home_icon, dock, false)
        label(view, app.label, cell)
        view.contentDescription = if (app.isWork) activity.getString(R.string.work_app, app.label) else app.label
        Icons.load(activity, app, iconPx, view.findViewById<ImageView>(R.id.icon))
        view.tag = cell
        view.setOnClickListener { onLaunch(app, view) }
        view.setOnLongClickListener { onLongPress(item, view, cell); true }
        return view
    }

    /** First four apps in a 2×2 preview, like the folder's open grid in miniature. */
    private fun folderIcon(folder: HomeItem.Folder, cell: HomeLayout.Cell): View {
        val view = activity.layoutInflater.inflate(R.layout.item_folder_icon, dock, false)
        val name = folderName(folder)
        label(view, name, cell)
        view.contentDescription = activity.resources.getQuantityString(R.plurals.folder_description, folder.apps.size, name, folder.apps.size)
        val grid = view.findViewById<GridLayout>(R.id.preview)
        for (key in folder.apps.take(PREVIEW_COUNT)) {
            val app = apps[key] ?: continue
            val image = ImageView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            Icons.load(activity, app, previewPx, image)
            grid.addView(image, GridLayout.LayoutParams().apply {
                width = previewPx
                height = previewPx
                val m = (PREVIEW_GAP_DP * density).toInt()
                setMargins(m, m, m, m)
            })
        }
        view.tag = cell
        view.setOnClickListener { onOpenFolder(cell, folder, view) }
        view.setOnLongClickListener { onLongPress(folder, view, cell); true }
        return view
    }

    private fun label(view: View, text: String, cell: HomeLayout.Cell) {
        view.findViewById<TextView>(R.id.label).apply {
            this.text = text
            // The dock row is too short for labels; TalkBack still reads the name.
            visibility = if (cell.dock) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val PREVIEW_COUNT = 4
        const val PREVIEW_DP = 20
        const val PREVIEW_GAP_DP = 2
    }
}
