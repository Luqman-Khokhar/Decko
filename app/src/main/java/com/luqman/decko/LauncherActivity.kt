package com.luqman.decko

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * Decko Home: pages of apps over the wallpaper with the clock on the first, a dock, and a swipe-up
 * app drawer, all drawn with the chosen icon pack. Long-press drags an app; holding still opens its menu.
 */
class LauncherActivity : Activity() {
    private lateinit var root: HomeGestureLayout
    private lateinit var home: HomeScreen
    private lateinit var drag: HomeDrag
    private lateinit var folder: FolderPanel
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var state: View
    private lateinit var adapter: AppAdapter
    private lateinit var launcherApps: LauncherApps
    private var all: List<AppEntry> = emptyList()
    private var loadFailed = false
    private var pendingIconKey: String? = null

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            home.layout?.let {
                it.removePackage(packageName, getSystemService(UserManager::class.java).getSerialNumberForUser(user))
                it.trimPages()
                it.save(this@LauncherActivity)
            }
            reload()
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIconKey = savedInstanceState?.getString(STATE_ICON_KEY)
        setContentView(R.layout.activity_launcher)
        root = findViewById(R.id.root)
        search = findViewById(R.id.search)
        grid = findViewById(R.id.apps)
        state = findViewById(R.id.drawer_state)
        launcherApps = getSystemService(LauncherApps::class.java)

