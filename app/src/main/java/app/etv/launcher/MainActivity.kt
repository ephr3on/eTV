package app.etv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.FocusFinder
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

/**
 * The home screen.
 *
 * How Home behaves:
 * - Coming back from another app, Home returns you to exactly where you were in eTV
 *   (the same app on the row, the same dock button, or the same app in All apps).
 * - Pressing Home again while you're already in eTV goes to the app in the middle of the row,
 *   which is also what's selected when eTV first opens.
 */
class MainActivity : EtvActivity() {

    companion object {
        /** Set when the Home button helper brought us back. */
        const val EXTRA_FROM_HOME_KEY = "app.etv.launcher.FROM_HOME_KEY"

        /** Dock button order. */
        private const val DOCK_SEARCH = 0
        private const val DOCK_INPUTS = 1
        private const val DOCK_APPS = 2
        private const val DOCK_SETTINGS = 3
    }

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
    private lateinit var nowCard: NowPlayingCard
    private lateinit var nowWatcher: NowPlayingWatcher
    private var nowPlaying: NowPlaying? = null

    private var pal: Palette = Look.palettes[0]
    private var accent: Int = Look.accents[0].second
    private var movingPkg: String? = null

    /** The app last selected on the row: Up from the dock goes back to it. */
    private var lastRowPkg: String? = null

    private var isResumedNow = false
    private var needsRender = true
    private var renderedAppsVersion = -1

