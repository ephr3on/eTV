package app.etv.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * How Customise asks for a choice: a panel at the right edge over the dimmed page, in eTV's own
 * colours, instead of Android's grey dialogs. Its rows are Customise's rows; the chosen option
 * has a filled doorway. Up / Down stop at the ends, Back closes it.
 *
 * [fill] builds the rows; [refresh] builds them again after a change, keeping the selected one,
 * so a panel can stay open while you change several things (one prayer's options, say).
 */
class Sheet(
    private val act: Activity,
    val pal: Palette,
    val accent: Int,
    var title: String,
    var note: String = "",
    /** The quiet line at the foot: what the keys do here. */
    var hint: String = "OK chooses  ·  Back closes",
    private val fill: Sheet.() -> Unit,
) : Dialog(act, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    /** The panel itself (the screenshots draw it). */
    val panel: LinearLayout
    private val titleView: TextView
    private val noteView: TextView
    private val hintView: TextView
    private val scroll: ScrollView
    private val list: LinearLayout

    /** The row to select after building (an item's key); else the chosen one, else the first. */
    var focusKey: String? = null
    private var chosenKey: String? = null

    /** Called once when the panel goes, however it goes. */
    var onClose: (() -> Unit)? = null

    /** The remote's Play / Pause key (the recording panels use it). */
    var onPlayPause: (() -> Unit)? = null

    /** True while the rows are rebuilt: focus moving then isn't you choosing. */
    private var rebuilding = false

    init {
        val c = act
        val root = FrameLayout(c).apply { setBackgroundColor(Look.withAlpha(Color.BLACK, 0x8C)) }
        panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            setPadding(c.dpi(30f), c.dpi(36f), c.dpi(30f), c.dpi(22f))
        }
        // A hairline down the panel's edge.
        root.addView(View(c).apply { setBackgroundColor(pal.tileStroke) }, FrameLayout.LayoutParams(c.dpi(1f), -1, Gravity.END).apply {
            marginEnd = c.dpi(PANEL_DP)
        })
        root.addView(panel, FrameLayout.LayoutParams(c.dpi(PANEL_DP), -1, Gravity.END))

        titleView = TextView(c).apply {
            typeface = Fonts.display(c, 500)
            sp(22f)
            includeFontPadding = false
            setTextColor(pal.text)
        }
        panel.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        noteView = noteText(c, pal, "")
        panel.addView(noteView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dpi(8f) })

        scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(c.dpi(28f))
            clipToPadding = false
            setPadding(0, c.dpi(4f), 0, c.dpi(12f))
        }
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = c.dpi(14f) })

        hintView = TextView(c).apply {
            typeface = Fonts.body(c, 400)
            sp(11.5f)
            letterSpacing = 0.04f
            setTextColor(pal.muted)
        }
        panel.addView(hintView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dpi(10f) })

        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(android.R.style.Animation_Toast)
        }
        setCanceledOnTouchOutside(true)
        setOnDismissListener {
            val f = onClose
            onClose = null
            f?.invoke()
        }
        setOnShowListener { quietly { target()?.requestFocus() } }
        refresh()
    }

    // ---------------- building ----------------

    /** Builds the rows again from [fill] (after a change), keeping the selected row. */
    fun refresh() {
        quietly {
            val keep = focusKey
            chosenKey = null
            list.removeAllViews()
            // The rows first: building them can change the title or the note.
            fill()
            titleView.text = title
            noteView.text = note
            noteView.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
            hintView.text = hint
            hintView.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
            focusKey = keep
            target()?.requestFocus()
        }
    }

    private fun target(): View? {
        val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }
        return focusKey?.let { k -> rows.firstOrNull { it.tag == k } }
            ?: chosenKey?.let { k -> rows.firstOrNull { it.tag == k } }
            ?: rows.firstOrNull()
    }

    private inline fun quietly(block: () -> Unit) {
        val was = rebuilding
        rebuilding = true
        try {
            block()
        } finally {
            rebuilding = was
        }
    }

    /**
     * One row. [checked] gives it a doorway (filled when it's the current choice); [toggle] a
     * switch; [opens] a "›". [onStep] makes Left / Right change it (-1 / +1) without opening anything.
     * [onFocus] runs when you move onto it.
     */
    fun item(
        key: String,
        label: String,
        value: String = "",
        detail: String = "",
        checked: Boolean? = null,
        toggle: Boolean? = null,
        opens: Boolean = false,
        swatch: Int? = null,
        onFocus: (() -> Unit)? = null,
        onStep: ((Int) -> Unit)? = null,
        onPress: () -> Unit,
    ): View {
        val r = optionRow(context, pal, accent, label, value, detail, swatch, toggle, opens, checked)
        r.tag = key
        if (checked == true && chosenKey == null) chosenKey = key
        r.setOnClickListener {
            focusKey = key
            onPress()
        }
        r.setOnFocusChangeListener { _, has ->
            if (has && !rebuilding) {
                focusKey = key
                onFocus?.invoke()
            }
        }
        if (onStep != null) {
            r.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        focusKey = key
                        onStep(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    }
                    true
                } else {
                    false
                }
            }
        }
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dpi(4f) })
        return r
    }

    /** A small heading between rows. */
    fun group(text: String) {
        list.addView(sectionLabel(context, pal, text), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dpi(16f)
            bottomMargin = context.dpi(2f)
            marginStart = context.dpi(18f)
        })
    }

    /** Quiet text between rows. */
    fun text(text: String) {
        list.addView(noteText(context, pal, text), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dpi(8f)
            bottomMargin = context.dpi(4f)
            marginStart = context.dpi(18f)
            marginEnd = context.dpi(18f)
        })
    }

    fun row(key: String): View? = list.findViewWithTag(key)

    fun setDetail(key: String, text: String, color: Int = 0) {
        row(key)?.let { setRowDetail(it, text, pal, color) }
    }

    fun setValue(key: String, text: String) {
        row(key)?.let { setRowValue(it, text) }
    }

    // ---------------- keys ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val f = currentFocus
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                    if (f != null && f.parent === list && FocusFinder.getInstance().findNextFocus(list, f, dir) == null) return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    onPlayPause?.let {
                        it()
                        return true
                    }
                }
            }
        }
        // Left and Right go nowhere in a panel unless a row uses them (onStep, handled by the row first).
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            super.dispatchKeyEvent(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val PANEL_DP = 440f
    }
}
