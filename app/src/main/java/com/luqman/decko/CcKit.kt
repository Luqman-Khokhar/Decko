package com.luqman.decko

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Building blocks for [ControlCenter]: dark glass panes, round icon bubbles, labels and the tiles made of
 * them. [ui] is the control center's own context, dark whatever Glass's mode, so every glass token here
 * resolves to its night value.
 */
class CcKit(base: Context) {
    val ui: Context = ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar).apply {
        applyOverrideConfiguration(Configuration().apply {
            uiMode = (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        })
    }
    private val density = ui.resources.displayMetrics.density
    val ink = ui.getColor(R.color.glass_text)
    val muted = ui.getColor(R.color.glass_text_muted)
    val onText = ui.getColor(R.color.cc_on_text)
    val blue = intArrayOf(ui.getColor(R.color.cc_blue), ui.getColor(R.color.cc_blue_deep))
    val indigo = intArrayOf(ui.getColor(R.color.cc_indigo), ui.getColor(R.color.cc_indigo_deep))
    val violet = intArrayOf(ui.getColor(R.color.cc_blue), ui.getColor(R.color.cc_violet_deep))

    fun dp(v: Int) = (v * density).toInt()

    /** A glass card; [glow] washes it from the left with a hue, like the light caught in the gaming card. */
    fun pane(radiusDp: Float, glow: Int = 0): Drawable {
        val glass = GlassDrawable(ui, radiusDp, sampleBackdrop = false)
        if (glow == 0) return glass
        val wash = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ui.getColor(glow), ui.getColor(R.color.cc_glow_none))).apply {
            cornerRadius = radiusDp * density
        }
        return LayerDrawable(arrayOf(glass, wash))
    }

    /** Round icon backing: a hue gradient when [colors] is given, otherwise faint glass. */
    fun bubble(colors: IntArray?, square: Boolean = false) = GradientDrawable().apply {
        if (square) cornerRadius = 14 * density else shape = GradientDrawable.OVAL
        if (colors != null) {
            orientation = GradientDrawable.Orientation.TL_BR
            setColors(colors)
        } else {
            setColor(ui.getColor(R.color.cc_bubble))
        }
        setStroke(density.toInt().coerceAtLeast(1), ui.getColor(R.color.cc_bubble_edge))
    }

    fun icon(res: Int, padDp: Int, bg: Drawable? = bubble(null)) = ImageView(ui).apply {
        setImageResource(res)
        setColorFilter(ink)
        val p = dp(padDp)
        setPadding(p, p, p, p)
        background = bg
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun text(sp: Float, color: Int = ink, medium: Boolean = false, lines: Int = 1) = TextView(ui).apply {
        textSize = sp
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A label that shrinks to fit narrow tiles instead of cutting a word. */
    fun fitText(sp: Int, minSp: Int, color: Int = ink, medium: Boolean = false) = text(sp.toFloat(), color, medium, 2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        ellipsize = null
        setAutoSizeTextTypeUniformWithConfiguration(minSp, sp, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    fun chevron() = icon(R.drawable.ic_cc_chevron, 0, null).apply { setColorFilter(muted) }

    fun column(vararg views: View) = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    /** Clickable with a ripple, focus highlight and the Glass press scale. */
    fun pressable(v: View, borderless: Boolean = false, onClick: () -> Unit) = v.apply {
        isClickable = true
        isFocusable = true
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        foreground = ui.obtainStyledAttributes(intArrayOf(attr)).run { getDrawable(0).also { recycle() } }
        GlassStyle.pressFeedback(this)
        setOnClickListener { onClick() }
    }

    /**
     * A switch-like control: its state is read fresh on every [bind]. [onColors] fills the bubble while on
     * ([always] keeps it filled, for tiles whose hue is their identity); the state is spelled out too, so
     * color is never the only signal.
     */
    inner class Tile(
        val root: View,
        private val bubbleView: ImageView,
        private val state: TextView,
        private val label: String,
        private val onColors: IntArray,
        private val always: Boolean,
        private val highlightOn: Boolean,
        private val isOn: () -> Boolean?,
        private val stateText: (Boolean?) -> String,
    ) {
        fun bind() {
            val on = isOn()
            bubbleView.background = bubble(if (on == true || always) onColors else null)
            state.text = stateText(on)
            state.setTextColor(if (on == true && highlightOn) onText else muted)
            root.contentDescription = "$label, ${state.text}"
        }
    }

    fun onOff(on: Boolean?) = ui.getString(when (on) { true -> R.string.state_on; false -> R.string.state_off; null -> R.string.state_open })

    /** Wide row: hue bubble, name over state, chevron. Internet and Bluetooth. */
    fun pill(iconRes: Int, label: Int, colors: IntArray, isOn: () -> Boolean?, stateText: (Boolean?) -> String, onTap: () -> Unit): Tile {
        val bubble = icon(iconRes, 10)
        val state = text(12f, muted)
        val root = LinearLayout(ui).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = pane(22f)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            addView(bubble, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(column(text(15f, medium = true).apply { setText(label) }, state), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(4) })
            addView(chevron(), LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        pressable(root, onClick = onTap)
        return Tile(root, bubble, state, ui.getString(label), colors, always = true, highlightOn = false, isOn, stateText)
    }

    /** Small card: bubble over a two-line name and its state. Airplane mode, Do Not Disturb and the like. */
    fun square(iconRes: Int, label: Int, isOn: () -> Boolean?, onTap: () -> Unit): Tile {
        val bubble = icon(iconRes, 10)
        val state = text(10f, muted).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val root = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pane(22f)
            setPadding(dp(4), dp(12), dp(4), dp(10))
            addView(bubble, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(fitText(12, 9, medium = true).apply { setText(label) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        pressable(root, onClick = onTap)
        return Tile(root, bubble, state, ui.getString(label), blue, always = false, highlightOn = true, isOn, ::onOff)
    }

    /** Column inside a shared card: larger bubble, name, state. Location, Hotspot and the like. */
    fun round(iconRes: Int, label: Int, isOn: () -> Boolean?, onTap: () -> Unit): Tile {
        val bubble = icon(iconRes, 13)
        val state = text(11f, muted).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val root = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(bubble, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(fitText(13, 9).apply { maxLines = 1 }.apply { setText(label) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(state, LinearLayout.LayoutParams(-1, -2))
        }
        pressable(root, borderless = true, onClick = onTap)
        return Tile(root, bubble, state, ui.getString(label), violet, always = false, highlightOn = true, isOn, ::onOff)
    }

    /** Card with a rounded-square icon over its name: launches something. */
    fun shortcut(iconRes: Int, label: Int, onTap: () -> Unit): View = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = pane(20f)
        setPadding(dp(2), dp(10), dp(2), dp(10))
        addView(icon(iconRes, 9, bubble(null, square = true)), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(fitText(11, 8).apply { maxLines = 1 }.apply { setText(label) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        contentDescription = ui.getString(label)
        pressable(this, onClick = onTap)
    }

    /** The pill switch on the gaming card: a hue track when on, a white knob sliding across. Shows state only; the card takes the tap. */
    @SuppressLint("ViewConstructor")
    inner class Toggle : View(ui) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.getColor(R.color.cc_fill_bright) }
        private val off = ui.getColor(R.color.cc_track)
        private var at = 0f
        private var anim: ValueAnimator? = null

        var checked = false
            set(v) {
                if (v == field) return
                field = v
                anim?.cancel()
                anim = ValueAnimator.ofFloat(at, if (v) 1f else 0f).setDuration(180).apply {
                    addUpdateListener { at = it.animatedValue as Float; invalidate() }
                    start()
                }
            }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            track.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, violet[0], violet[1], Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2
            track.alpha = 255
            if (at < 1f) {
                // Off color under the hue, so the hue fades in rather than snapping.
                track.shader?.let { track.shader = null; track.color = off; canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track); track.shader = it }
            }
            track.alpha = (at * 255).toInt()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
            val inset = 3 * density
            val cx = r + (width - 2 * r) * at
            canvas.drawCircle(cx, r, r - inset, knob)
        }
    }
}
