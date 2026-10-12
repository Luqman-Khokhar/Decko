package com.luqman.decko

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** What a home cell holds: one app, a folder of apps (by [AppEntry.key]), or an app widget. */
sealed class HomeItem {
    data class App(val key: String) : HomeItem()

    /** Empty [name] shows as the default "Folder". A folder always holds at least two apps. */
    class Folder(var name: String, val apps: MutableList<String>) : HomeItem()

    /** A bound widget [id] from [provider] (flattened component), covering [spanX]×[spanY] cells from its anchor. */
    data class Widget(val id: Int, val provider: String, override val spanX: Int, override val spanY: Int) : HomeItem()

    open val spanX get() = 1
    open val spanY get() = 1
}

/**
 * Where each app, folder and widget sits on the home pages and in the dock, keyed by its anchor
 * (top-left) cell. Widgets cover several cells; the dock holds only single-cell items.
 * The same app may sit in several cells.
 */
class HomeLayout private constructor(private val cells: LinkedHashMap<Cell, HomeItem>, pages: Int) {
    data class Cell(val dock: Boolean, val page: Int, val x: Int, val y: Int)

    var pages = pages.coerceAtLeast(1)
        private set

    val items: Map<Cell, HomeItem> get() = cells

    private fun inBounds(c: Cell) =
        if (c.dock) c.page == 0 && c.y == 0 && c.x in 0 until DOCK_SLOTS
        else c.page in 0 until pages && c.x in 0 until COLUMNS && c.y in 0 until ROWS

    /** The anchor and item covering [cell], counting a widget's whole area. */
    fun occupantAt(cell: Cell): Pair<Cell, HomeItem>? = cells.entries.firstOrNull { (a, item) ->
        a.dock == cell.dock && a.page == cell.page && cell.x in a.x until a.x + item.spanX && cell.y in a.y until a.y + item.spanY
    }?.let { it.key to it.value }

    /** [ignoring] counts as free: it's the anchor an item is being dragged out of. */
    fun isFree(cell: Cell, ignoring: Cell? = null) =
        inBounds(cell) && occupantAt(cell)?.first.let { it == null || it == ignoring }

    /** Whether a [spanX]×[spanY] item fits with its anchor at [anchor]. */
    fun fits(anchor: Cell, spanX: Int, spanY: Int, ignoring: Cell? = null): Boolean {
        if (anchor.dock && (spanX > 1 || spanY > 1)) return false
        for (dx in 0 until spanX) for (dy in 0 until spanY) {
            if (!isFree(anchor.copy(x = anchor.x + dx, y = anchor.y + dy), ignoring)) return false
        }
        return true
    }

    /** Anchor closest to [cell] on the same page (or in the dock) where the item fits, or null when it can't. */
    fun nearestFree(cell: Cell, ignoring: Cell? = null, spanX: Int = 1, spanY: Int = 1): Cell? {
        val candidates = if (cell.dock) {
            (0 until DOCK_SLOTS).map { Cell(true, 0, it, 0) }
        } else {
            (0 until ROWS).flatMap { y -> (0 until COLUMNS).map { x -> Cell(false, cell.page, x, y) } }
        }
        return candidates.filter { fits(it, spanX, spanY, ignoring) }
            .minByOrNull { (it.x - cell.x) * (it.x - cell.x) + (it.y - cell.y) * (it.y - cell.y) }
    }

    /** First place in reading order across the pages, starting at [fromPage], opening a new page when all are full. */
    fun firstFree(spanX: Int = 1, spanY: Int = 1, fromPage: Int = 0): Cell {
        for (p in (fromPage until pages) + (0 until fromPage)) for (y in 0 until ROWS) for (x in 0 until COLUMNS) {
            Cell(false, p, x, y).takeIf { fits(it, spanX, spanY) }?.let { return it }
        }
        return Cell(false, addPage(), 0, 0)
    }

    fun pageHasItems(page: Int) = cells.keys.any { !it.dock && it.page == page }

    /** Puts [item] at [to]; [from] is the anchor it was dragged out of, or null when it comes from elsewhere. */
    fun move(from: Cell?, item: HomeItem, to: Cell) {
        from?.let { cells.remove(it) }
        cells[to] = item
    }

    /** Swaps the item at [anchor] for [item] in place, e.g. a widget after resizing. */
    fun replace(anchor: Cell, item: HomeItem) {
        cells[anchor] = item
    }

    /**
     * Drops app [key] onto the app or folder at [onto]: an app becomes a folder of both, a folder gains it.
     * [from] is where the dragged app was, and [fromFolder] says it came out of the folder there.
     */
    fun merge(from: Cell?, fromFolder: Boolean, key: String, onto: Cell) {
        if (from != null) if (fromFolder) removeFromFolder(from, key) else cells.remove(from)
        when (val target = cells[onto]) {
            is HomeItem.App -> cells[onto] = HomeItem.Folder("", mutableListOf(target.key, key))
            is HomeItem.Folder -> target.apps.add(key)
            is HomeItem.Widget -> Unit
            null -> cells[onto] = HomeItem.App(key)
        }
    }