        adapter = AppAdapter(this)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, view, position, _ -> launch(adapter.getItem(position), view) }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            val app = adapter.getItem(position)
            longPress(HomeDrag.DragInfo(HomeItem.App(app.key), null, view, app.label, app))
            true
        }

        home = HomeScreen(
            this, findViewById(R.id.pager), findViewById(R.id.dock), findViewById(R.id.indicator), ::launch,
            onOpenFolder = { cell, f, view -> folder.open(cell, f, view) },
            onLongPress = { item, view, cell ->
                val info = when (item) {
                    is HomeItem.App -> home.app(item.key)?.let { HomeDrag.DragInfo(item, cell, view, it.label, it) }
                    is HomeItem.Folder -> HomeDrag.DragInfo(item, cell, view, home.folderName(item))
                }
                info?.let(::longPress)
            },
        )
        folder = FolderPanel(
            this, findViewById(R.id.folder_overlay), home, ::launch,
            onLongPress = { app, view, cell -> longPress(HomeDrag.DragInfo(HomeItem.App(app.key), cell, view, app.label, app, fromFolder = true)) },
            onRenamed = ::saveHome,
        )
        drag = HomeDrag(
            root, home, findViewById(R.id.remove_bar),
            // Editable text accepts any drop; text fields stay closed to drops while an icon is dragged.
            onActive = { search.isCursorVisible = !it; folder.name.isCursorVisible = !it },
            onMoveBegan = { folder.close() },
            onHold = ::onHold,
            onChanged = ::saveHome,
        )
        root.setOnDragListener(drag)

        root.drawerCanScrollUp = { grid.canScrollVertically(-1) }
        root.onDrawerStateChanged = ::onDrawerStateChanged
        findViewById<View>(R.id.open_drawer).setOnClickListener { root.open() }
        findViewById<Button>(R.id.drawer_retry).setOnClickListener { reload() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        search.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_GO || adapter.count == 0) return@setOnEditorActionListener false
            launch(adapter.getItem(0), grid.getChildAt(-grid.firstVisiblePosition) ?: search)
            true
        }

        applyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            // Targeting 36, onBackPressed is no longer called; route back through the same handling.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        launcherApps.registerCallback(appsChanged)
        reload()
    }

    override fun onResume() {
        super.onResume()
        home.clock.clockTheme = Prefs.appTheme(this)
        // Pack, clock design or overrides may have changed in the Home tab; cache keys pick that up on rebind.
        refreshIcons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: animate back to the home page if we're on screen, otherwise just be there when the app closes.
        // Already on the home pages, it returns to the first one, like other launchers.
        if (Intent.ACTION_MAIN != intent.action) return
        val onScreen = hasWindowFocus()
        if (onScreen && !root.isDrawerOpen && !folder.isOpen) home.pager.snapTo(0)
        folder.close(animate = onScreen)
        root.close(animate = onScreen)
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsChanged)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ICON_KEY, pendingIconKey)
    }

    @SuppressLint("GestureBackNavigation") // Only reached below Android 13; newer versions use the callback registered in onCreate.
    override fun onBackPressed() = handleBack()

    /** Back never leaves the home screen, but a preview opened from the Home tab can close. */
    private fun handleBack() {
        if (folder.isOpen) folder.close() else if (root.isDrawerOpen) root.close() else if (!HomeSetup.isDefaultHome(this)) finish()
    }

    private fun onDrawerStateChanged(open: Boolean) {
        if (!open) {
            search.text.clear()
            search.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
            grid.setSelection(0)
        }
    }

    private fun reload() {
        if (all.isEmpty()) showState(loading = true)
        Apps.io.execute {
            val result = runCatching { Apps.load(this) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loadFailed = result.isFailure
                all = result.getOrDefault(all)
                if (result.isSuccess) home.setData(home.layout ?: HomeLayout.load(this, all), all)
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        val shown = if (query.isEmpty()) all else all.filter { it.label.contains(query, ignoreCase = true) }
        adapter.apps = shown
        when {
            loadFailed && all.isEmpty() -> showState(message = getString(R.string.drawer_error), retry = true)
            shown.isEmpty() && query.isNotEmpty() -> showState(message = getString(R.string.drawer_no_match, query))
            all.isEmpty() -> showState(message = getString(R.string.drawer_empty), retry = true)
            else -> state.visibility = View.GONE
        }
    }

    private fun showState(loading: Boolean = false, message: String? = null, retry: Boolean = false) {
        state.visibility = View.VISIBLE
        findViewById<ProgressBar>(R.id.drawer_progress).visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.drawer_message).apply {
            text = message
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.drawer_retry).visibility = if (retry) View.VISIBLE else View.GONE
    }

    /** Opens the app growing out of its icon. */
    private fun launch(app: AppEntry, from: View) {
        val icon = from.findViewById<View>(R.id.icon) ?: from
        val bounds = Rect().also { icon.getGlobalVisibleRect(it) }
        val options = ActivityOptions.makeScaleUpAnimation(icon, 0, 0, icon.width, icon.height).toBundle()
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, options)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshIcons() {
        adapter.notifyDataSetChanged()
        home.bind()
    }

    private fun saveHome() {
        home.layout?.save(this)
        home.bind()
    }

    /** Drag to place or move; TalkBack users can't drag, so they get what holding still gives: a menu, or the folder. */
    private fun longPress(info: HomeDrag.DragInfo) {
        if (getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
            onHold(info)
        } else {
            drag.start(info, info.anchor.findViewById(R.id.icon) ?: info.anchor)
        }
    }

    private fun onHold(info: HomeDrag.DragInfo) {
        val app = info.app
        val cell = info.from
        if (app != null) {
            showAppMenu(app, info.anchor, cell, info.fromFolder)
        } else if (cell != null && info.item is HomeItem.Folder) {
            folder.open(cell, info.item, info.anchor)
        }
    }

    /** [cell] is where the app sits on home (inside the folder there when [inFolder]), or null in the drawer. */
    private fun showAppMenu(app: AppEntry, anchor: View, cell: HomeLayout.Cell?, inFolder: Boolean = false) {
        PopupMenu(this, anchor).apply {
            if (cell == null) menu.add(0, MENU_ADD, 0, R.string.add_to_home) else menu.add(0, MENU_REMOVE, 0, R.string.remove_from_home)
            menu.add(0, MENU_ICON, 0, R.string.change_icon)
            menu.add(0, MENU_INFO, 1, R.string.app_info)
            if (!app.isSystem) menu.add(0, MENU_UNINSTALL, 2, R.string.uninstall)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_ADD -> addToHome(app)
                    MENU_REMOVE -> home.layout?.let { l ->
                        if (inFolder) {
                            l.removeFromFolder(cell!!, app.key)
                            folder.close()
                        } else {
                            l.remove(cell!!)
                        }
                        l.trimPages()
                        saveHome()
                    }
                    MENU_ICON -> IconPicker.show(this@LauncherActivity, app, onPickImage = { pickImage(app) }, onChanged = ::refreshIcons)
                    MENU_INFO -> launcherApps.startAppDetailsActivity(app.component, app.user, Rect().also { r -> anchor.getGlobalVisibleRect(r) }, null)
                    MENU_UNINSTALL -> startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))
                            .putExtra(Intent.EXTRA_USER, app.user),
                    )
                }
                true
            }
            show()
        }
    }

    private fun addToHome(app: AppEntry) {
        val layout = home.layout ?: return
        val cell = layout.firstFree()
        layout.move(null, HomeItem.App(app.key), cell)
        saveHome()
        root.close()
        home.pager.snapTo(cell.page)
        Toast.makeText(this, R.string.added_to_home, Toast.LENGTH_SHORT).show()
    }

    private fun pickImage(app: AppEntry) {
        pendingIconKey = app.key
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_ICON_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ICON_IMAGE) return
        val key = pendingIconKey ?: return
        pendingIconKey = null
        val uri = data?.data ?: return
        Apps.io.execute {
            val ok = Icons.saveCustom(this, key, uri)
            runOnUiThread {
                if (ok) refreshIcons() else Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Edge to edge: the wallpaper runs under the bars; content and the keyboard-shrunk drawer stay clear of them. */
    private fun applyInsets() {
        val home = findViewById<View>(R.id.home)
        val drawer = findViewById<View>(R.id.drawer)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (bars, ime) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let { Rect(it.left, it.top, it.right, it.bottom) } to
                    insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.stableInsetBottom) to
                    insets.systemWindowInsetBottom
            }
            home.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            drawer.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
            insets
        }
    }

    private companion object {
        const val REQ_ICON_IMAGE = 1
        const val STATE_ICON_KEY = "icon_key"
        const val MENU_ICON = 1
        const val MENU_INFO = 2
        const val MENU_UNINSTALL = 3
        const val MENU_ADD = 4
        const val MENU_REMOVE = 5
    }
}
