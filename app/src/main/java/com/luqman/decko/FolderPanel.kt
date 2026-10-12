package com.luqman.decko

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/** An open folder: its apps in a card over a scrim, grown out of the folder icon. The name is edited in place. */
class FolderPanel(
    private val activity: Activity,
    private val overlay: View,
    private val home: HomeScreen,
    private val onLaunch: (AppEntry, View) -> Unit,
    /** Long-press on an app inside the folder at the given home cell. */
    private val onLongPress: (AppEntry, View, HomeLayout.Cell) -> Unit,
    private val onRenamed: () -> Unit,
    private val style: HomeStyle,
) {
    val name: EditText = overlay.findViewById(R.id.folder_name)
    private val card: View = overlay.findViewById(R.id.folder_card)
    private val grid: GridLayout = overlay.findViewById(R.id.folder_grid)
    private val iconPx = (AppAdapter.ICON_DP * activity.resources.displayMetrics.density).toInt()
    private var folder: HomeItem.Folder? = null
    private var startScale = 1f

    /** Glass blooms the card on a spring: 0 = folded into the icon, 1 = open. */
    private val spring = Spring(SPRING_STIFFNESS, SPRING_DAMPING, onUpdate = ::applyOpen, onEnd = { if (!isOpen) hide() })

    var openCell: HomeLayout.Cell? = null
        private set
    val isOpen get() = openCell != null

    init {
        overlay.setOnClickListener { close() }
        name.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            commitName()
            hideKeyboard()
            name.clearFocus()
            true
        }
    }

    fun open(cell: HomeLayout.Cell, folder: HomeItem.Folder, from: View) {
        openCell = cell
        this.folder = folder
        name.setText(folder.name)
        fill(folder, cell)
        overlay.visibility = View.VISIBLE
        val icon = from.findViewById<View>(R.id.icon) ?: from
        // Measure first, then pivot the card on the folder icon so it grows out of it.
        card.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                val iconPos = IntArray(2).also { icon.getLocationOnScreen(it) }
                val cardPos = IntArray(2).also { card.getLocationOnScreen(it) }
                card.pivotX = iconPos[0] + icon.width / 2f - cardPos[0]
                card.pivotY = iconPos[1] + icon.height / 2f - cardPos[1]
                startScale = icon.width / card.width.coerceAtLeast(1).toFloat()
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    style.onFolderProgress(activity, card, 1f)
                    return true
                }
                if (style.springs) {
                    applyOpen(0f)
                    spring.animateTo(1f, 0f)
                    return true
                }
                card.scaleX = startScale
                card.scaleY = startScale
                card.alpha = 0f
                overlay.alpha = 0f
                card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OPEN_MS).setInterpolator(EMPHASIZED).start()
                overlay.animate().alpha(1f).setDuration(OPEN_MS).start()
                return true
            }
        })
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        commitName()
        hideKeyboard()
        name.clearFocus()
        openCell = null
        folder = null
        val done = Runnable { hide() }
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) return done.run()
        if (style.springs) return spring.animateTo(0f, spring.value)
        card.animate().scaleX(startScale).scaleY(startScale).alpha(0f).setDuration(CLOSE_MS).setInterpolator(EMPHASIZED).start()
        overlay.animate().alpha(0f).setDuration(CLOSE_MS).withEndAction(done).start()
    }

    /** Card grows from the icon's size and spot; it fades in faster than it grows so it reads early. */
    private fun applyOpen(p: Float) {
        val scale = startScale + (1 - startScale) * p
        card.scaleX = scale
        card.scaleY = scale
        card.alpha = (p * 2).coerceIn(0f, 1f)
        overlay.alpha = (p * 1.5f).coerceIn(0f, 1f)
        style.onFolderProgress(activity, card, p.coerceIn(0f, 1f))
    }

    private fun hide() {
        spring.cancel()
        overlay.visibility = View.GONE
        overlay.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        card.alpha = 1f
        style.onFolderProgress(activity, card, 0f)
    }

    private fun commitName() {
        val f = folder ?: return
        val typed = name.text.toString().trim()
        if (typed != f.name) {
            f.name = typed
            onRenamed()
        }
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(name.windowToken, 0)
    }

    private fun fill(folder: HomeItem.Folder, cell: HomeLayout.Cell) {
        grid.removeAllViews()
        val ripple = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        for (key in folder.apps) {
            val app = home.app(key) ?: continue
            val view = activity.layoutInflater.inflate(R.layout.item_app, grid, false)
            view.findViewById<TextView>(R.id.label).apply {
                text = app.label
                style.panelText(activity)?.let(::setTextColor)
            }
            style.pressFeedback(view)
            view.contentDescription = if (app.isWork) activity.getString(R.string.work_app, app.label) else app.label
            Icons.load(activity, app, iconPx, view.findViewById<ImageView>(R.id.icon))
            view.foreground = ripple.getDrawable(0)
            view.isFocusable = true
            view.setOnClickListener { onLaunch(app, view) }
            view.setOnLongClickListener { onLongPress(app, view, cell); true }
            grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        ripple.recycle()
    }

    private companion object {
        const val OPEN_MS = 280L
        const val CLOSE_MS = 200L
        const val SPRING_STIFFNESS = 420f
        const val SPRING_DAMPING = 0.72f
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
