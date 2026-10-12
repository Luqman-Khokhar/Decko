package com.luqman.decko

import android.app.Activity
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Now playing, on a glass card: artwork, title, artist and previous / play-pause / next. Media sessions are
 * only visible to an app with notification access, so the card stays hidden until the user grants it.
 */
class MediaCard(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    private val ink = activity.getColor(R.color.glass_text)
    private val muted = activity.getColor(R.color.glass_text_muted)
    private val art = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(muted) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = TextView(activity).apply {
        textSize = 16f
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = TextView(activity).apply {
        textSize = 14f
        setTextColor(muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val play = button(R.drawable.ic_media_play, R.string.media_play) { controller?.let { if (isPlaying(it)) it.transportControls.pause() else it.transportControls.play() } }
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(activity, 26f, sampleBackdrop = false)
        val p = (14 * density).toInt()
        setPadding(p, p, p, p)
        visibility = View.GONE
        isClickable = true
        val artPx = (60 * density).toInt()
        addView(art, LinearLayout.LayoutParams(artPx, artPx))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(artist)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * density).toInt() })
        addView(button(R.drawable.ic_media_prev, R.string.media_previous) { controller?.transportControls?.skipToPrevious() })
        addView(play)
        addView(button(R.drawable.ic_media_next, R.string.media_next) { controller?.transportControls?.skipToNext() })
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = bind()
        override fun onMetadataChanged(metadata: MediaMetadata?) = bind()
        override fun onSessionDestroyed() = pick()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    fun start() {
        if (!NotificationHub.isGranted(activity)) return
        runCatching {
            activity.getSystemService(MediaSessionManager::class.java)
                .addOnActiveSessionsChangedListener(sessionsChanged, NotificationHub.component(activity), main)
        }
        pick()
    }

    fun stop() {
        runCatching { activity.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The playing session, or the most recent one. */
    private fun pick() {
        val sessions = runCatching {
            activity.getSystemService(MediaSessionManager::class.java).getActiveSessions(NotificationHub.component(activity))
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
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        title.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        artist.text = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty()
        art.setImageBitmap(meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART))
        val playing = isPlaying(c)
        play.setImageResource(if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        play.contentDescription = activity.getString(if (playing) R.string.media_pause else R.string.media_play)
        view.setOnClickListener { c.sessionActivity?.let { runCatching { it.send() } } }
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun button(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        setColorFilter(ink)
        background = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }
        contentDescription = activity.getString(label)
        val s = (44 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener { onClick() }
    }
}