    /** Takes one [key] out of the folder at [cell]; a folder left with one app turns back into that app. */
    fun removeFromFolder(cell: Cell, key: String) {
        val folder = cells[cell] as? HomeItem.Folder ?: return
        folder.apps.remove(key)
        when (folder.apps.size) {
            0 -> cells.remove(cell)
            1 -> cells[cell] = HomeItem.App(folder.apps[0])
        }
    }

    fun remove(cell: Cell) {
        cells.remove(cell)
    }

    fun addPage(): Int = pages++

    /**
     * Uninstalled package: drop its icons for that user (serial from [Apps.load]'s key) and its widgets.
     * Returns the removed widget ids, which the caller must release from the widget host.
     */
    fun removePackage(packageName: String, userSerial: Long): List<Int> {
        fun gone(key: String) = key.startsWith("$packageName/") && key.endsWith("#$userSerial")
        // Folders first: one can collapse into a single app that the second pass may also remove.
        for ((cell, item) in cells.entries.toList()) {
            if (item is HomeItem.Folder) item.apps.filter(::gone).forEach { removeFromFolder(cell, it) }
        }
        val widgets = cells.values.filterIsInstance<HomeItem.Widget>().filter { it.provider.startsWith("$packageName/") }
        cells.entries.removeAll { (_, item) -> (item is HomeItem.App && gone(item.key)) || item in widgets }
        return widgets.map { it.id }
    }

    /** Closes empty pages after the first; the rest keep their order. */
    fun trimPages() {
        val keep = (listOf(0) + cells.keys.filter { !it.dock }.map { it.page }.distinct().sorted()).distinct()
        val index = keep.withIndex().associate { (i, p) -> p to i }
        val moved = cells.entries.associate { (c, k) -> (if (c.dock) c else c.copy(page = index.getValue(c.page))) to k }
        cells.clear()
        cells.putAll(moved)
        pages = keep.size
    }

    fun save(ctx: Context) {
        val list = JSONArray()
        for ((c, item) in cells) {
            val o = JSONObject().put("d", c.dock).put("p", c.page).put("x", c.x).put("y", c.y)
            when (item) {
                is HomeItem.App -> o.put("k", item.key)
                is HomeItem.Folder -> o.put("f", item.name).put("a", JSONArray(item.apps))
                is HomeItem.Widget -> o.put("w", item.id).put("pv", item.provider).put("sx", item.spanX).put("sy", item.spanY)
            }
            list.put(o)
        }
        Prefs.setHomeLayout(ctx, JSONObject().put("pages", pages).put("items", list).toString())
    }

    companion object {
        const val COLUMNS = 4
        const val ROWS = 5
        const val DOCK_SLOTS = 4

        /** Saved layout, or on first run a dock of the phone's dialer, messages, browser and camera. */
        fun load(ctx: Context, apps: List<AppEntry>): HomeLayout {
            Prefs.homeLayout(ctx)?.let { raw ->
                try {
                    return parse(raw)
                } catch (e: Exception) {
                    // Corrupt or from a future format: start over rather than crash the home screen.
                }
            }
            return defaults(ctx, apps).also { it.save(ctx) }
        }

        private fun parse(raw: String): HomeLayout {
            val json = JSONObject(raw)
            val list = json.getJSONArray("items")
            val cells = LinkedHashMap<Cell, HomeItem>()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val cell = Cell(o.getBoolean("d"), o.getInt("p"), o.getInt("x"), o.getInt("y"))
                cells[cell] = when {
                    o.has("w") -> HomeItem.Widget(o.getInt("w"), o.getString("pv"), o.getInt("sx"), o.getInt("sy"))
                    o.has("f") -> {
                        val apps = o.getJSONArray("a")
                        HomeItem.Folder(o.getString("f"), MutableList(apps.length()) { apps.getString(it) })
                    }
                    else -> HomeItem.App(o.getString("k"))
                }
            }
            return HomeLayout(cells, json.getInt("pages"))
        }

        private fun defaults(ctx: Context, apps: List<AppEntry>): HomeLayout {
            val roles = listOf(
                Intent(Intent.ACTION_DIAL),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE),
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            )
            val personal = apps.filter { !it.isWork }
            // No default set resolves to the system chooser ("android"), which matches no app and leaves the slot empty.
            val dock = roles.mapNotNull { intent ->
                val pkg = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                personal.firstOrNull { it.component.packageName == pkg }
            }.distinctBy { it.key }
            val cells = LinkedHashMap<Cell, HomeItem>()
            dock.forEachIndexed { i, app -> cells[Cell(true, 0, i, 0)] = HomeItem.App(app.key) }
            return HomeLayout(cells, 1)
        }
    }
}
