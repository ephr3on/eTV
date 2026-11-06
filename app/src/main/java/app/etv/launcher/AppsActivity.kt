package app.etv.launcher

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/** Every installed app, as smaller arches. Hold OK on one for options. */
class AppsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var toggle: TextView
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private var showHidden = false
    private var lastPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        root = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dpi(56f), dpi(40f), dpi(56f), dpi(48f))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            text = "All apps"
            typeface = Fonts.display(this@AppsActivity, 500)
            sp(30f)
            includeFontPadding = false
        }
        subtitle = TextView(this).apply {
            typeface = Fonts.body(this@AppsActivity, 300)
            sp(12f)
        }
        titles.addView(title)
        titles.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        toggle = TextView(this).apply {
            typeface = Fonts.body(this@AppsActivity, 400)
            sp(13f)
            gravity = Gravity.CENTER
            setPadding(dpi(18f), 0, dpi(18f), 0)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                showHidden = !showHidden
                render()
            }
            setOnFocusChangeListener { v, has -> v.softScale(if (has) 1.05f else 1f) }
        }
        header.addView(toggle, LinearLayout.LayoutParams(-2, dpi(40f)))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        grid = GridLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            useDefaultMargins = false
        }
        column.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(16f) })

        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val pal = Look.palette(prefs)
        val accent = Look.accent(prefs)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        root.setBackgroundColor(pal.bg)
        title.setTextColor(pal.text)
        subtitle.setTextColor(pal.muted)

        val all = AppRepo.all(this)
        val hidden = prefs.hidden
        val favs = prefs.favourites.toSet()
        val list = if (showHidden) all else all.filter { it.pkg !in hidden }
        val hiddenCount = all.count { it.pkg in hidden }

        subtitle.text = "${list.size} apps   ·   Hold OK on an app to add it to home, hide it or remove it"
        toggle.visibility = if (hiddenCount > 0 || showHidden) View.VISIBLE else View.GONE
        toggle.text = if (showHidden) "Hide hidden apps" else "Show hidden ($hiddenCount)"
        toggle.setTextColor(focusColors(pal.bg, pal.label))
        toggle.background = pillBackground(this, 20f, pal.tileStroke, accent)

        // Slightly smaller arches than on the home row.
        val scale = prefs.tileScale * 0.86f
        val tileW = dpi(96f * scale)
        val gapX = dpi(18f * scale)
        val gapY = dpi(22f * scale)
        val usable = resources.displayMetrics.widthPixels - dpi(112f)
        val cols = max(3, (usable + gapX) / (tileW + gapX))

        grid.removeAllViews()
        grid.columnCount = cols

        var focusTarget: View? = null
        for ((i, app) in list.withIndex()) {
            val t = ArchTile(this, app, pal, accent, scale, prefs.iconStyle, 0)
            val lp = GridLayout.LayoutParams().apply {
                width = t.tileW
                height = t.tileH
                setMargins(if (i % cols == 0) 0 else gapX, gapY, 0, 0)
            }
            if (app.pkg in hidden) t.alpha = 0.45f
            t.setOnClickListener { AppRepo.launch(this, app) }
            t.setOnLongClickListener {
                showOptions(app, app.pkg in favs, app.pkg in hidden)
                true
            }
            t.onFocusChangeListener = View.OnFocusChangeListener { _, has -> if (has) lastPkg = app.pkg }
            grid.addView(t, lp)
            if (app.pkg == lastPkg) focusTarget = t
        }

        val target = focusTarget ?: grid.getChildAt(0) ?: toggle
        target.post { target.requestFocus() }
    }

    /** Left / Right stop at the ends of a row of apps instead of jumping to the button at the top. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        val f = currentFocus
        if (dir != 0 && event.action == KeyEvent.ACTION_DOWN && f != null && f.parent === grid &&
            FocusFinder.getInstance().findNextFocus(grid, f, dir) == null
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Options/Info opens the selected app's options; channel and page keys jump a screen at a time. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                val tile = currentFocus as? ArchTile ?: return super.onKeyDown(keyCode, event)
                showOptions(tile.app, tile.app.pkg in prefs.favourites, tile.app.pkg in prefs.hidden)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                scroll.pageScroll(View.FOCUS_DOWN); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                scroll.pageScroll(View.FOCUS_UP); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOptions(app: AppEntry, inHome: Boolean, isHidden: Boolean) {
        val items = arrayOf(
            "Open",
            if (inHome) "Remove from home" else "Add to home",
            if (isHidden) "Show in All apps" else "Hide from All apps",
            "App info",
            "Uninstall",
        )
        choose(app.label, items) { which ->
            when (which) {
                0 -> AppRepo.launch(this, app)
                1 -> {
                    val favs = prefs.favourites
                    prefs.favourites = if (inHome) favs.filter { it != app.pkg } else favs + app.pkg
                    render()
                }
                2 -> {
                    prefs.hidden = if (isHidden) prefs.hidden - app.pkg else prefs.hidden + app.pkg
                    if (!isHidden) prefs.favourites = prefs.favourites.filter { it != app.pkg }
                    render()
                }
                3 -> AppRepo.openAppInfo(this, app.pkg)
                4 -> AppRepo.uninstall(this, app.pkg)
            }
        }
    }
}