    /** A view eTV is about to select itself; focus changes on the way there aren't "where you were". */
    private var pendingFocus: View? = null
    private var rebuilding = false

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    /** Apps installed, updated or removed since the last refresh (their icons may have changed). */
    private val iconChanges = HashSet<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Android TV announces package changes constantly (Google services switching their own parts on
     * and off, often every minute). Wait for a quiet moment, then only redraw the row if the apps
     * you can see actually changed.
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            if (pkg != null && intent.action != Intent.ACTION_PACKAGE_CHANGED) iconChanges.add(pkg)
            handler.removeCallbacks(refreshApps)
            handler.postDelayed(refreshApps, 1_500L)
        }
    }

    private val refreshApps = Runnable {
        val changed = AppRepo.refresh(this, iconChanges)
        iconChanges.clear()
        if (!changed) return@Runnable
        if (isResumedNow && movingPkg == null) {
            rebuildRow()
            applyFocus()
        } else {
            needsRender = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Customise and All apps change options while this screen is hidden; redraw on return.
        prefsListener = prefs.listen { needsRender = true }

        handleHome(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHome(intent)
    }

    /** Home was pressed (or eTV was opened): decide where to land. */
    private fun handleHome(intent: Intent?) {
        if (movingPkg != null) endMove()
        // On TVs that open their own home screen first (Philips), Home pressed in eTV also makes eTV
        // leave for a moment before the helper brings it back. That's "Home again", not a return.
        val bounced = intent?.getBooleanExtra(EXTRA_FROM_HOME_KEY, false) == true &&
            SystemClock.elapsedRealtime() - Nav.leftAt < 2_000L &&
            SystemClock.elapsedRealtime() - AppRepo.lastLaunchAt > 2_000L // not an app you just opened
        if (Nav.away && !bounced) {
            // Back from another app: exactly where you were.
            if (Nav.screen == Nav.SCREEN_APPS) openAllApps(Nav.appsPkg, instant = true)
        } else {
            // Already in eTV: Home goes to the app in the middle of the row.
            Nav.screen = Nav.SCREEN_HOME
            Nav.homeKey = null
        }
        Nav.away = false
        if (isResumedNow) applyFocus()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        Nav.away = false
        Nav.screen = Nav.SCREEN_HOME

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerSystemReceiver(timeReceiver, f)

        // Back after an update (or the update was installed some other way): drop the hint.
        prefs.justUpdated = false
        if (prefs.updateCode in 1..Updater.installedCode(this)) prefs.updateCode = 0L

        // Only redraw when something actually changed, so coming back is instant and nothing jumps.
        if (needsRender || renderedAppsVersion != AppRepo.version) render() else updateClock()
        // Before choosing the selection, so "where you were" can be on the card.
        if (prefs.nowPlaying) nowWatcher.start() else showNowPlaying(null)
        nowCard.resume()
        applyFocus()

        // Look for a new version on GitHub now and then; the Settings button says when there is one.
        Updater.checkIfDue(this) {
            if (isResumedNow && needsRender) {
                render()
                applyFocus()
            }
        }

        // Back home normally: nothing left to learn about the TV's own home screen.
        if (prefs.learningHome) prefs.learnUntil = 0L

        // First run: offer the Home button setup once, only if Home doesn't reach eTV yet.
        if (!prefs.setupShown) {
            prefs.setupShown = true
            if (!HomeSetup.homeHandled(this)) {
                startActivity(Intent(this, SetupActivity::class.java))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        try { unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        // Nothing to watch while an app is in front; the card catches up on return.
        nowWatcher.stop()
        nowCard.pause()
        if (movingPkg != null) endMove()
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(refreshApps)
        prefsListener?.let { prefs.unlisten(it) }
        super.onDestroy()
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
            fontFeatureSettings = "tnum" // equal-width digits, so the clock doesn't shuffle sideways
        }
        date = TextView(this).apply {
            typeface = Fonts.body(this@MainActivity, 300)
            sp(13f)
            gravity = Gravity.CENTER
        }
        dots = DotsView(this)
        // Full-width lines: when the time changes only the text redraws; nothing else moves.
        header.addView(greeting, LinearLayout.LayoutParams(-1, -2))
        header.addView(clock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        header.addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        header.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(16f) })
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dpi(70f) })

        // The row of arched apps, centred on the screen.
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

        // "Now playing": appears in the top corner when another app plays in the background.
        nowCard = NowPlayingCard(this)
        root.addView(nowCard, FrameLayout.LayoutParams(dpi(284f), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dpi(26f)
            marginEnd = dpi(28f)
        })
        nowWatcher = NowPlayingWatcher(this) { np -> showNowPlaying(np) }
        nowCard.onOpen = { nowPlaying?.let { nowWatcher.open(it) } }
        nowCard.onPlayPause = { nowPlaying?.let { nowWatcher.playPause(it) } }
        nowCard.onPrev = { nowPlaying?.let { nowWatcher.previous(it) } }
        nowCard.onNext = { nowPlaying?.let { nowWatcher.next(it) } }
        nowCard.onFocused = { v, key -> remember(v, key) }
    }

    private fun dockButton(index: Int, text: String, iconRes: Int, onClick: () -> Unit): TextView {
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
            setOnFocusChangeListener { v, has ->
                v.softScale(if (has) 1.06f else 1f)
                if (has) remember(v, "dock:$index")
            }
        }
    }

    // ---------------- where you are ----------------

    /** Records the selected item as "where you were", unless eTV is moving the selection itself. */
    private fun remember(v: View, key: String) {
        if (rebuilding) return
        val p = pendingFocus
        if (p != null && p !== v) return
        pendingFocus = null
        Nav.homeKey = key
        if (key.startsWith("app:")) lastRowPkg = key.substring(4)
    }

    private fun viewFor(key: String?): View? = when {
        key == null -> null
        key.startsWith("app:") -> tiles().firstOrNull { it.app.pkg == key.substring(4) }
        key.startsWith("dock:") -> dock.getChildAt(key.substring(5).toIntOrNull() ?: -1)
        key.startsWith("media:") -> nowCard.viewFor(key)
        else -> null
    }

    /** The row app to return to from the dock or the card. */
    private fun rowTarget(): View? =
        tiles().firstOrNull { it.app.pkg == lastRowPkg } ?: middleView()

    /** Shows, updates or hides the "now playing" card. */
    private fun showNowPlaying(np: NowPlaying?) {
        nowPlaying = np
        if (np != null) {
            nowCard.show(np)
            return
        }
        if (!nowCard.isShowing) return
        // Never leave the selection on a card that's fading away.
        val f = currentFocus
        if (f != null && isInside(f, nowCard)) {
            Nav.homeKey = null
            rowTarget()?.let { select(it) }
        }
        nowCard.hide()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /** The app in the middle of the row (left of the two middle ones when the count is even). */
    private fun middleView(): View? =
        row.getChildAt((row.childCount - 1) / 2) ?: dock.getChildAt(DOCK_APPS)

    /** Selects the remembered item, or the app in the middle of the row. */
    private fun applyFocus() {
        val target = viewFor(Nav.homeKey) ?: middleView() ?: return
        select(target)
    }

    private fun select(target: View) {
        pendingFocus = target
        target.post {
            if (!target.isFocused) target.requestFocus()
            if (pendingFocus === target) pendingFocus = null
        }
    }

    /** Back (and Home while already in eTV) goes to the app in the middle of the row. */
    private fun goToMiddleApp() {
        if (movingPkg != null) endMove()
        Nav.homeKey = null
        applyFocus()
    }

    // ---------------- rendering ----------------

    private fun render() {
        rebuilding = true
        try {
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

            nowCard.applyTheme(pal, accent)

            dock.removeAllViews()
            val gap = dpi(14f)
            fun addDock(v: View) {
                val lp = LinearLayout.LayoutParams(-2, dpi(44f))
                if (dock.childCount > 0) lp.marginStart = gap
                dock.addView(v, lp)
            }
            addDock(dockButton(DOCK_SEARCH, "Search", R.drawable.ic_search) { openSearch() })
            addDock(dockButton(DOCK_INPUTS, "Inputs", R.drawable.ic_inputs) { showInputs() })
            addDock(dockButton(DOCK_APPS, "All apps", R.drawable.ic_apps) { openAllApps() })
            val settingsLabel = if (prefs.updateCode > 0) "Settings  ·  Update" else "Settings"
            addDock(dockButton(DOCK_SETTINGS, settingsLabel, R.drawable.ic_settings) { openCustomise() })

            updateClock()
            buildRow()
        } finally {
            rebuilding = false
        }
        needsRender = false
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
        val g = when (prefs.greeting) {
            0 -> "$ar  ·  $en"
            1 -> ar
            2 -> en
            else -> ""
        }
        val gv = if (prefs.greeting == 3) View.GONE else View.VISIBLE
        if (greeting.visibility != gv) greeting.visibility = gv

        val locale = Locale.getDefault()
        // Only touch what changed: setting the same text still makes Android redo the layout.
        setIfChanged(greeting, g)
        setIfChanged(clock, SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", locale).format(now))
        setIfChanged(date, SimpleDateFormat("EEEE, d MMMM", locale).format(now))
    }

    private fun setIfChanged(view: TextView, text: String) {
        if (view.text?.toString() != text) view.text = text
    }

    private fun tiles(): List<ArchTile> =
        (0 until row.childCount).mapNotNull { row.getChildAt(it) as? ArchTile }

    /** Rebuilds the row of apps. Callers choose what to select afterwards. */
    private fun rebuildRow() {
        rebuilding = true
        try {
            buildRow()
        } finally {
            rebuilding = false
        }
    }

    private fun buildRow() {
        val all = AppRepo.all(this)
        renderedAppsVersion = AppRepo.version
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
            t.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                if (has) remember(v, "app:${app.pkg}")
            }
            if (app.pkg == movingPkg) t.moving = true
            row.addView(t, lp)
        }
        emptyHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- app options & moving ----------------

    private fun showTileOptions(app: AppEntry) {
        val items = arrayOf("Open", "Move", "Remove from home", "App info")
        choose(app.label, items) { which ->
            when (which) {
                0 -> AppRepo.launch(this, app)
                1 -> startMove(app)
                2 -> removeFromHome(app)
                3 -> AppRepo.openAppInfo(this, app.pkg)
            }
        }
    }

    /** Removes an app from the row and selects its neighbour, so you stay in the same spot. */
    private fun removeFromHome(app: AppEntry) {
        val list = prefs.favourites
        val i = list.indexOf(app.pkg)
        val rest = list.filter { it != app.pkg }
        prefs.favourites = rest
        Nav.homeKey = rest.getOrNull(i.coerceAtMost(rest.size - 1).coerceAtLeast(0))?.let { "app:$it" }
        rebuildRow()
        applyFocus()
    }

    private fun startMove(app: AppEntry) {
        movingPkg = app.pkg
        tiles().firstOrNull { it.app.pkg == app.pkg }?.let {
            it.moving = true
            select(it)
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
        rebuildRow()
        Nav.homeKey = "app:$m"
        applyFocus()
    }

    private fun endMove() {
        val m = movingPkg
        movingPkg = null
        tiles().firstOrNull { it.app.pkg == m }?.moving = false
        moveHint.visibility = View.GONE
    }

    // ---------------- remote ----------------

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
        if (keepFocusInPlace(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Keeps the remote predictable: Left / Right stop at the ends of the home row and the dock
     * instead of jumping to the other one, Up from the dock returns to the app you were on, and
     * Up from the row / Down from the dock do nothing.
     */
    private fun keepFocusInPlace(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val f = currentFocus ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (nowCard.isShowing && isInside(f, nowCard)) return moveInCard(f, dir)
        val inRow = f.parent === row
        val inDock = f.parent === dock
        if (!inRow && !inDock) return false

        return when (dir) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> {
                val group = if (inRow) row else dock
                FocusFinder.getInstance().findNextFocus(group, f, dir) == null // at the end: stay put
            }
            View.FOCUS_UP -> {
                if (inDock) {
                    rowTarget()?.let { select(it) }
                } else if (nowCard.isShowing) {
                    // Up from the apps reaches the "now playing" card.
                    select(nowCard.play)
                }
                true
            }
            else -> {
                // Down from the row lands on the nearest dock button.
                if (inRow && dock.childCount > 0) {
                    val middle = dock.getChildAt(DOCK_APPS) ?: dock.getChildAt(0)
                    val fromNearest = FocusFinder.getInstance().findNextFocus(root, f, View.FOCUS_DOWN)
                    select(if (fromNearest?.parent === dock) fromNearest else middle)
                }
                true
            }
        }
    }

    /**
     * Inside the card: Left / Right move between the controls, Up goes from the controls to the
     * artwork (OK there opens the app), Down goes from the artwork to the controls and from the
     * controls back to the apps.
     */
    private fun moveInCard(f: View, dir: Int): Boolean {
        val onOpen = f === nowCard.openArea
        when (dir) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> {
                if (!onOpen) {
                    val next = FocusFinder.getInstance().findNextFocus(f.parent as ViewGroup, f, dir)
                    next?.let { select(it) }
                }
            }
            View.FOCUS_UP -> if (!onOpen) select(nowCard.openArea)
            View.FOCUS_DOWN -> if (onOpen) select(nowCard.play) else rowTarget()?.let { select(it) }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to.
        goToMiddleApp()
    }

    /**
     * Remote keys (Philips layout in mind):
     * 1–9 open the app in that spot, 0 opens All apps, Options/Menu or Info opens the selected
     * app's options (or Customise), the colour keys are shortcuts, Sources opens Inputs.
     * Volume, mute and the TV's own keys are never touched.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            goToMiddleApp() // a launcher has nowhere to go back to
            return true
        }
        if (event.repeatCount > 0) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val tile = tiles().getOrNull(keyCode - KeyEvent.KEYCODE_1) ?: return true
                select(tile)
                AppRepo.launch(this, tile.app)
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_PROG_RED -> { openAllApps(); return true }
            KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_TV_INPUT -> { showInputs(); return true }
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_SEARCH -> { openSearch(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { openCustomise(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                val tile = currentFocus as? ArchTile
                if (tile != null) showTileOptions(tile.app) else openCustomise()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openAllApps(focusPkg: String? = null, instant: Boolean = false) {
        val i = Intent(this, AppsActivity::class.java)
        if (focusPkg != null) i.putExtra(AppsActivity.EXTRA_FOCUS, focusPkg)
        if (instant) i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(i)
    }

    private fun openCustomise() = startActivity(Intent(this, SettingsActivity::class.java))

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
