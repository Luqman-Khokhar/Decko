package com.luqman.decko

import android.animation.AnimatorInflater
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.GridView
import android.widget.TextView
import kotlin.math.abs

/** How Decko Home looks and moves. The launcher engine is shared; a style dresses its views and adds motion. */
interface HomeStyle {
    /** Restyles the freshly inflated launcher. */
    fun dress(activity: Activity, root: View) {}

    /** Folder icon tile; null keeps the layout's own. */
    fun folderTile(ctx: Context): Drawable? = null

    /** Icon label color over the wallpaper, and whether it needs a shadow to read; null keeps the layout's. */
    fun homeLabel(ctx: Context): Pair<Int, Boolean>? = null

    /** Text color on drawer and folder panels; null keeps the layout's. */
    fun panelText(ctx: Context): Int? = null

    /** Drawer and folders settle on springs that keep the fling's velocity. */
    val springs: Boolean get() = false

    fun onDrawerProgress(activity: Activity, progress: Float) {}

    fun onFolderProgress(activity: Activity, card: View, progress: Float) {}

    /** [offset] is the page's distance from the centered page, -1..1 while it's visible. */
    fun transformPage(page: View, offset: Float) {}

    /** The drawer started opening: bring its first screen of apps in. */
    fun revealDrawer(grid: GridView) {}

    /** Back on home from an app. */
    fun revealHome(vararg views: View) {}

    fun pressFeedback(view: View) {}

    companion object {
        fun of(ctx: Context): HomeStyle = if (Prefs.homeStyle(ctx) == Prefs.STYLE_GLASS) GlassStyle else ClassicStyle
    }
}

/** The original look: everything as the layouts define it. */
object ClassicStyle : HomeStyle

/**
 * Glass: frosted panes over the wallpaper, light or dark, with spring settles, depth between pages,
 * a staggered drawer, folders that bloom out of their icon, and icons that give under the finger.
 */
object GlassStyle : HomeStyle {
    private const val BLUR_RADIUS_DP = 40
    private val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private var blurOn = false
    private var overPanel = false

    override val springs get() = true

