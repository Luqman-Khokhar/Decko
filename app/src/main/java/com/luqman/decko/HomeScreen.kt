package com.luqman.decko

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/** Fills the home pages and the dock from a [HomeLayout]: app icons, folders and hosted widgets. */
class HomeScreen(
    private val activity: Activity,
    val pager: HomePager,
    val dock: CellLayout,
    private val indicator: PageIndicator,
    private val onLaunch: (AppEntry, View) -> Unit,
    private val onOpenFolder: (HomeLayout.Cell, HomeItem.Folder, View) -> Unit,
    private val onLongPress: (HomeItem, View, HomeLayout.Cell) -> Unit,
    private val style: HomeStyle,
    /** Long-press on empty page space, at screen coordinates. */
    private val onPageLongPress: (Float, Float) -> Unit,
) {
    var layout: HomeLayout? = null
        private set

    /** Cell whose icon is being dragged; its view stays invisible until the drag ends. */
    var hiddenCell: HomeLayout.Cell? = null
        set(value) { field = value; applyHidden() }

    /** Widget views live across rebinds, so a widget keeps its state (a scrolled list, a playing clip). */
    private val widgetViews = HashMap<Int, WidgetView>()

    private var apps: Map<String, AppEntry> = emptyMap()
    private val touch = floatArrayOf(0f, 0f)
    private val density = activity.resources.displayMetrics.density
    private val iconPx = (AppAdapter.ICON_DP * density).toInt()
    private val previewPx = (PREVIEW_DP * density).toInt()

    init {
        dock.columns = HomeLayout.DOCK_SLOTS
        dock.rows = 1
        pager.onScroll = { indicator.position = it }
        pager.pageTransform = style::transformPage
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
    @SuppressLint("ClickableViewAccessibility") // The page touch listener only notes where a press starts; it never consumes.
    fun bind() {
        val l = layout
        val pageCount = l?.pages ?: 1
        for (v in widgetViews.values) (v.parent as? CellLayout)?.removeView(v)
        pager.removeAllViews()
        for (p in 0 until pageCount) pager.addView(CellLayout(activity).apply {
            // Long-press anchors its menu where the finger is, so remember where touches start.
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { touch[0] = e.rawX; touch[1] = e.rawY }
                false
            }
            setOnLongClickListener { onPageLongPress(touch[0], touch[1]); true }
        })
        dock.removeAllViews()
        val placed = HashSet<Int>()
        l?.items?.forEach { (cell, item) ->
            val view = when (item) {
                is HomeItem.App -> apps[item.key]?.let { appIcon(it, item, cell) }
                is HomeItem.Folder -> folderIcon(item, cell)
                is HomeItem.Widget -> widgetView(item, cell)?.also { placed += item.id }
            } ?: return@forEach
            (if (cell.dock) dock else page(cell.page)).addView(view, CellLayout.LayoutParams(cell.x, cell.y, item.spanX, item.spanY))
        }
        widgetViews.keys.retainAll(placed)
        pager.post(::sizeWidgets)
        indicator.count = pageCount
        pager.snapTo(pager.currentPage, animate = false)
        applyHidden()
    }

    /** Pixel size of one home cell; before the first layout, an estimate from the screen. */
    fun cellSize(): Pair<Float, Float> {
        (pager.getChildAt(0) as? CellLayout)?.takeIf { it.width > 0 }?.let { return it.cellW to it.cellH }
        val dm = activity.resources.displayMetrics
        return dm.widthPixels / HomeLayout.COLUMNS.toFloat() to dm.heightPixels * 0.7f / HomeLayout.ROWS
    }

    /** The hosted view for widget [id], if it's on screen. */
    fun widget(id: Int): WidgetView? = widgetViews[id]

    private fun widgetView(item: HomeItem.Widget, cell: HomeLayout.Cell): View? {
        val info = Widgets.manager(activity).getAppWidgetInfo(item.id) ?: return null
        val view = widgetViews.getOrPut(item.id) {
            Widgets.host(activity).createView(activity, item.id, info) as WidgetView
        }
        view.tag = cell
        view.onLongPress = { onLongPress(item, view, cell) }
        return view
    }

    /** Widgets learn their real size once pages are laid out, and again after any resize. */
    private fun sizeWidgets() {
        val (cw, ch) = cellSize()
        layout?.items?.forEach { (_, item) ->
            if (item is HomeItem.Widget) widgetViews[item.id]?.let { Widgets.updateSize(it, cw * item.spanX, ch * item.spanY) }
        }
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
        style.folderTile(activity)?.let { view.findViewById<View>(R.id.icon).background = it }
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
        style.pressFeedback(view)
        view.findViewById<TextView>(R.id.label).apply {
            this.text = text
            style.homeLabel(activity)?.let { (color, shadow) ->
                setTextColor(color)
                setShadowLayer(if (shadow) 6f else 0f, 0f, 0f, activity.getColor(R.color.icon_bg))
            }
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
