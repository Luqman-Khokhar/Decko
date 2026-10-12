package com.luqman.decko

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Now playing, on a glass card: artwork, title, artist and previous / play-pause / next. Media sessions are
 * only visible to an app with notification access, so the card stays hidden until the user grants it.
 * With [kit] it's the control center's tile instead: artwork and title over the controls, and it stays up
 * when nothing plays, saying so (or asking for access), with [onLeave] called before it opens anything.
 */
class MediaCard(private val context: Context, private val kit: CcKit? = null, private val onLeave: () -> Unit = {}) {
    private val density = context.resources.displayMetrics.density
    private val ink = context.getColor(R.color.glass_text)
    private val muted = context.getColor(R.color.glass_text_muted)
    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(muted) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = TextView(context).apply {
        textSize = 16f
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = TextView(context).apply {
        textSize = 14f
        setTextColor(muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val play = button(R.drawable.ic_media_play, R.string.media_play) { controller?.let { if (isPlaying(it)) it.transportControls.pause() else it.transportControls.play() } }
    private val prev = button(R.drawable.ic_media_prev, R.string.media_previous) { controller?.transportControls?.skipToPrevious() }
    private val next = button(R.drawable.ic_media_next, R.string.media_next) { controller?.transportControls?.skipToNext() }
    private val badge = ImageView(context).apply {
        clipToOutline = true
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    val view: View = if (kit != null) tile(kit) else LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(context, 26f, sampleBackdrop = false)
        val p = (14 * density).toInt()
        setPadding(p, p, p, p)
        visibility = View.GONE
        isClickable = true
        val artPx = (60 * density).toInt()
        addView(art, LinearLayout.LayoutParams(artPx, artPx))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(artist)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * density).toInt() })
        addView(prev)
        addView(play)
        addView(next)
    }

    private fun tile(kit: CcKit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.pane(26f, R.color.cc_glow_warm)
        setPadding(kit.dp(12), kit.dp(12), kit.dp(10), kit.dp(10))
        art.background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(context.getColor(R.color.cc_bubble)) }
        title.textSize = 15f
        artist.textSize = 13f
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                addView(art, FrameLayout.LayoutParams(-1, -1))
                addView(badge, FrameLayout.LayoutParams(kit.dp(18), kit.dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, kit.dp(4), kit.dp(4)) })
            }, LinearLayout.LayoutParams(kit.dp(56), kit.dp(56)))
            addView(kit.column(title, artist), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10); marginEnd = kit.dp(2) })
            addView(kit.chevron(), LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)))
        })
        play.background = kit.bubble(null)
        val s = kit.dp(44)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(prev, LinearLayout.LayoutParams(s, s))
            addView(play, LinearLayout.LayoutParams(kit.dp(50), kit.dp(50)).apply { marginStart = kit.dp(10); marginEnd = kit.dp(10) })
            addView(next, LinearLayout.LayoutParams(s, s))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) })
        kit.pressable(this) { open() }
    }

    /** The player's own screen; with nothing playing, the music app, or Android's access screen if needed. */
    private fun open() {
        val c = controller
        onLeave()
        when {
            !NotificationHub.isGranted(context) -> NotificationHub.requestAccess(context)
            c?.sessionActivity != null -> runCatching { c.sessionActivity?.send() }
            else -> runCatching {
                context.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = bind()
        override fun onMetadataChanged(metadata: MediaMetadata?) = bind()
        override fun onSessionDestroyed() = pick()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    fun start() {
        if (!NotificationHub.isGranted(context)) return bind()
        runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .addOnActiveSessionsChangedListener(sessionsChanged, NotificationHub.component(context), main)
        }
        pick()
    }

    fun stop() {
        runCatching { context.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The playing session, or the most recent one. */
    private fun pick() {
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(NotificationHub.component(context))
        }.getOrDefault(emptyList())
        val next = sessions.firstOrNull(::isPlaying) ?: sessions.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        bind()
    }

    private fun bind() {
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) {
            if (kit != null) bindEmpty() else view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        title.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        artist.text = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty()
        val cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (kit != null) {
            if (cover != null) showCover(cover) else showPlaceholder()
            badge.setImageDrawable(runCatching { context.packageManager.getApplicationIcon(c.packageName) }.getOrNull())
            badge.visibility = if (badge.drawable != null) View.VISIBLE else View.GONE
            setControls(true)
            view.contentDescription = context.getString(R.string.media_open) + ", " + title.text + ", " + artist.text
        } else {
            art.setImageBitmap(cover)
        }
        val playing = isPlaying(c)
        play.setImageResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        play.contentDescription = context.getString(if (playing) R.string.media_pause else R.string.media_play)
        if (kit == null) view.setOnClickListener { c.sessionActivity?.let { runCatching { it.send() } } }
    }

    /** Tile with no session: says why, and the controls rest. */
    private fun bindEmpty() {
        title.setText(R.string.media_not_playing)
        artist.setText(if (NotificationHub.isGranted(context)) R.string.media_not_playing_sub else R.string.media_need_access)
        artist.maxLines = 2
        showPlaceholder()
        badge.visibility = View.GONE
        play.setImageResource(R.drawable.ic_media_play)
        setControls(false)
        view.contentDescription = title.text.toString() + ", " + artist.text
    }

    private fun showCover(cover: android.graphics.Bitmap) {
        artist.maxLines = 1
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.setPadding(0, 0, 0, 0)
        art.clearColorFilter()
        art.setImageBitmap(cover)
    }

    private fun showPlaceholder() {
        art.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (16 * density).toInt()
        art.setPadding(p, p, p, p)
        art.setColorFilter(muted)
        art.setImageResource(R.drawable.ic_cc_music)
    }

    private fun setControls(on: Boolean) {
        for (b in listOf(prev, play, next)) {
            b.isEnabled = on
            b.alpha = if (on) 1f else DISABLED_ALPHA
        }
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }

    private fun button(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(ink)
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }
        contentDescription = context.getString(label)
        val s = (44 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener { onClick() }
    }
}
