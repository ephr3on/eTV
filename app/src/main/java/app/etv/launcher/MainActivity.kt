package app.etv.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

/** The home screen. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var arches: ArchBackgroundView
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var dots: DotsView
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var moveHint: TextView
    private lateinit var dock: LinearLayout

    private var pal: Palette = Look.palettes[0]
    private var accent: Int = Look.accents[0].second
    private var movingPkg: String? = null
    private var lastFocusedPkg: String? = null
    private var isResumedNow = false
    private var needsRender = true

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppRepo.invalidate()
            if (isResumedNow) rebuildRow() else needsRender = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildViews()
        setContentView(root)

        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerSystemReceiver(packageReceiver, pkgFilter)
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerSystemReceiver(timeReceiver, f)
        // Settings may have changed while we were away; re-applying is cheap.
        render()
        needsRender = false
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        try { unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        if (movingPkg != null) endMove()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Pressing Home while already home brings you back to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (movingPkg != null) endMove()
        rowScroll.smoothScrollTo(0, 0)
        (row.getChildAt(0) ?: dock.getChildAt(0))?.requestFocus()
    }

    private fun registerSystemReceiver(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
    }

    // ---------------- layout ----------------

    private fun buildViews() {
        root = FrameLayout(this).apply { clipChildren = false }

        arches = ArchBackgroundView(this)
        root.addView(arches, FrameLayout.LayoutParams(-1, -1))

        // Greeting, clock, date, studs.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        greeting = TextView(this).apply {
            typeface = Fonts.display(this@MainActivity, 500)
            sp(18f)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
        }
        clock = TextView(this).apply {
            typeface = Fonts.display(this@MainActivity, 500)
            sp(58f)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        date = TextView(this).apply {
            typeface = Fonts.body(this@MainActivity, 300)
            sp(13f)
            gravity = Gravity.CENTER
        }
        dots = DotsView(this)
        header.addView(greeting, LinearLayout.LayoutParams(-2, -2))
        header.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(4f) })
        header.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f) })
        header.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(16f) })
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dpi(70f) })

        // The row of arched apps.
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            clipChildren = false
            clipToPadding = false
            setPadding(dpi(48f), dpi(24f), dpi(48f), dpi(4f))
        }
        rowScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rowScroll.addView(row, FrameLayout.LayoutParams(-2, -1))
        root.addView(rowScroll, FrameLayout.LayoutParams(-1, dpi(190f), Gravity.TOP).apply { topMargin = dpi(250f) })

        emptyHint = TextView(this).apply {
            typeface = Fonts.body(this@MainActivity, 300)
            sp(14f)
            gravity = Gravity.CENTER
            text = "Your home row is empty.\nOpen All apps and hold OK on an app to add it here."
            visibility = View.GONE
        }
        root.addView(emptyHint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dpi(320f) })

        moveHint = TextView(this).apply {
            typeface = Fonts.body(this@MainActivity, 400)
            sp(13f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(moveHint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dpi(96f) })

        // Dock.
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        root.addView(dock, FrameLayout.LayoutParams(-1, dpi(64f), Gravity.BOTTOM).apply { bottomMargin = dpi(28f) })
    }

    private fun dockButton(text: String, iconRes: Int, onClick: () -> Unit): TextView {
        val colors = focusColors(pal.bg, pal.label)
        val icon = getDrawable(iconRes)?.mutate()?.apply { setTintList(colors) }
        return TextView(this).apply {
            this.text = text
            typeface = Fonts.body(this@MainActivity, 400)
            sp(13f)
            setTextColor(colors)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dpi(9f)
            setPadding(dpi(18f), 0, dpi(20f), 0)
            background = pillBackground(this@MainActivity, 22f, pal.tileStroke, accent)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has -> v.softScale(if (has) 1.06f else 1f) }
        }
    }

    // ---------------- rendering ----------------

    private fun render() {
        pal = Look.palette(prefs)
        accent = Look.accent(prefs)

        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        root.setBackgroundColor(pal.bg)

        arches.visibility = if (prefs.arches) View.VISIBLE else View.GONE
        arches.outer = pal.frame1
        arches.innerColor = pal.frame2
        arches.invalidate()

        greeting.setTextColor(accent)
        clock.setTextColor(pal.text)
        date.setTextColor(pal.muted)
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        dots.dot = pal.dot
        dots.accent = accent
        dots.invalidate()
        emptyHint.setTextColor(pal.muted)
        moveHint.setTextColor(accent)

        dock.removeAllViews()
        val gap = dpi(14f)
        fun addDock(v: View) {
            val lp = LinearLayout.LayoutParams(-2, dpi(44f))
            if (dock.childCount > 0) lp.marginStart = gap
            dock.addView(v, lp)
        }
        addDock(dockButton("Search", R.drawable.ic_search) { openSearch() })
        addDock(dockButton("Inputs", R.drawable.ic_inputs) { showInputs() })
        addDock(dockButton("All apps", R.drawable.ic_apps) {
            startActivity(Intent(this, AppsActivity::class.java))
        })
        addDock(dockButton("Settings", R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        updateClock()
        rebuildRow()
    }

    private fun updateClock() {
        val now = Date()
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val ar = if (h in 4..11) "صباح الخير" else "مساء الخير"
        val en = when (h) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        when (prefs.greeting) {
            0 -> greeting.text = "$ar  ·  $en"
            1 -> greeting.text = ar
            2 -> greeting.text = en
        }
        greeting.visibility = if (prefs.greeting == 3) View.GONE else View.VISIBLE

        val locale = Locale.getDefault()
        clock.text = SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", locale).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", locale).format(now)
    }

    private fun tiles(): List<ArchTile> =
        (0 until row.childCount).mapNotNull { row.getChildAt(it) as? ArchTile }

    private fun rebuildRow(focusPkg: String? = lastFocusedPkg) {
        val all = AppRepo.all(this)
        val byPkg = all.associateBy { it.pkg }

        if (!prefs.hasFavourites) {
            val tv = all.filter { it.isTv }
            prefs.favourites = (if (tv.isNotEmpty()) tv else all).take(6).map { it.pkg }
        }
        val saved = prefs.favourites
        val apps = saved.mapNotNull { byPkg[it] }
        if (apps.size != saved.size) prefs.favourites = apps.map { it.pkg } // drop uninstalled apps

        row.removeAllViews()
        val scale = prefs.tileScale
        val gap = dpi(15f * scale)
        for ((i, app) in apps.withIndex()) {
            val t = ArchTile(this, app, pal, accent, scale, prefs.iconStyle, prefs.nameMode)
            val lp = LinearLayout.LayoutParams(t.tileW, t.tileH)
            if (i > 0) lp.marginStart = gap
            t.setOnClickListener { AppRepo.launch(this, app) }
            t.setOnLongClickListener { showTileOptions(app); true }
            t.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
                if (has) lastFocusedPkg = app.pkg
            }
            row.addView(t, lp)
        }
        emptyHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

        val target: View? = tiles().firstOrNull { it.app.pkg == focusPkg } ?: row.getChildAt(0) ?: dock.getChildAt(2)
        target?.post { target.requestFocus() }
    }

    // ---------------- app options & moving ----------------

    private fun showTileOptions(app: AppEntry) {
        val items = arrayOf("Open", "Move", "Remove from home", "App info")
        choose(app.label, items) { which ->
            when (which) {
                0 -> AppRepo.launch(this, app)
                1 -> startMove(app)
                2 -> {
                    prefs.favourites = prefs.favourites.filter { it != app.pkg }
                    lastFocusedPkg = null
                    rebuildRow()
                }
                3 -> AppRepo.openAppInfo(this, app.pkg)
            }
        }
    }

    private fun startMove(app: AppEntry) {
        movingPkg = app.pkg
        tiles().firstOrNull { it.app.pkg == app.pkg }?.let {
            it.moving = true
            it.requestFocus()
        }
        moveHint.text = "Moving ${app.label}   ·   Left / Right to place it, OK when done"
        moveHint.visibility = View.VISIBLE
    }

    private fun shift(delta: Int) {
        val m = movingPkg ?: return
        val list = prefs.favourites.toMutableList()
        val i = list.indexOf(m)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        Collections.swap(list, i, j)
        prefs.favourites = list
        rebuildRow(m)
        tiles().firstOrNull { it.app.pkg == m }?.moving = true
    }

    private fun endMove() {
        val m = movingPkg
        movingPkg = null
        tiles().firstOrNull { it.app.pkg == m }?.moving = false
        moveHint.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingPkg != null) {
            val code = event.keyCode
            val handled = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK
            if (!handled) return super.dispatchKeyEvent(event) // volume etc. still work
            if (event.action == KeyEvent.ACTION_DOWN) {
                val rtl = row.layoutDirection == View.LAYOUT_DIRECTION_RTL
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> shift(if (rtl) 1 else -1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> shift(if (rtl) -1 else 1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> endMove()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true // a launcher has nowhere to go back to
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---------------- dock actions ----------------

    private fun openSearch() {
        val tries = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        for (i in tries) {
            if (AppRepo.tryStart(this, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        }
        Toast.makeText(this, "No search app found", Toast.LENGTH_SHORT).show()
    }

    private fun showInputs() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val inputs: List<TvInputInfo> = try {
            tim?.tvInputList?.filter { it.isPassthroughInput }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (inputs.isEmpty()) {
            val opened = AppRepo.tryStart(this, Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
                AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!opened) Toast.makeText(this, "No inputs found on this TV", Toast.LENGTH_SHORT).show()
            return
        }
        val names = inputs.map { it.loadLabel(this).toString() }.toTypedArray()
        choose("Inputs", names) { which ->
            val uri = TvContract.buildChannelUriForPassthroughInput(inputs[which].id)
            if (!AppRepo.tryStart(this, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                Toast.makeText(this, "Couldn't switch input", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