    override fun dress(activity: Activity, root: View) {
        // One style object outlives activity rebuilds; a new window starts unblurred, over the wallpaper.
        blurOn = false
        overPanel = false
        val text = activity.getColor(R.color.glass_text)
        val muted = activity.getColor(R.color.glass_text_muted)
        root.findViewById<View>(R.id.drawer).background = GlassDrawable(activity, 32f, topOnly = true)
        root.findViewById<TextView>(R.id.search).apply {
            background = GlassDrawable(activity, 24f)
            setTextColor(text)
            setHintTextColor(muted)
        }
        root.findViewById<TextView>(R.id.remove_bar).apply {
            background = GlassDrawable(activity, 24f)
            setTextColor(text)
        }
        root.findViewById<View>(R.id.folder_overlay).setBackgroundColor(activity.getColor(R.color.glass_scrim))
        root.findViewById<View>(R.id.folder_card).background = GlassDrawable(activity, 32f)
        root.findViewById<TextView>(R.id.folder_name).apply {
            setTextColor(text)
            setHintTextColor(muted)
        }
        root.findViewById<TextView>(R.id.drawer_message).setTextColor(text)
        // Home: a glass search capsule replaces the "Apps" hint, and the page dots sit in a glass pill.
        root.findViewById<TextView>(R.id.open_drawer).apply {
            setText(R.string.search_capsule)
            background = GlassDrawable(activity, 22f)
            setTextColor(text)
            setShadowLayer(0f, 0f, 0f, 0)
            val icon = activity.getDrawable(R.drawable.ic_search)?.mutate()?.apply { setTint(text) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = (8 * activity.resources.displayMetrics.density).toInt()
            val p = (22 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                height = (46 * activity.resources.displayMetrics.density).toInt()
                bottomMargin = (12 * activity.resources.displayMetrics.density).toInt()
            }
        }
        root.findViewById<PageIndicator>(R.id.indicator).apply {
            hideWhenSingle = true
            background = GlassDrawable(activity, 10f)
            setColors(muted, text)
            val p = (10 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = (20 * activity.resources.displayMetrics.density).toInt()
                bottomMargin = (10 * activity.resources.displayMetrics.density).toInt()
            }
        }
        root.findViewById<View>(R.id.dock).apply {
            background = GlassDrawable(activity, 34f)
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = (16 * activity.resources.displayMetrics.density).toInt()
                marginEnd = marginStart
                bottomMargin = (12 * activity.resources.displayMetrics.density).toInt()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        matchWallpaper(activity, root)
    }

    /** Status-bar icons follow the wallpaper's brightness, which may arrive after [dress]. */
    fun matchWallpaper(activity: Activity, root: View) {
        applyBars(activity)
    }

    /** Dark bar icons over light glass or a light wallpaper, light ones otherwise. */
    private fun applyBars(activity: Activity) {
        val lightGlass = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val dark = if (overPanel) lightGlass else GlassBackdrop.prefersDarkText
        if (Build.VERSION.SDK_INT >= 30) {
            val bits = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if (dark) bits else 0, bits)
        } else {
            @Suppress("DEPRECATION")
            val decor = activity.window.decorView
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = if (dark) decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    /** Tiles move with the pages, so they use the frosted fallback rather than a backdrop that would lag behind. */
    override fun folderTile(ctx: Context): Drawable = GlassDrawable(ctx, 16f, sampleBackdrop = false)

    override fun homeLabel(ctx: Context) =
        if (GlassBackdrop.prefersDarkText) ctx.getColor(R.color.config_text) to false else ctx.getColor(R.color.controls_text) to true

    override fun panelText(ctx: Context) = ctx.getColor(R.color.glass_text)

    override fun onDrawerProgress(activity: Activity, progress: Float) {
        // The drawer and its search field slide; their glass must re-aim at the wallpaper behind them.
        activity.findViewById<View>(R.id.drawer).background?.invalidateSelf()
        activity.findViewById<View>(R.id.search).background?.invalidateSelf()
        windowBlur(activity, progress > 0f)
        // The drawer covers the status bar once mostly open.
        val covered = progress > 0.5f
        if (covered != overPanel) {
            overPanel = covered
            applyBars(activity)
        }
    }

    override fun onFolderProgress(activity: Activity, card: View, progress: Float) {
        card.background?.invalidateSelf()
        windowBlur(activity, progress > 0f)
    }

    /**
     * Real blur of everything behind the window, where the phone supports it (Android 12+, blur enabled,
     * not in battery saver). Toggled rather than ramped: changing it re-lays out the window.
     */
    private fun windowBlur(activity: Activity, on: Boolean) {
        if (Build.VERSION.SDK_INT < 31 || on == blurOn) return
        val wm = activity.getSystemService(WindowManager::class.java)
        if (on && !wm.isCrossWindowBlurEnabled) return
        blurOn = on
        val window = activity.window
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (BLUR_RADIUS_DP * activity.resources.displayMetrics.density).toInt() }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    override fun transformPage(page: View, offset: Float) {
        val a = abs(offset).coerceAtMost(1f)
        page.scaleX = 1 - 0.1f * a
        page.scaleY = page.scaleX
        page.alpha = 1 - 0.65f * a
    }

    override fun revealDrawer(grid: GridView) {
        val columns = grid.numColumns.coerceAtLeast(1)
        val rise = 28 * grid.resources.displayMetrics.density
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.translationY = rise
            child.alpha = 0f
            child.animate().translationY(0f).alpha(1f).setStartDelay(i / columns * 30L).setDuration(380).setInterpolator(EMPHASIZED).start()
        }
    }

    override fun revealHome(vararg views: View) {
        for (v in views) {
            v.animate().cancel()
            v.scaleX = 1.06f
            v.scaleY = 1.06f
            v.alpha = 0.3f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(EMPHASIZED).start()
        }
    }

    override fun pressFeedback(view: View) {
        view.stateListAnimator = AnimatorInflater.loadStateListAnimator(view.context, R.animator.press_scale)
    }
}
