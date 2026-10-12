package com.luqman.decko

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.text.Normalizer

/**
 * Glass Home's app list. At rest: a Suggestions card (what Decko Home opens most), Recently added, and one
 * glass card per category with three apps and a mini grid of the rest. Focusing search or typing switches
 * to an A–Z list with a letter scrubber; Back returns to the cards.
 */
class AppLibrary(
    private val activity: Activity,
    container: FrameLayout,
    private val search: EditText,
    private val onLaunch: (AppEntry, View) -> Unit,
    private val onLongPress: (AppEntry, View) -> Unit,
    private val onOpenCategory: (String, List<AppEntry>, View) -> Unit,
) {
    private val root: View = activity.layoutInflater.inflate(R.layout.glass_library, container, false).also { container.addView(it, 0) }
    private val scroll: ScrollView = root.findViewById(R.id.lib_scroll)
    private val content: LinearLayout = root.findViewById(R.id.lib_content)
    private val az: View = root.findViewById(R.id.lib_az)
    private val list: ListView = root.findViewById(R.id.lib_list)
    private val scrubber: LetterScrubber = root.findViewById(R.id.lib_scrubber)
    private val bubble: TextView = root.findViewById(R.id.lib_bubble)
    private val density = activity.resources.displayMetrics.density
    private val text = activity.getColor(R.color.glass_text)
    private val muted = activity.getColor(R.color.glass_text_muted)
    private val iconPx = (AppAdapter.ICON_DP * density).toInt()
    private val rowIconPx = (ROW_ICON_DP * density).toInt()
    private val miniPx = (MINI_ICON_DP * density).toInt()
    private val adapter = AzAdapter()
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    /** True while the A–Z list is up: search focused or something typed. */
    var searchMode = false
        set(value) {
            if (field == value) return
            field = value
            swap()
        }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, view, position, _ -> (adapter.rows[position] as? Row.App)?.let { onLaunch(it.app, view) } }
        list.setOnItemLongClickListener { _, view, position, _ ->
            (adapter.rows[position] as? Row.App)?.let { onLongPress(it.app, view) }
            true
        }
        scrubber.color = muted
        scrubber.activeColor = text
        bubble.background = GlassDrawable(activity, 32f, sampleBackdrop = false)
        bubble.setTextColor(text)
        scrubber.onLetter = { letter, y ->
            if (letter == null) {
                bubble.visibility = View.GONE
            } else {
                adapter.sections[letter]?.let { list.setSelection(it) }
                bubble.text = letter.toString()
                bubble.visibility = View.VISIBLE
                bubble.translationY = (y - bubble.height / 2f).coerceIn(0f, (az.height - bubble.height).toFloat().coerceAtLeast(0f))
            }
        }
    }

    /** New app list or search text. */
    fun setApps(apps: List<AppEntry>, query: String) {
        val changed = apps !== all
        all = apps
        if (query.isNotEmpty()) searchMode = true
        shown = if (query.isEmpty()) apps else rank(apps, query)
        adapter.build(shown, sectioned = query.isEmpty())
        if (changed) buildCards()
    }

    fun firstResult(): AppEntry? = if (searchMode) shown.firstOrNull() else null

    /** Name starts with the query, then a word in it does, then it appears anywhere; ties keep A–Z order. */
    private fun rank(apps: List<AppEntry>, query: String): List<AppEntry> = apps.mapNotNull { app ->
        val label = app.label
        when {
            label.startsWith(query, ignoreCase = true) -> 0
            label.split(' ', '-', '.').any { it.startsWith(query, ignoreCase = true) } -> 1
            label.contains(query, ignoreCase = true) -> 2
            else -> null
        }?.let { app to it }
    }.sortedBy { it.second }.map { it.first }

    /** Back while searching leaves search; returns false when there's nothing to back out of. */
    fun handleBack(): Boolean {
        if (!searchMode) return false
        exitSearch()
        return true
    }

    /** True while whichever view is up (cards or A–Z list) can still scroll toward its top. */
    fun canScrollUp(): Boolean = if (searchMode) list.canScrollVertically(-1) else scroll.canScrollVertically(-1)

    /** Drawer closed: next time it opens on the cards, scrolled to the top. */
    fun reset() {
        exitSearch()
        scroll.scrollTo(0, 0)
        // Suggestions follow what was just opened; rebuilding while hidden costs nothing visible.
        buildCards()
    }

    private fun exitSearch() {
        search.text.clear()
        search.clearFocus()
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        searchMode = false
    }

    /** Cards rise in one after another as the library opens. */
    fun reveal() {
        if (searchMode) return
        val rise = 36 * density
        var delay = 0L
        for (i in 0 until content.childCount) {
            val row = content.getChildAt(i)
            val parts = if (row is GridLayout) (0 until row.childCount).map(row::getChildAt) else listOf(row)
            for (v in parts) {
                v.animate().cancel()
                v.translationY = rise
                v.alpha = 0f
                v.animate().translationY(0f).alpha(1f).setStartDelay(delay).setDuration(REVEAL_MS).setInterpolator(EMPHASIZED).start()
                delay += STAGGER_MS
            }
        }
    }

    private fun swap() {
        val (show, hide) = if (searchMode) az to scroll else scroll to az
        show.visibility = View.VISIBLE
        show.alpha = 0f
        show.translationY = 16 * density
        show.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(SWAP_MS).setInterpolator(EMPHASIZED).start()
        hide.visibility = View.GONE
        if (searchMode) list.setSelection(0)
    }

    private fun buildCards() {
        content.removeAllViews()
        val suggested = all.map { it to Prefs.launchStats(activity, it.key) }
            .filter { it.second.first > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Pair<Int, Long>>> { it.second.first }.thenByDescending { it.second.second })
            .take(ROW_APPS).map { it.first }
        if (suggested.isNotEmpty()) content.addView(rowCard(R.string.suggestions, suggested))
        val recent = all.sortedByDescending { it.info.firstInstallTime }.take(ROW_APPS)
        if (recent.isNotEmpty()) content.addView(rowCard(R.string.recently_added, recent))

        val grid = GridLayout(activity).apply { columnCount = 2 }
        all.groupBy(AppCategory::of).toSortedMap(compareBy { it.ordinal }).forEach { (category, apps) ->
            grid.addView(categoryCard(activity.getString(category.title), apps), GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                val m = (6 * density).toInt()
                setMargins(m, m, m, m * 2)
            })
        }
        content.addView(grid)
    }

    /** Full-width glass card: a title and four apps with labels. */
    private fun rowCard(title: Int, apps: List<AppEntry>): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(activity, 26f, sampleBackdrop = false)
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
        }
        card.addView(TextView(activity).apply {
            setText(title)
            setTextColor(muted)
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (app in apps) {
            row.addView(appTile(app, iconPx, labelled = true), LinearLayout.LayoutParams(0, -2, 1f))
        }
        repeat(ROW_APPS - apps.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                val m = (6 * density).toInt()
                setMargins(m, m, m, m * 2)
            }
        }
    }

    /** Category card: three apps and, for the rest, a mini grid that opens the whole category. */
    private fun categoryCard(title: String, apps: List<AppEntry>): View {
        val tile = GridLayout(activity).apply {
            columnCount = 2
            background = GlassDrawable(activity, 26f, sampleBackdrop = false)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
        }
        val open = { v: View -> onOpenCategory(title, apps, v) }
        val direct = if (apps.size <= 4) apps else apps.take(3)
        for (app in direct) tile.addView(appTile(app, iconPx, labelled = false), quadrant())
        if (apps.size > 4) {
            val mini = GridLayout(activity).apply {
                columnCount = 2
                isClickable = true
                isFocusable = true
                contentDescription = activity.getString(R.string.open_category, title)
                foreground = ripple()
                setOnClickListener(open)
            }
            for (app in apps.drop(3).take(4)) {
                val image = ImageView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                Icons.load(activity, app, miniPx, image)
                val m = (2 * density).toInt()
                mini.addView(image, GridLayout.LayoutParams().apply { width = miniPx; height = miniPx; setMargins(m, m, m, m) })
            }
            tile.addView(FrameLayout(activity).apply { addView(mini, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, quadrant())
        }
        repeat((4 - tile.childCount).coerceAtLeast(0)) { tile.addView(View(activity), quadrant()) }
        val label = TextView(activity).apply {
            this.text = title
            setTextColor(this@AppLibrary.text)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isClickable = true
            setOnClickListener { open(tile) }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tile, LinearLayout.LayoutParams(-1, -2))
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun quadrant() = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
        width = 0
        height = (QUADRANT_DP * density).toInt()
    }

    private fun appTile(app: AppEntry, size: Int, labelled: Boolean): View {
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            foreground = ripple()
            contentDescription = if (app.isWork) activity.getString(R.string.work_app, app.label) else app.label
        }
        val image = ImageView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        Icons.load(activity, app, size, image)
        image.id = R.id.icon
        tile.addView(image, LinearLayout.LayoutParams(size, size))
        if (labelled) tile.addView(TextView(activity).apply {
            this.text = app.label
            setTextColor(this@AppLibrary.text)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() })
        GlassStyle.pressFeedback(tile)
        tile.setOnClickListener { onLaunch(app, tile) }
        tile.setOnLongClickListener { onLongPress(app, tile); true }
        return tile
    }

    private fun ripple() = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }

    private sealed class Row {
        class Header(val letter: Char) : Row()
        class App(val app: AppEntry) : Row()
    }

    /** Letter headers between alphabetical app rows; [sections] maps each letter to its header row. */
    private inner class AzAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        val sections = HashMap<Char, Int>()

        /** [sectioned]: letter headers and the scrubber, for the full list; search results are ranked, not lettered. */
        fun build(apps: List<AppEntry>, sectioned: Boolean) {
            val out = ArrayList<Row>()
            sections.clear()
            for (app in apps) {
                val letter = letterOf(app.label)
                if (sectioned && letter !in sections) {
                    sections[letter] = out.size
                    out += Row.Header(letter)
                }
                out += Row.App(app)
            }
            rows = out
            scrubber.letters = sections.keys.sortedBy { sections[it] }
            scrubber.visibility = if (sectioned) View.VISIBLE else View.GONE
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Row.Header -> ((convertView ?: activity.layoutInflater.inflate(R.layout.item_library_header, parent, false)) as TextView).apply {
                text = row.letter.toString()
                setTextColor(muted)
            }
            is Row.App -> (convertView ?: activity.layoutInflater.inflate(R.layout.item_library_row, parent, false).also(GlassStyle::pressFeedback)).apply {
                findViewById<TextView>(R.id.label).apply {
                    text = row.app.label
                    setTextColor(this@AppLibrary.text)
                }
                contentDescription = if (row.app.isWork) activity.getString(R.string.work_app, row.app.label) else row.app.label
                Icons.load(activity, row.app, rowIconPx, findViewById<ImageView>(R.id.icon))
            }
        }
    }

    private companion object {
        const val ROW_APPS = 4
        const val ROW_ICON_DP = 44
        const val MINI_ICON_DP = 22
        const val QUADRANT_DP = 68
        const val REVEAL_MS = 420L
        const val STAGGER_MS = 35L
        const val SWAP_MS = 260L
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        /** Section letter: the label's first letter without accents, or # for digits and symbols. */
        fun letterOf(label: String): Char {
            val c = Normalizer.normalize(label.take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c else '#'
        }
    }
}
