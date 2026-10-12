package com.luqman.decko

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
    private lateinit var style: HomeStyle

    /** Style and light/dark choice this activity was built with; a change in the Home tab rebuilds it. */
    private var styleKey = ""

    /** Widget id between binding/configuring and placing, kept across process death. */
    private var pendingWidgetId = -1

    /** A pinned widget that arrived before the layout finished loading. */
    private var placeWhenLoaded = -1
    private var resizeFrame: WidgetResizeFrame? = null
    private lateinit var menuAnchor: View
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
                    .forEach(Widgets.host(this@LauncherActivity)::deleteAppWidgetId)
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

    /** Glass with a fixed Light or Dark choice overrides the system's night mode for this activity only. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val mode = Prefs.glassMode(newBase)
        if (Prefs.homeStyle(newBase) != Prefs.STYLE_GLASS || mode == Prefs.MODE_AUTO) return
        applyOverrideConfiguration(Configuration().apply {
            val night = if (mode == Prefs.MODE_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        style = HomeStyle.of(this)
        styleKey = currentStyleKey()
        if (style === GlassStyle) setTheme(R.style.Theme_Decko_Launcher_Glass)
        super.onCreate(savedInstanceState)
        pendingIconKey = savedInstanceState?.getString(STATE_ICON_KEY)
        pendingWidgetId = savedInstanceState?.getInt(STATE_WIDGET_ID, -1) ?: -1
        setContentView(R.layout.activity_launcher)
        root = findViewById(R.id.root)
        search = findViewById(R.id.search)
        grid = findViewById(R.id.apps)
        state = findViewById(R.id.drawer_state)
        launcherApps = getSystemService(LauncherApps::class.java)

        adapter = AppAdapter(this, style)
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
                    is HomeItem.Widget -> HomeDrag.DragInfo(item, cell, view, getString(R.string.menu_widgets))
                }
                info?.let(::longPress)
            },
            style = style,
            onPageLongPress = ::showHomeMenu,
        )
        folder = FolderPanel(
            this, findViewById(R.id.folder_overlay), home, ::launch,
            onLongPress = { app, view, cell -> longPress(HomeDrag.DragInfo(HomeItem.App(app.key), cell, view, app.label, app, fromFolder = true)) },
            onRenamed = ::saveHome,
            style = style,
        )
        drag = HomeDrag(
            root, home, findViewById(R.id.remove_bar),
            // Editable text accepts any drop; text fields stay closed to drops while an icon is dragged.
            onActive = { search.isCursorVisible = !it; folder.name.isCursorVisible = !it },
            onMoveBegan = { folder.close() },
            onHold = ::onHold,
            onChanged = ::saveHome,
            onWidgetRemoved = { Widgets.host(this).deleteAppWidgetId(it) },
        )
        root.setOnDragListener(drag)
        menuAnchor = View(this).also { root.addView(it, android.widget.FrameLayout.LayoutParams(1, 1)) }
        Widgets.host(this).onProvidersChanged = { home.bind() }
        if (intent?.action == ACTION_PLACE_WIDGET) placeWhenLoaded = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        style.dress(this, root)
        root.springs = style.springs
        var lastProgress = 0f
        root.onProgress = { p ->
            if (lastProgress == 0f && p > 0f) style.revealDrawer(grid)
            lastProgress = p
            style.onDrawerProgress(this, p)
        }
        if (style === GlassStyle) {
            GlassBackdrop.onChanged = {
                GlassStyle.matchWallpaper(this, root)
                home.bind()
            }
        }
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
        if (styleKey != currentStyleKey()) {
            recreate()
            return
        }
        if (style === GlassStyle) GlassBackdrop.refresh(this)
        // Pack, clock design or overrides may have changed in the Home tab; cache keys pick that up on rebind.
        refreshIcons()
    }

    override fun onStart() {
        super.onStart()
        // Widgets only update a host that's listening; startListening also refreshes ones that changed meanwhile.
        runCatching { Widgets.host(this).startListening() }
    }

    override fun onStop() {
        runCatching { Widgets.host(this).stopListening() }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_PLACE_WIDGET) {
            placeWidget(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            return
        }
        // Home pressed: animate back to the home page if we're on screen, otherwise just be there when the app closes.
        // Already on the home pages, it returns to the first one, like other launchers.
        if (Intent.ACTION_MAIN != intent.action) return
        val onScreen = hasWindowFocus()
        if (onScreen && !root.isDrawerOpen && !folder.isOpen) home.pager.snapTo(0)
        folder.close(animate = onScreen)
        root.close(animate = onScreen)
    }

    override fun onRestart() {
        super.onRestart()
        // Back from an app: home settles into place rather than just appearing.
        if (!root.isDrawerOpen && !folder.isOpen) style.revealHome(findViewById(R.id.pager), findViewById(R.id.dock))
    }

    /** configChanges keeps us alive through night-mode switches; Glass following the system rebuilds to restyle. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (styleKey != currentStyleKey()) recreate()
    }

    private fun currentStyleKey(): String {
        val glass = Prefs.homeStyle(this) == Prefs.STYLE_GLASS
        val mode = Prefs.glassMode(this)
        val night = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (!glass) "classic" else if (mode == Prefs.MODE_AUTO) "glass|auto|$night" else "glass|$mode"
    }

    override fun onDestroy() {
        if (GlassBackdrop.onChanged != null && style === GlassStyle) GlassBackdrop.onChanged = null
        launcherApps.unregisterCallback(appsChanged)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ICON_KEY, pendingIconKey)
        outState.putInt(STATE_WIDGET_ID, pendingWidgetId)
    }

    @SuppressLint("GestureBackNavigation") // Only reached below Android 13; newer versions use the callback registered in onCreate.
    override fun onBackPressed() = handleBack()

    /** Back never leaves the home screen, but a preview opened from the Home tab can close. */
    private fun handleBack() {
        if (resizeFrame != null) resizeFrame?.dismiss() else if (folder.isOpen) folder.close() else if (root.isDrawerOpen) root.close() else if (!HomeSetup.isDefaultHome(this)) finish()
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
                if (result.isSuccess) {
                    val first = home.layout == null
                    home.setData(home.layout ?: HomeLayout.load(this, all), all)
                    if (first) releaseOrphanWidgets()
                    if (placeWhenLoaded != -1) placeWidget(placeWhenLoaded).also { placeWhenLoaded = -1 }
                }
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
            // A widget's own views may reuse our R.id values, so widgets are dragged whole.
            val shadow = if (info.item is HomeItem.Widget) info.anchor else info.anchor.findViewById(R.id.icon) ?: info.anchor
            drag.start(info, shadow)
        }
    }

    private fun onHold(info: HomeDrag.DragInfo) {
        val app = info.app
        val cell = info.from
        if (app != null) {
            showAppMenu(app, info.anchor, cell, info.fromFolder)
        } else if (cell != null && info.item is HomeItem.Folder) {
            folder.open(cell, info.item, info.anchor)
        } else if (cell != null && info.item is HomeItem.Widget) {
            showWidgetMenu(info.item, info.anchor, cell)
        }
    }

    /** Long-press on empty home space: widgets, wallpaper and home settings. */
    private fun showHomeMenu(screenX: Float, screenY: Float) {
        val rootPos = IntArray(2).also { root.getLocationOnScreen(it) }
        menuAnchor.x = screenX - rootPos[0]
        menuAnchor.y = screenY - rootPos[1]
        PopupMenu(this, menuAnchor).apply {
            menu.add(0, MENU_WIDGETS, 0, R.string.menu_widgets)
            menu.add(0, MENU_WALLPAPER, 1, R.string.menu_wallpaper)
            menu.add(0, MENU_SETTINGS, 2, R.string.menu_home_settings)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_WIDGETS -> {
                        val (cw, ch) = home.cellSize()
                        startActivityForResult(Intent(this@LauncherActivity, WidgetPickerActivity::class.java)
                            .putExtra(WidgetPickerActivity.EXTRA_CELL_W, cw).putExtra(WidgetPickerActivity.EXTRA_CELL_H, ch), REQ_PICK_WIDGET)
                    }
                    MENU_WALLPAPER -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.menu_wallpaper)))
                    MENU_SETTINGS -> startActivity(Intent(this@LauncherActivity, HomeSettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun showWidgetMenu(widget: HomeItem.Widget, anchor: View, cell: HomeLayout.Cell) {
        val info = Widgets.manager(this).getAppWidgetInfo(widget.id)
        PopupMenu(this, anchor).apply {
            if (info != null && info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) menu.add(0, MENU_RESIZE, 0, R.string.resize)
            if (info != null && Build.VERSION.SDK_INT >= 28 && info.configure != null &&
                info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0) {
                menu.add(0, MENU_WIDGET_SETTINGS, 1, R.string.widget_settings)
            }
            menu.add(0, MENU_REMOVE, 2, R.string.remove_from_home)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_RESIZE -> info?.let { i -> startResize(widget, cell, i) }
                    MENU_WIDGET_SETTINGS -> runCatching {
                        Widgets.host(this@LauncherActivity).startAppWidgetConfigureActivityForResult(this@LauncherActivity, widget.id, 0, REQ_RECONFIGURE, null)
                    }
                    MENU_REMOVE -> home.layout?.let { l ->
                        l.remove(cell)
                        l.trimPages()
                        Widgets.host(this@LauncherActivity).deleteAppWidgetId(widget.id)
                        saveHome()
                    }
                }
                true
            }
            show()
        }
    }

    private fun startResize(widget: HomeItem.Widget, start: HomeLayout.Cell, info: AppWidgetProviderInfo) {
        val view = home.widget(widget.id) ?: return
        val page = home.page(start.page)
        var anchor = start
        var current = widget
        val frame = WidgetResizeFrame(this, onCommit = { x, y, sx, sy ->
            val layout = home.layout
            val to = HomeLayout.Cell(false, anchor.page, x, y)
            if (layout == null || !layout.fits(to, sx, sy, ignoring = anchor)) return@WidgetResizeFrame false
            val resized = current.copy(spanX = sx, spanY = sy)
            layout.move(anchor, resized, to)
            anchor = to
            current = resized
            saveHome()
            true
        }, onDone = { resizeFrame = null })
        resizeFrame = frame
        frame.show(root, view, page, start.x, start.y, widget.spanX, widget.spanY, info, Widgets.resizeLimits(info, page.cellW, page.cellH))
    }

    /** From the picker: bind (asking once for permission if needed), configure if the widget wants it, then place. */
    private fun addWidget(provider: ComponentName, profile: UserHandle) {
        val id = Widgets.host(this).allocateAppWidgetId()
        pendingWidgetId = id
        if (Widgets.manager(this).bindAppWidgetIdIfAllowed(id, profile, provider, null)) {
            configureOrPlace(id)
        } else {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile), REQ_BIND_WIDGET)
        }
    }

    private fun configureOrPlace(id: Int) {
        val info = Widgets.manager(this).getAppWidgetInfo(id) ?: return dropPending(id)
        if (!Widgets.needsConfigure(info)) return placeWidget(id)
        try {
            Widgets.host(this).startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE_WIDGET, null)
        } catch (e: Exception) {
            // A broken or missing settings screen shouldn't lose the widget.
            placeWidget(id)
        }
    }

    private fun dropPending(id: Int) {
        Widgets.host(this).deleteAppWidgetId(id)
        pendingWidgetId = -1
    }

    /** Ids the host holds but no home cell uses (a cancelled add, a crash mid-bind) are given back. */
    private fun releaseOrphanWidgets() {
        val used = home.layout?.items?.values?.filterIsInstance<HomeItem.Widget>()?.map { it.id }?.toSet() ?: return
        val host = Widgets.host(this)
        host.appWidgetIds.filter { it !in used && it != pendingWidgetId && it != placeWhenLoaded }.forEach(host::deleteAppWidgetId)
    }

    /** Puts bound widget [id] on the page in view, or the first page with room. */
    private fun placeWidget(id: Int) {
        pendingWidgetId = -1
        if (id == -1) return
        val layout = home.layout ?: run { placeWhenLoaded = id; return }
        val info = Widgets.manager(this).getAppWidgetInfo(id) ?: return
        val (cw, ch) = home.cellSize()
        val (sx, sy) = Widgets.spanFor(info, cw, ch)
        val cell = layout.firstFree(sx, sy, fromPage = home.pager.currentPage)
        layout.move(null, HomeItem.Widget(id, info.provider.flattenToShortString(), sx, sy), cell)
        folder.close(animate = false)
        root.close(animate = false)
        saveHome()
        home.pager.snapTo(cell.page)
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
        when (requestCode) {
            REQ_PICK_WIDGET -> {
                if (resultCode != RESULT_OK || data == null) return
                @Suppress("DEPRECATION")
                val provider = data.getParcelableExtra<ComponentName>(WidgetPickerActivity.EXTRA_PROVIDER) ?: return
                @Suppress("DEPRECATION")
                val profile = data.getParcelableExtra<UserHandle>(WidgetPickerActivity.EXTRA_PROFILE) ?: android.os.Process.myUserHandle()
                return addWidget(provider, profile)
            }
            REQ_BIND_WIDGET -> return if (resultCode == RESULT_OK) configureOrPlace(pendingWidgetId) else dropPending(pendingWidgetId)
            REQ_CONFIGURE_WIDGET -> return if (resultCode == RESULT_OK) placeWidget(pendingWidgetId) else dropPending(pendingWidgetId)
            REQ_RECONFIGURE -> return
        }
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

    companion object {
        /** From [PinConfirmActivity]: place an accepted, already-bound widget. */
        const val ACTION_PLACE_WIDGET = "com.luqman.decko.action.PLACE_WIDGET"

        private const val REQ_ICON_IMAGE = 1
        private const val REQ_PICK_WIDGET = 2
        private const val REQ_BIND_WIDGET = 3
        private const val REQ_CONFIGURE_WIDGET = 4
        private const val REQ_RECONFIGURE = 5
        private const val STATE_ICON_KEY = "icon_key"
        private const val STATE_WIDGET_ID = "widget_id"
        private const val MENU_WIDGETS = 10
        private const val MENU_WALLPAPER = 11
        private const val MENU_SETTINGS = 12
        private const val MENU_RESIZE = 13
        private const val MENU_WIDGET_SETTINGS = 14
        private const val MENU_ICON = 1
        private const val MENU_INFO = 2
        private const val MENU_UNINSTALL = 3
        private const val MENU_ADD = 4
        private const val MENU_REMOVE = 5
    }
}
