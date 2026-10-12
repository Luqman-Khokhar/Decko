package com.luqman.decko

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("bigclock", Context.MODE_PRIVATE)

    fun appTheme(ctx: Context): ClockTheme =
        ClockTheme.byId(sp(ctx).getString("app_theme", null)) ?: ClockTheme.CLASSIC

    fun setAppTheme(ctx: Context, theme: ClockTheme) {
        sp(ctx).edit().putString("app_theme", theme.id).apply()
    }

    fun digitalTime(ctx: Context) = sp(ctx).getBoolean("digital_time", false)

    fun setDigitalTime(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("digital_time", on).apply()
    }

    /** Library animated GIF/WebP id the animated wallpaper service plays. */
    fun animatedWallpaper(ctx: Context): String? = sp(ctx).getString("animated_wallpaper", null)

    fun setAnimatedWallpaper(ctx: Context, id: String) {
        sp(ctx).edit().putString("animated_wallpaper", id).apply()
    }

    /** Library video id the video wallpaper service plays. */
    fun videoWallpaper(ctx: Context): String? = sp(ctx).getString("video_wallpaper", null)

    fun setVideoWallpaper(ctx: Context, id: String) {
        sp(ctx).edit().putString("video_wallpaper", id).apply()
    }

    /** Library image id currently applied as the static "home" or "lock" wallpaper, if any. */
    fun staticApplied(ctx: Context, which: String): String? = sp(ctx).getString("static_$which", null)

    fun setStaticApplied(ctx: Context, which: String, id: String?) {
        sp(ctx).edit().apply { if (id == null) remove("static_$which") else putString("static_$which", id) }.apply()
    }

    /** Widgets with no design of their own (e.g. pinned from the app) follow the app design. */
    fun widgetTheme(ctx: Context, widgetId: Int): ClockTheme =
        ClockTheme.byId(sp(ctx).getString("widget_$widgetId", null)) ?: appTheme(ctx)

    fun hasWidgetTheme(ctx: Context, widgetId: Int) = sp(ctx).contains("widget_$widgetId")

    fun setWidgetTheme(ctx: Context, widgetId: Int, theme: ClockTheme) {
        sp(ctx).edit().putString("widget_$widgetId", theme.id).apply()
    }

    fun clearWidget(ctx: Context, widgetId: Int) {
        sp(ctx).edit().remove("widget_$widgetId").apply()
    }

    /** Design chosen in the preview screen for a widget the user is about to pin; expires so a cancelled pin can't leak. */
    fun setPendingWidgetTheme(ctx: Context, theme: ClockTheme) {
        sp(ctx).edit().putString("pending_widget", "${theme.id}|${System.currentTimeMillis()}").apply()
    }

    fun takePendingWidgetTheme(ctx: Context): ClockTheme? {
        val raw = sp(ctx).getString("pending_widget", null) ?: return null
        sp(ctx).edit().remove("pending_widget").apply()
        val (id, at) = raw.split("|").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        return if (System.currentTimeMillis() - at < 5 * 60_000) ClockTheme.byId(id) else null
    }

    class PhotoWidgetConfig(val itemId: String, val fit: Boolean, val radiusDp: Int)

    fun photoWidget(ctx: Context, widgetId: Int): PhotoWidgetConfig? {
        val p = sp(ctx).getString("photo_$widgetId", null)?.split("|") ?: return null
        if (p.size < 3) return null
        return PhotoWidgetConfig(p[0], p[1] == "fit", p[2].toIntOrNull() ?: 0)
    }

    fun setPhotoWidget(ctx: Context, widgetId: Int, config: PhotoWidgetConfig) {
        sp(ctx).edit().putString("photo_$widgetId", "${config.itemId}|${if (config.fit) "fit" else "fill"}|${config.radiusDp}").apply()
    }

    /** Flip interval (ms) the looping photo widget's layout is currently using. */
    fun loopInterval(ctx: Context, widgetId: Int) = sp(ctx).getInt("loop_$widgetId", PhotoRenderer.DEFAULT_INTERVAL)

    fun setLoopInterval(ctx: Context, widgetId: Int, ms: Int) {
        sp(ctx).edit().putInt("loop_$widgetId", ms).apply()
    }

    fun clearPhotoWidget(ctx: Context, widgetId: Int) {
        sp(ctx).edit().remove("photo_$widgetId").remove("loop_$widgetId").apply()
    }

    /** Photo widget settings chosen in the app for a widget the user is about to pin; expires so a cancelled pin can't leak. */
    fun setPendingPhotoWidget(ctx: Context, config: PhotoWidgetConfig) {
        sp(ctx).edit().putString("pending_photo", "${config.itemId}|${if (config.fit) "fit" else "fill"}|${config.radiusDp}|${System.currentTimeMillis()}").apply()
    }

    fun takePendingPhotoWidget(ctx: Context): PhotoWidgetConfig? {
        val p = sp(ctx).getString("pending_photo", null)?.split("|") ?: return null
        sp(ctx).edit().remove("pending_photo").apply()
        if (p.size < 4 || System.currentTimeMillis() - (p[3].toLongOrNull() ?: 0L) > 5 * 60_000) return null
        return PhotoWidgetConfig(p[0], p[1] == "fit", p[2].toIntOrNull() ?: 0)
    }

    /** Built-in pack Decko Home draws every app icon with. */
    fun iconPack(ctx: Context): IconPack = IconPack.byId(sp(ctx).getString("icon_pack", null)) ?: IconPack.SYSTEM

    fun setIconPack(ctx: Context, pack: IconPack) {
        sp(ctx).edit().putString("icon_pack", pack.id).apply()
    }

    /** Per-app icon choice keyed by [AppEntry.key]: "pack:<id>" or "image:<stamp>"; null follows [iconPack]. */
    fun iconOverride(ctx: Context, appKey: String): String? = sp(ctx).getString("icon_o_$appKey", null)

    fun setIconOverride(ctx: Context, appKey: String, value: String?) {
        sp(ctx).edit().apply { if (value == null) remove("icon_o_$appKey") else putString("icon_o_$appKey", value) }.apply()
    }

    fun iconOverrideCount(ctx: Context) = sp(ctx).all.keys.count { it.startsWith("icon_o_") }

    fun clearIconOverrides(ctx: Context) {
        val p = sp(ctx)
        p.edit().apply { p.all.keys.filter { it.startsWith("icon_o_") }.forEach { remove(it) } }.apply()
    }

    /** System wallpaper id right after Decko set a library image on the home screen; see [GlassBackdrop]. */
    fun homeWallpaperId(ctx: Context) = sp(ctx).getInt("home_wallpaper_id", -1)

    fun setHomeWallpaperId(ctx: Context, id: Int) {
        sp(ctx).edit().putInt("home_wallpaper_id", id).apply()
    }

    /** Decko Home look: "classic" or "glass". */
    fun homeStyle(ctx: Context): String = sp(ctx).getString("home_style", null) ?: STYLE_CLASSIC

    fun setHomeStyle(ctx: Context, style: String) {
        sp(ctx).edit().putString("home_style", style).apply()
    }

    fun observe(ctx: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) = sp(ctx).registerOnSharedPreferenceChangeListener(l)

    fun unobserve(ctx: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) = sp(ctx).unregisterOnSharedPreferenceChangeListener(l)

    /** Glass light/dark: [MODE_AUTO] follows the system. */
    fun glassMode(ctx: Context): Int = sp(ctx).getInt("glass_mode", MODE_AUTO)

    fun setGlassMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("glass_mode", mode).apply()
    }

    const val STYLE_CLASSIC = "classic"
    const val STYLE_GLASS = "glass"
    const val MODE_AUTO = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    /** How often and how recently Decko Home opened [appKey], for App Library suggestions. */
    fun recordLaunch(ctx: Context, appKey: String) {
        val p = sp(ctx)
        p.edit().putInt("launch_n_$appKey", p.getInt("launch_n_$appKey", 0) + 1)
            .putLong("launch_t_$appKey", System.currentTimeMillis()).apply()
    }

    /** Launch count and last launch time; (0, 0) if never opened from Decko Home. */
    fun launchStats(ctx: Context, appKey: String): Pair<Int, Long> =
        sp(ctx).let { it.getInt("launch_n_$appKey", 0) to it.getLong("launch_t_$appKey", 0L) }

    /** Decko Home's pages and dock as JSON; see [HomeLayout]. */
    fun homeLayout(ctx: Context): String? = sp(ctx).getString("home_layout", null)

    fun setHomeLayout(ctx: Context, json: String) {
        sp(ctx).edit().putString("home_layout", json).apply()
    }

    /** Status-bar overlay settings. */
    fun header(ctx: Context): HeaderConfig {
        val p = sp(ctx)
        return HeaderConfig(
            enabled = p.getBoolean("header_enabled", false),
            itemId = p.getString("header_item", null),
            sizeDp = p.getInt("header_size", 22),
            xPercent = p.getInt("header_x", 50),
            movement = p.getInt("header_move", HeaderConfig.MOVE_WALK),
            speed = p.getInt("header_speed", 4),
            effect = p.getInt("header_effect", HeaderConfig.EFFECT_GLOW),
            color = p.getInt("header_color", 0xFF00E5FF.toInt()),
            effectSpeed = p.getInt("header_effect_speed", 5),
            trigger = p.getInt("header_trigger", HeaderConfig.TRIGGER_ALWAYS),
            hideFullscreen = p.getBoolean("header_hide_fullscreen", true),
        )
    }

    fun editHeader(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp(ctx).edit().apply(block).apply()
    }

    /** Callers must keep a strong reference to the returned listener; SharedPreferences only holds it weakly. */
    fun onHeaderChanged(ctx: Context, callback: () -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("header_")) callback()
        }
        sp(ctx).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeHeaderListener(ctx: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        sp(ctx).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
