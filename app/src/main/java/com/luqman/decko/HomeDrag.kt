package com.luqman.decko

import android.content.ClipData
import android.content.ClipDescription
import android.graphics.PointF
import android.view.DragEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Drag and drop on Decko Home: from the drawer to add an app, within home to move it, onto another
 * app's center to make a folder, out of an open folder, or onto the top bar to remove.
 * A long-press that never moves is handed to [onHold] to show a menu instead.
 */
class HomeDrag(
    private val root: HomeGestureLayout,
    private val home: HomeScreen,
    private val removeBar: View,
    private val onActive: (Boolean) -> Unit,
    private val onMoveBegan: () -> Unit,
    private val onHold: (DragInfo) -> Unit,
    private val onChanged: () -> Unit,
) : View.OnDragListener {

    /**
     * [from] is the home cell the item leaves (null from the drawer). With [fromFolder], [item] is one app
     * leaving the folder at [from], which stays put. [app] is set for app drags, null for a folder.
     */
    class DragInfo(
        val item: HomeItem,
        val from: HomeLayout.Cell?,
        val anchor: View,
        val label: String,
        val app: AppEntry? = null,
        val fromFolder: Boolean = false,
    ) {
        /** The cell that frees up when the item leaves it. */
        val vacates get() = from.takeIf { !fromFolder }
    }

    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop * 2f
    private val edge = EDGE_DP * root.resources.displayMetrics.density
    private var start = PointF()
    private var moved = false
    private var target: HomeLayout.Cell? = null
    private var mergeInto: HomeLayout.Cell? = null
    private var overRemove = false
    private var flipDir = 0
    private val flip = Runnable { flipPage() }
    private val tmp = IntArray(2)
    private val rootPos = IntArray(2)

    fun start(info: DragInfo, icon: View) {
        // A private mime type, so no other view (or app) mistakes the drag for text it can accept.
        val clip = ClipData(ClipDescription(info.label, arrayOf(MIME)), ClipData.Item(info.label))
        // Before starting: DRAG_STARTED reaches children before this root listener, and an editable
        // TextView accepts any drag, so text fields must already be closed to drops by then.
        onActive(true)
        if (!icon.startDragAndDrop(clip, View.DragShadowBuilder(icon), info, 0)) onActive(false)
    }

    override fun onDrag(v: View, e: DragEvent): Boolean {
        val info = e.localState as? DragInfo ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                start.set(e.x, e.y)
                moved = false
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!moved && hypot(e.x - start.x, e.y - start.y) > slop) {
                    moved = true
                    root.close()
                    onMoveBegan()
                    home.hiddenCell = info.vacates
                    if (info.from != null) removeBar.visibility = View.VISIBLE
                }
                if (moved) track(info, e.x, e.y)
            }
            DragEvent.ACTION_DRAG_EXITED -> clearTarget()
            DragEvent.ACTION_DROP -> return drop(info)
            DragEvent.ACTION_DRAG_ENDED -> finish(info, e.result)
        }
        return true
    }

    private fun track(info: DragInfo, x: Float, y: Float) {
        overRemove = info.from != null && local(removeBar, x, y) != null
        removeBar.isActivated = overRemove
        if (overRemove) return clearTarget()

        local(home.dock, x, y)?.let { p ->
            cancelFlip()
            val (cx, _) = home.dock.cellAt(p.x, p.y)
            return aim(info, HomeLayout.Cell(true, 0, cx, 0), home.dock, p)
        }
        local(home.pager, x, y)?.let { p ->
            when {
                p.x < edge -> scheduleFlip(-1)
                p.x > home.pager.width - edge -> scheduleFlip(1)
                else -> cancelFlip()
            }
            val pageIndex = home.pager.currentPage
            val page = home.page(pageIndex)
            val (cx, cy) = page.cellAt(p.x, p.y)
            return aim(info, HomeLayout.Cell(false, pageIndex, cx, cy), page, p)
        }
        cancelFlip()
        clearTarget()
    }

    /** Hovering an app or folder's center with an app means "put them together"; anywhere else lands in the nearest free cell. */
    private fun aim(info: DragInfo, cell: HomeLayout.Cell, grid: CellLayout, p: PointF) {
        val layout = home.layout ?: return
        val occupant = layout.items[cell]
        val center = grid.isNearCenter(p.x, p.y)
        val canMerge = info.item is HomeItem.App && occupant != null && cell != info.from && center
        if (info.fromFolder && cell == info.from && center) {
            // Back onto its own folder: dropping there changes nothing.
            target = null
            mergeInto = null
        } else if (canMerge) {
            target = null
            mergeInto = cell
        } else {
            target = layout.nearestFree(cell, info.vacates)
            mergeInto = null
        }
        showHints()
    }

    private fun drop(info: DragInfo): Boolean {
        val layout = home.layout ?: return false
        val from = info.from
        val merge = mergeInto
        val to = target
        when {
            overRemove && from != null ->
                if (info.fromFolder) layout.removeFromFolder(from, (info.item as HomeItem.App).key) else layout.remove(from)
            merge != null -> layout.merge(from, info.fromFolder, (info.item as HomeItem.App).key, merge)
            to != null -> {
                if (info.fromFolder && from != null) layout.removeFromFolder(from, (info.item as HomeItem.App).key)
                layout.move(info.vacates, info.item, to)
            }
            else -> return false
        }
        layout.trimPages()
        onChanged()
        return true
    }

    private fun finish(info: DragInfo, dropped: Boolean) {
        cancelFlip()
        clearTarget()
        overRemove = false
        removeBar.isActivated = false
        removeBar.visibility = View.GONE
        home.hiddenCell = null
        onActive(false)
        if (!moved) {
            onHold(info)
        } else if (!dropped) {
            // A page opened mid-drag and left empty closes again.
            home.layout?.let { l -> val before = l.pages; l.trimPages(); if (l.pages != before) home.bind() }
        }
    }

    private fun showHints() {
        fun hint(cell: HomeLayout.Cell?, dock: Boolean, page: Int) = cell?.takeIf { it.dock == dock && (dock || it.page == page) }?.let { it.x to it.y }
        home.dock.dropHint = hint(target, true, 0)
        home.dock.mergeHint = hint(mergeInto, true, 0)
        for (i in 0 until home.pager.childCount) {
            home.page(i).dropHint = hint(target, false, i)
            home.page(i).mergeHint = hint(mergeInto, false, i)
        }
    }

    private fun clearTarget() {
        target = null
        mergeInto = null
        showHints()
    }

    private fun scheduleFlip(dir: Int) {
        if (flipDir == dir) return
        cancelFlip()
        flipDir = dir
        root.postDelayed(flip, FLIP_DELAY_MS)
    }

    private fun cancelFlip() {
        root.removeCallbacks(flip)
        flipDir = 0
    }

    /** Holding at an edge turns the page; past the last page a new one opens, unless the last is already empty. */
    private fun flipPage() {
        val layout = home.layout ?: return
        val next = home.pager.currentPage + flipDir
        flipDir = 0
        if (next < 0) return
        if (next >= layout.pages) {
            if (!layout.pageHasItems(layout.pages - 1) && layout.pages > 1) return
            layout.addPage()
            home.bind()
        }
        clearTarget()
        home.pager.snapTo(next)
    }

    /** Drag point (root coordinates) inside [view]'s own coordinates, or null when outside it. */
    private fun local(view: View, x: Float, y: Float): PointF? {
        if (view.visibility != View.VISIBLE) return null
        root.getLocationOnScreen(rootPos)
        view.getLocationOnScreen(tmp)
        val lx = x + rootPos[0] - tmp[0]
        val ly = y + rootPos[1] - tmp[1]
        return if (lx >= 0 && ly >= 0 && lx < view.width && ly < view.height) PointF(lx, ly) else null
    }

    private companion object {
        const val MIME = "application/vnd.decko.app"
        const val EDGE_DP = 28
        const val FLIP_DELAY_MS = 550L
    }
}
