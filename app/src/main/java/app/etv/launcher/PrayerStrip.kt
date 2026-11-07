package app.etv.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** How the prayer times look in each theme. */
enum class StripStyle {
    /** Classic, Lantern: names over times; the next prayer stands in an outlined doorway. */
    ARCH,

    /** Riad: every prayer a small whitewashed door; the next one is a blue door with brass studs. */
    RIAD,

    /** Cartouche: the next prayer in a gold cartouche. */
    CARTOUCHE,

    /** Doorway: a quiet list in the corner, one prayer a line. */
    LIST,
}

/** One prayer on the home screen. */
class PrayerCell(
    val name: String,
    val time: String,
    /** Under the next prayer: how long until it. */
    val note: String,
    val next: Boolean,
    /** Already prayed today. */
    val past: Boolean,
    /** Sunrise: shown quieter (it has no athan). */
    val quiet: Boolean,
)

/**
 * Today's prayer times on the home screen. Nothing in it can be selected: it's there to be read.
 * Sizes are fixed, so the once-a-minute update never moves anything else on the screen.
 */
@SuppressLint("ViewConstructor")
class PrayerStripView(context: Context, private val style: StripStyle) : LinearLayout(context) {

    private var pal: Palette = Look.palettes[0]
    private var accent = Color.WHITE
    private val message = TextView(context)
    private var shown: List<PrayerCell> = emptyList()

    private class Holder(val box: View, val name: TextView, val time: TextView, val note: TextView, val shape: ArchDrawable?)

    private val holders = ArrayList<Holder>()

    init {
        orientation = if (style == StripStyle.LIST) VERTICAL else HORIZONTAL
        gravity = if (style == StripStyle.LIST) Gravity.START else Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        message.typeface = Fonts.body(context, 300)
        message.sp(11.5f)
        message.visibility = View.GONE
    }

    fun applyTheme(pal: Palette, accent: Int) {
        this.pal = pal
        this.accent = accent
        message.setTextColor(pal.muted)
    }

    /** A single quiet line instead of the times (no city chosen yet). */
    fun showMessage(text: String) {
        if (message.parent == null) {
            removeAllViews()
            holders.clear()
            shown = emptyList()
            addView(message, LayoutParams(-2, -2))
        }
        message.visibility = View.VISIBLE
        if (message.text?.toString() != text) message.text = text
    }

    fun show(cells: List<PrayerCell>) {
        if (message.parent != null) {
            removeView(message)
        }
        if (holders.size != cells.size) build(cells.size)
        for ((i, c) in cells.withIndex()) apply(holders[i], c, shown.getOrNull(i))
        shown = cells
    }

    private fun build(n: Int) {
        removeAllViews()
        holders.clear()
        val d = resources.displayMetrics.density
        for (i in 0 until n) {
            val name = TextView(context).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val time = TextView(context).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                maxLines = 1
            }
            val note = TextView(context).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                maxLines = 1
            }
            if (style == StripStyle.LIST) {
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dpi(12f), 0, context.dpi(12f), 0)
                }
                name.typeface = Fonts.display(context, 500)
                name.sp(14f)
                name.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                time.typeface = Fonts.body(context, 400)
                time.sp(13.5f)
                time.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                note.typeface = Fonts.body(context, 400)
                note.sp(10.5f)
                note.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                row.addView(name, LayoutParams(0, -2, 1f))
                row.addView(note, LayoutParams(-2, -2).apply { marginEnd = context.dpi(8f) })
                row.addView(time, LayoutParams(context.dpi(50f), -2))
                addView(row, LayoutParams(context.dpi(196f), context.dpi(27f)).apply { if (i > 0) topMargin = context.dpi(2f) })
                holders.add(Holder(row, name, time, note, null))
            } else {
                val shape = ArchDrawable(d).apply {
                    this.shape = if (style == StripStyle.CARTOUCHE) TileShape.CARTOUCHE else TileShape.ARCH
                }
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = shape
                    setPadding(0, context.dpi(if (style == StripStyle.CARTOUCHE) 15f else 19f), 0, 0)
                }
                name.typeface = Fonts.display(context, 500)
                name.sp(12.5f)
                time.typeface = Fonts.body(context, 500)
                time.sp(14f)
                note.typeface = Fonts.body(context, 400)
                note.sp(9.5f)
                cell.addView(name, LayoutParams(-1, -2))
                cell.addView(time, LayoutParams(-1, -2).apply { topMargin = context.dpi(4f) })
                cell.addView(note, LayoutParams(-1, -2).apply { topMargin = context.dpi(3f) })
                val w = if (style == StripStyle.RIAD) 62f else 60f
                val h = if (style == StripStyle.RIAD) 62f else 68f
                addView(cell, LayoutParams(context.dpi(w), context.dpi(h)).apply {
                    if (i > 0) marginStart = context.dpi(if (style == StripStyle.RIAD) 8f else 4f)
                })
                holders.add(Holder(cell, name, time, note, shape))
            }
        }
        shown = emptyList()
    }

    private fun apply(h: Holder, c: PrayerCell, before: PrayerCell?) {
        setText(h.name, c.name)
        setText(h.time, c.time)
        setText(h.note, c.note)
        h.note.visibility = if (c.note.isEmpty()) View.INVISIBLE else View.VISIBLE
        // Colours only change when the state does.
        if (before != null && before.next == c.next && before.past == c.past && before.quiet == c.quiet) return

        val d = resources.displayMetrics.density
        val dim = if (c.past || c.quiet) 0.55f else 1f
        when (style) {
            StripStyle.LIST -> {
                h.box.background = if (c.next) GradientDrawable().apply {
                    cornerRadius = 14f * d
                    setColor(Look.withAlpha(accent, 30))
                    setStroke(context.dpi(1f), accent)
                } else null
                h.name.setTextColor(if (c.next) accent else pal.label)
                h.time.setTextColor(if (c.next) pal.text else pal.label)
                h.note.setTextColor(accent)
            }
            StripStyle.RIAD -> {
                val s = h.shape!!
                s.strokeWidth = d
                if (c.next) {
                    s.fill = pal.tile
                    s.stroke = pal.tile
                    s.inner = pal.tileInner
                    s.studs = pal.studs
                    h.name.setTextColor(pal.tileLabel)
                    h.time.setTextColor(pal.tileText)
                    h.note.setTextColor(pal.studs)
                } else {
                    s.fill = pal.card
                    s.stroke = pal.tileStroke
                    s.inner = 0
                    s.studs = 0
                    h.name.setTextColor(pal.muted)
                    h.time.setTextColor(pal.text)
                }
                s.invalidateSelf()
            }
            else -> {
                val s = h.shape!!
                s.inner = 0
                s.studs = 0
                if (c.next) {
                    s.fill = Look.withAlpha(accent, 28)
                    s.stroke = accent
                    s.strokeWidth = 1.2f * d
                } else {
                    s.fill = Color.TRANSPARENT
                    // Cartouche: every prayer in a faint cartouche; Classic and Lantern: just the text.
                    s.stroke = if (style == StripStyle.CARTOUCHE) pal.dot else 0
                    s.strokeWidth = if (style == StripStyle.CARTOUCHE) 0.8f * d else 0f
                }
                h.name.setTextColor(if (c.next) accent else pal.muted)
                h.time.setTextColor(if (c.next) pal.text else pal.label)
                h.note.setTextColor(accent)
                s.invalidateSelf()
            }
        }
        h.name.alpha = dim
        h.time.alpha = dim
    }

    private fun setText(v: TextView, s: String) {
        if (v.text?.toString() != s) v.text = s
    }
}
