package app.etv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.ImageView
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

        /** Height of the "now playing" bar. */
        private const val NOW_BAR_DP = 44f

    }

    private lateinit var root: FrameLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var moveHint: TextView
    private lateinit var dock: LinearLayout
    private lateinit var nowCard: NowPlayingCard
    private lateinit var nowWatcher: NowPlayingWatcher
    private var nowPlaying: NowPlaying? = null

    // Parts only some themes have.
    private var greeting: TextView? = null
    private var greeting2: TextView? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var dots: DotsView? = null
    private var caption: TextView? = null
    private var inputsRow: LinearLayout? = null
    private var prayers: PrayerStripView? = null

    /** The Hijri date goes on its own line under the date (themes with little room beside it). */
    private var hijriOwnLine = false
    private var dockVertical = false
    private var dockAppsIndex = 0

    private var theme: Theme = Look.themes[0]
    private var pal: Palette = Look.palettes[0]
    private var accent: Int = Look.accents[0].second
    private var movingPkg: String? = null

    /** The app last selected on the row: moving back into the doors returns to it. */
    private var lastRowPkg: String? = null

    private var isResumedNow = false
    private var needsRender = true
    private var renderedAppsVersion = -1

    /** A view eTV is about to select itself; focus changes on the way there aren't "where you were". */
    private var pendingFocus: View? = null
    private var rebuilding = false

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
            // In case the athan alarm came late (or not at all): its time has come, play it.
            AthanScheduler.checkDue(this@MainActivity)
        }
    }

    /** New prayer times arrived (from aladhan.com or the mosque). */
    private val prayersChanged: () -> Unit = { if (isResumedNow) updateClock() }

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
        nowWatcher = NowPlayingWatcher(this) { np -> showNowPlaying(np) }
        render()

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
        PrayerStore.listeners.add(prayersChanged)

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

        // Prayer times: keep them fresh, the athan alarm set, and the chosen recordings on the TV.
        PrayerSync.refreshIfDue(this)
        AthanScheduler.schedule(this)
        AthanScheduler.checkDue(this)
        AthanSounds.ensureChosen(this)

        // First run: offer the Home button setup once, only if Home doesn't reach eTV yet.
        if (!prefs.setupShown) {
            prefs.setupShown = true
            if (!HomeSetup.homeHandled(this)) {
                startActivity(Intent(this, SetupActivity::class.java))
            }
        } else if (!prefs.athanSetupShown && prefs.place == null && (prefs.athanOn || prefs.showPrayers)) {
            // The athan needs to know where you are: ask once.
            prefs.athanSetupShown = true
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CHOOSE_CITY, true))
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
        PrayerStore.listeners.remove(prayersChanged)
        super.onDestroy()
    }

    private fun registerSystemReceiver(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
    }

    // ---------------- building the screen for the chosen theme ----------------

    /** Rebuilds the whole home screen for the current theme and options. */
    private fun render() {
        rebuilding = true
        try {
            theme = Look.theme(prefs)
            pal = Look.palette(prefs)
            accent = Look.accent(prefs)
            window.setBackgroundDrawable(ColorDrawable(pal.bg))

            if (::nowCard.isInitialized) nowCard.pause()
            greeting = null; greeting2 = null; clock = null; date = null; dots = null
            caption = null; inputsRow = null; dockVertical = false
            prayers = null; hijriOwnLine = theme.layout != Layout.CLASSIC && theme.layout != Layout.DOORWAY

            root = FrameLayout(this).apply {
                clipChildren = false
                setBackgroundColor(pal.bg)
            }
            nowCard = NowPlayingCard(this)
            nowCard.applyTheme(pal, accent)
            nowCard.onOpen = { nowPlaying?.let { nowWatcher.open(it) } }
            nowCard.onPlayPause = { nowPlaying?.let { nowWatcher.playPause(it) } }
            nowCard.onPrev = { nowPlaying?.let { nowWatcher.previous(it) } }
            nowCard.onNext = { nowPlaying?.let { nowWatcher.next(it) } }
            nowCard.onFocused = { v, key -> remember(v, key) }
            nowCard.onProgress = { p -> dots?.progress = p }

            emptyHint = text(14f, 300, color = pal.muted, gravity = Gravity.CENTER).apply {
                text = "Your home row is empty.\nOpen All apps and hold OK on an app to add it here."
                visibility = View.GONE
            }
            moveHint = text(13f, 400, color = accent, gravity = Gravity.CENTER).apply { visibility = View.GONE }

            when (theme.layout) {
                Layout.CLASSIC -> buildClassic()
                Layout.DOORWAY -> buildDoorway()
                Layout.RIAD -> buildRiad()
                Layout.CARTOUCHE -> buildCartouche()
                Layout.LANTERN -> buildLantern()
            }
            setContentView(root)

            updateClock()
            buildRow()
            buildInputs()
        } finally {
            rebuilding = false
        }
        needsRender = false
        // A rebuilt screen has a new bar: show what's playing straight away.
        nowPlaying?.let { nowCard.show(it) }
    }

    // ---- small builders ----

    private fun text(
        sizeSp: Float,
        weight: Int,
        display: Boolean = false,
        color: Int = pal.text,
        gravity: Int = Gravity.START,
    ): TextView = TextView(this).apply {
        typeface = if (display) Fonts.display(this@MainActivity, weight) else Fonts.body(this@MainActivity, weight)
        sp(sizeSp)
        setTextColor(color)
        this.gravity = gravity
    }

    /** Frame layout params in dp, margins relative (start / end follow the reading direction). */
    private fun at(w: Int, h: Int, gravity: Int, start: Float = 0f, top: Float = 0f, end: Float = 0f, bottom: Float = 0f) =
        FrameLayout.LayoutParams(w, h, gravity).apply {
            marginStart = dpi(start)
            topMargin = dpi(top)
            marginEnd = dpi(end)
            bottomMargin = dpi(bottom)
        }

    private fun clockView(sizeSp: Float, gravity: Int = Gravity.CENTER) =
        text(sizeSp, 500, display = true, gravity = gravity).apply {
            includeFontPadding = false
            fontFeatureSettings = "tnum" // equal-width digits, so the clock doesn't shuffle sideways
        }

    /** The row of doors inside its scroller. */
    private fun makeRow(scroller: HorizontalScrollView, gravity: Int, padStart: Float, padEnd: Float) {
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            clipChildren = false
            clipToPadding = false
            setPaddingRelative(dpi(padStart), dpi(24f), dpi(padEnd), dpi(4f))
        }
        rowScroll = scroller.apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        rowScroll.addView(row, FrameLayout.LayoutParams(-2, -1))
    }

    private fun makeDock(vertical: Boolean, gravity: Int) {
        dockVertical = vertical
        dock = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            this.gravity = gravity
            clipChildren = false
            clipToPadding = false
        }
    }

    private fun addToDock(v: View, w: Int, h: Int, gapDp: Float) {
        val lp = LinearLayout.LayoutParams(w, h)
        if (dock.childCount > 0) {
            if (dockVertical) lp.topMargin = dpi(gapDp) else lp.marginStart = dpi(gapDp)
        }
        dock.addView(v, lp)
    }

    private fun wire(v: View, index: Int, grow: Float, onClick: () -> Unit) {
        v.isFocusable = true
        v.isClickable = true
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { view, has ->
            view.softScale(if (has) grow else 1f)
            if (has) remember(view, "dock:$index")
        }
    }

    /** Classic: a pill with an icon and a name. */
    private fun pillButton(index: Int, label: String, iconRes: Int, onClick: () -> Unit): TextView {
        val colors = focusColors(pal.onAccent, pal.label)
        val icon = getDrawable(iconRes)?.mutate()?.apply { setTintList(colors) }
        return text(13f, 400, color = pal.label, gravity = Gravity.CENTER).apply {
            this.text = label
            setTextColor(colors)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dpi(9f)
            setPadding(dpi(18f), 0, dpi(20f), 0)
            background = pillBackground(this@MainActivity, 22f, pal.tileStroke, accent)
            wire(this, index, 1.06f, onClick)
        }
    }

    /** Doorway and Lantern: a round icon button (outlined, or bare for Lantern). */
    private fun roundButton(index: Int, label: String, iconRes: Int, outlined: Boolean, badge: Boolean, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = label
            imageTintList = focusColors(pal.onAccent, if (badge) accent else pal.label)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    if (outlined) setStroke(dpi(1f), pal.tileStroke)
                })
            }
            wire(this, index, 1.08f, onClick)
        }

    /** Riad: a text tab; the current section is filled in door blue. */
    private fun tabButton(index: Int, label: String, current: Boolean, onClick: () -> Unit): TextView =
        text(13.5f, if (current) 500 else 400, gravity = Gravity.CENTER).apply {
            this.text = label
            setPadding(dpi(15f), 0, dpi(15f), 0)
            setTextColor(focusColors(pal.onAccent, if (current) pal.tileText else pal.text))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(16f)
                    setColor(accent)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = dp(16f)
                    setColor(if (current) pal.tile else Color.TRANSPARENT)
                })
            }
            wire(this, index, 1.04f, onClick)
        }

    /** Cartouche: a small upright cartouche on the side rail. */
    private fun railButton(index: Int, label: String, iconRes: Int, current: Boolean, badge: Boolean, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = label
            imageTintList = focusColors(pal.onAccent, if (current || badge) accent else pal.text)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(18f)
                    setColor(accent)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = dp(18f)
                    setColor(Color.TRANSPARENT)
                    setStroke(dpi(if (current) 1.5f else 1f), if (current) accent else pal.tileStroke)
                })
            }
            wire(this, index, 1.06f, onClick)
        }

    private val updateReady: Boolean get() = prefs.updateCode > 0

    private fun header(gravity: Int, greetSp: Float, clockSp: Float, dateSp: Float, withDots: Boolean): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = gravity
        }
        val g = text(greetSp, 500, display = true, color = accent, gravity = Gravity.CENTER).apply {
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val c = clockView(clockSp)
        val d = text(dateSp, 300, color = pal.muted, gravity = Gravity.CENTER).apply {
            visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        }
        // Full-width lines: when the time changes only the text redraws; nothing else moves.
        h.addView(g, LinearLayout.LayoutParams(-1, -2))
        h.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        h.addView(d, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        if (withDots) {
            val s = DotsView(this).apply {
                dot = pal.dot
                accent = this@MainActivity.accent
            }
            h.addView(s, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14f) })
            dots = s
        }
        greeting = g; clock = c; date = d
        return h
    }

    private fun archesBackground() {
        if (!prefs.arches) return
        val a = ArchBackgroundView(this).apply {
            outer = pal.frame1
            innerColor = pal.frame2
        }
        root.addView(a, FrameLayout.LayoutParams(-1, -1))
    }

    /** The selected app's name, under the doors (Doorway, Lantern). */
    private fun captionBlock(nameSp: Float): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val name = text(nameSp, 500, display = true, gravity = Gravity.CENTER).apply { maxLines = 1 }
        box.addView(name, LinearLayout.LayoutParams(-1, -2))
        caption = name
        return box
    }

    private fun sectionTitle(label: String, color: Int): TextView =
        text(10.5f, 500, color = color).apply {
            text = label
            letterSpacing = 0.2f
        }

    /** Today's prayer times, in this theme's style; null when they're switched off. */
    private fun prayerStrip(vertical: Boolean = false, columnDp: Float = 84f): PrayerStripView? {
        if (!prefs.showPrayers) return null
        val light = theme.layout == Layout.RIAD
        val colors = PrayerColors(
            name = pal.label,
            time = pal.text,
            muted = pal.muted,
            line = pal.tileStroke,
            // Riad marks the next prayer with one of its blue doors, brass studs and all.
            accent = if (light) pal.tile else accent,
            doorFill = if (light) pal.tile else Look.withAlpha(accent, 70),
            doorStroke = if (light) pal.tile else accent,
            bg = pal.bg,
            studs = if (light) pal.studs else 0,
        )
        return PrayerStripView(this, vertical).also {
            it.columnDp = columnDp
            it.applyTheme(colors)
            prayers = it
        }
    }

    // ---- Classic: today's eTV ----

    private fun buildClassic() {
        archesBackground()
        // The prayer times across the top, the greeting and clock below them.
        val strip = prayerStrip()
        strip?.let { root.addView(it, at(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 16f)) }
        root.addView(
            header(Gravity.CENTER_HORIZONTAL, 18f, if (strip != null) 52f else 58f, 13f, true),
            at(-1, -2, Gravity.TOP, top = if (strip != null) 112f else 70f),
        )
        makeRow(HorizontalScrollView(this), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 48f, 48f)
        root.addView(rowScroll, at(-1, dpi(190f), Gravity.TOP, top = 232f))
        root.addView(emptyHint, at(-1, -2, Gravity.TOP, top = 302f))
        root.addView(moveHint, at(-1, -2, Gravity.BOTTOM, bottom = 86f))

        makeDock(false, Gravity.CENTER)
        val h = dpi(44f)
        addToDock(pillButton(0, "Search", R.drawable.ic_search) { openSearch() }, -2, h, 14f)
        addToDock(pillButton(1, "Inputs", R.drawable.ic_inputs) { showInputs() }, -2, h, 14f)
        addToDock(pillButton(2, "All apps", R.drawable.ic_apps) { openAllApps() }, -2, h, 14f)
        addToDock(pillButton(3, if (updateReady) "Settings  ·  Update" else "Settings", R.drawable.ic_settings) { openCustomise() }, -2, h, 14f)
        dockAppsIndex = 2
        root.addView(dock, at(-1, dpi(56f), Gravity.BOTTOM, bottom = 18f))

        // The "now playing" bar sits between the doors and the dock.
        root.addView(nowCard, at(-2, dpi(NOW_BAR_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addOnLayoutChangeListener { v, _, t, _, b, _, oldT, _, oldB -> if (b - t != oldB - oldT) v.post { placeNowBar() } }
    }

    // ---- Doorway: calmer, buttons in the corner, the selected app named under the doors ----

    private fun buildDoorway() {
        archesBackground()
        root.addView(header(Gravity.CENTER_HORIZONTAL, 17f, 54f, 13f, true), at(-1, -2, Gravity.TOP, top = 50f))

        makeDock(false, Gravity.CENTER_VERTICAL)
        val s = dpi(38f)
        addToDock(roundButton(0, "Search", R.drawable.ic_search, true, false) { openSearch() }, s, s, 8f)
        addToDock(roundButton(1, "Inputs", R.drawable.ic_inputs, true, false) { showInputs() }, s, s, 8f)
        addToDock(roundButton(2, "All apps", R.drawable.ic_apps, true, false) { openAllApps() }, s, s, 8f)
        addToDock(roundButton(3, "Settings", R.drawable.ic_settings, true, updateReady) { openCustomise() }, s, s, 8f)
        dockAppsIndex = 2
        root.addView(dock, at(-2, -2, Gravity.TOP or Gravity.END, top = 22f, end = 30f))
        // The prayer times in the other top corner, one a line.
        prayerStrip(vertical = true)?.let { root.addView(it, at(-2, -2, Gravity.TOP or Gravity.START, start = 36f, top = 26f)) }

        makeRow(HorizontalScrollView(this), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 48f, 48f)
        root.addView(rowScroll, at(-1, dpi(196f), Gravity.TOP, top = 200f))
        root.addView(captionBlock(24f), at(-1, -2, Gravity.TOP, top = 404f))
        root.addView(emptyHint, at(-1, -2, Gravity.TOP, top = 270f))
        root.addView(moveHint, at(-1, -2, Gravity.TOP, top = 452f))
        root.addView(nowCard, at(-2, dpi(NOW_BAR_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = 16f))
    }

    // ---- Riad: daylight, tabs, inputs on home ----

    private fun buildRiad() {
        val band = TileBandView(this).apply {
            tileColor = pal.tile
            ground = pal.frame1
            centre = pal.frame2
        }
        root.addView(band, at(-1, dpi(14f), Gravity.BOTTOM))

        // Left: the eTV mark and the greeting.
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(9f), dp(9f), dp(9f), dp(9f), dp(3f), dp(3f), dp(3f), dp(3f))
                setColor(pal.tile)
            }
        }
        brand.addView(mark, LinearLayout.LayoutParams(dpi(18f), dpi(24f)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(15f, 600, display = true).apply { text = "eTV" })
        val g = text(10.5f, 400, color = pal.muted)
        names.addView(g)
        greeting = g
        brand.addView(names, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(10f) })
        root.addView(brand, at(-2, -2, Gravity.TOP or Gravity.START, start = 48f, top = 22f))

        // Right: the clock.
        val time = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val c = clockView(26f, Gravity.END)
        val d = text(10.5f, 400, color = pal.muted, gravity = Gravity.END).apply {
            visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        }
        time.addView(c, LinearLayout.LayoutParams(dpi(170f), -2))
        time.addView(d, LinearLayout.LayoutParams(dpi(170f), -2))
        clock = c; date = d
        root.addView(time, at(-2, -2, Gravity.TOP or Gravity.END, top = 20f, end = 48f))

        // Centre: the tabs.
        makeDock(false, Gravity.CENTER_VERTICAL)
        val h = dpi(32f)
        addToDock(tabButton(0, "Home", true) { goToMiddleApp() }, -2, h, 4f)
        addToDock(tabButton(1, "Apps", false) { openAllApps() }, -2, h, 4f)
        addToDock(tabButton(2, "Inputs", false) { showInputs() }, -2, h, 4f)
        addToDock(tabButton(3, "Search", false) { openSearch() }, -2, h, 4f)
        addToDock(tabButton(4, if (updateReady) "Settings  •" else "Settings", false) { openCustomise() }, -2, h, 4f)
        dockAppsIndex = 1
        root.addView(dock, at(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 26f))

        root.addView(sectionTitle("FAVOURITES", pal.muted), at(-2, -2, Gravity.TOP or Gravity.START, start = 48f, top = 84f))
        makeRow(HorizontalScrollView(this), Gravity.START or Gravity.BOTTOM, 48f, 48f)
        root.addView(rowScroll, at(-1, dpi(196f), Gravity.TOP, top = 96f))
        root.addView(emptyHint, at(-1, -2, Gravity.TOP, top = 170f))

        root.addView(sectionTitle("INPUTS", pal.muted), at(-2, -2, Gravity.TOP or Gravity.START, start = 48f, top = 306f))
        val inputs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        inputsRow = inputs
        root.addView(inputs, at(-2, dpi(44f), Gravity.TOP or Gravity.START, start = 48f, top = 324f))

        prayerStrip(columnDp = 86f)?.let {
            root.addView(sectionTitle("PRAYER TIMES", pal.muted), at(-2, -2, Gravity.TOP or Gravity.START, start = 48f, top = 386f))
            root.addView(it, at(-2, -2, Gravity.TOP or Gravity.START, start = 48f, top = 408f))
        }

        root.addView(moveHint, at(-1, -2, Gravity.TOP, top = 384f))
        root.addView(nowCard, at(-2, dpi(NOW_BAR_DP), Gravity.BOTTOM or Gravity.END, end = 40f, bottom = 30f))
    }

    // ---- Cartouche: lapis and gold, a side rail ----

    private fun buildCartouche() {
        val railW = 78f
        // The rail: eTV on top, the sections as small cartouches, a hairline beside it.
        val rail = FrameLayout(this)
        rail.addView(text(15f, 600, display = true, color = accent, gravity = Gravity.CENTER).apply { text = "eTV" },
            at(-1, -2, Gravity.TOP, top = 26f))
        makeDock(true, Gravity.CENTER_HORIZONTAL)
        val bw = dpi(36f)
        val bh = dpi(48f)
        addToDock(railButton(0, "Home", R.drawable.ic_home, true, false) { goToMiddleApp() }, bw, bh, 10f)
        addToDock(railButton(1, "All apps", R.drawable.ic_apps, false, false) { openAllApps() }, bw, bh, 10f)
        addToDock(railButton(2, "Inputs", R.drawable.ic_inputs, false, false) { showInputs() }, bw, bh, 10f)
        addToDock(railButton(3, "Search", R.drawable.ic_search, false, false) { openSearch() }, bw, bh, 10f)
        addToDock(railButton(4, "Settings", R.drawable.ic_settings, false, updateReady) { openCustomise() }, bw, bh, 10f)
        dockAppsIndex = 1
        rail.addView(dock, at(-1, -2, Gravity.TOP, top = 104f))
        rail.addView(View(this).apply { setBackgroundColor(pal.frame1) }, at(dpi(1f), -1, Gravity.END))
        root.addView(rail, at(dpi(railW), -1, Gravity.START))

        // The time, in a cartouche, with the greeting beside it.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val outer = FrameLayout(this).apply {
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(40f)
                setStroke(dpi(1f), pal.dot)
            }
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(26f), 0, dpi(26f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(34f)
                setStroke(dpi(1.5f), accent)
            }
        }
        val c = clockView(40f)
        box.addView(c, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        outer.addView(box, FrameLayout.LayoutParams(-2, dpi(64f)))
        top.addView(outer)
        top.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2f)
                setColor(accent)
            }
        }, LinearLayout.LayoutParams(dpi(2.5f), dpi(50f)).apply { marginStart = dpi(8f) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val g1 = text(16f, 500, display = true, color = accent)
        val g2 = text(12f, 400)
        val d = text(11f, 300, color = pal.muted).apply { visibility = if (prefs.showDate) View.VISIBLE else View.GONE }
        info.addView(g1)
        info.addView(g2)
        info.addView(d, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(2f) })
        top.addView(info, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(18f) })
        clock = c; greeting = g1; greeting2 = g2; date = d
        root.addView(top, at(-2, -2, Gravity.TOP or Gravity.START, start = 118f, top = 34f))
        // The prayer times opposite the clock, each in a cartouche.
        prayerStrip(columnDp = 78f)?.let { root.addView(it, at(-2, -2, Gravity.TOP or Gravity.END, top = 34f, end = 36f)) }

        root.addView(sectionTitle("FAVOURITES", accent), at(-2, -2, Gravity.TOP or Gravity.START, start = 118f, top = 150f))
        makeRow(HorizontalScrollView(this), Gravity.START or Gravity.BOTTOM, 40f, 40f)
        root.addView(rowScroll, at(-1, dpi(200f), Gravity.TOP, start = railW + 1f, top = 160f))
        root.addView(emptyHint, at(-1, -2, Gravity.TOP, start = railW, top = 240f))
        root.addView(moveHint, at(-1, -2, Gravity.TOP, start = railW, top = 372f))

        val frieze = PapyrusView(this).apply { color = pal.frame2 }
        root.addView(frieze, at(-1, dpi(11f), Gravity.BOTTOM, start = railW + 1f, bottom = 6f))
        root.addView(nowCard, at(-2, dpi(NOW_BAR_DP), Gravity.BOTTOM or Gravity.START, start = 112f, bottom = 28f))
    }

    // ---- Lantern: one door at a time ----

    private fun buildLantern() {
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(250f)
                colors = intArrayOf(Look.withAlpha(accent, 0x18), Look.withAlpha(accent, 0))
            }
        }
        root.addView(glow, at(dpi(560f), dpi(480f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 10f))

        val time = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val c = clockView(24f, Gravity.START)
        val d = text(11f, 300, color = pal.muted).apply { visibility = if (prefs.showDate) View.VISIBLE else View.GONE }
        time.addView(c, LinearLayout.LayoutParams(dpi(84f), -2))
        time.addView(d, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(3f) })
        clock = c; date = d
        root.addView(time, at(-2, -2, Gravity.TOP or Gravity.START, start = 40f, top = 26f))

        makeDock(false, Gravity.CENTER_VERTICAL)
        val s = dpi(38f)
        addToDock(roundButton(0, "Search", R.drawable.ic_search, false, false) { openSearch() }, s, s, 4f)
        addToDock(roundButton(1, "Inputs", R.drawable.ic_inputs, false, false) { showInputs() }, s, s, 4f)
        addToDock(roundButton(2, "All apps", R.drawable.ic_apps, false, false) { openAllApps() }, s, s, 4f)
        addToDock(roundButton(3, "Settings", R.drawable.ic_settings, false, updateReady) { openCustomise() }, s, s, 4f)
        dockAppsIndex = 2
        root.addView(dock, at(-2, -2, Gravity.TOP or Gravity.END, top = 20f, end = 30f))
        // The prayer times above the lit door.
        prayerStrip(columnDp = 76f)?.let { root.addView(it, at(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 14f)) }

        val scroller = CentreScrollView(this)
        makeRow(scroller, Gravity.BOTTOM, 0f, 0f)
        root.addView(rowScroll, at(-1, dpi(330f), Gravity.TOP, top = 64f))
        scroller.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) scroller.post { centreLantern(false) }
        }
        root.addView(captionBlock(28f), at(-1, -2, Gravity.TOP, top = 398f))
        root.addView(emptyHint, at(-1, -2, Gravity.TOP, top = 200f))
        root.addView(moveHint, at(-1, -2, Gravity.TOP, top = 452f))
        root.addView(nowCard, at(-2, dpi(NOW_BAR_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = 14f))
    }

    /** Lantern: the selected door full size in the middle, its neighbours smaller and dimmer. */
    private fun shapeLantern(animate: Boolean) {
        if (theme.layout != Layout.LANTERN) return
        val ts = tiles()
        val i = ts.indexOfFirst { it.app.pkg == lastRowPkg }.takeIf { it >= 0 } ?: ((ts.size - 1) / 2)
        for ((k, t) in ts.withIndex()) {
            val dist = kotlin.math.abs(k - i)
            val scale = when (dist) { 0 -> 1f; 1 -> 0.66f; 2 -> 0.5f; else -> 0.42f }
            val alpha = when (dist) { 0 -> 1f; 1 -> 0.78f; 2 -> 0.45f; else -> 0.25f }
            if (animate) {
                t.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(220).start()
            } else {
                t.scaleX = scale; t.scaleY = scale; t.alpha = alpha
            }
        }
    }

    private fun centreLantern(smooth: Boolean) {
        val scroller = rowScroll as? CentreScrollView ?: return
        val t = tiles().firstOrNull { it.app.pkg == lastRowPkg } ?: middleView() ?: return
        if (t.parent === row) scroller.centreOn(t, smooth)
    }

    // ---- Riad's inputs row ----

    private fun buildInputs() {
        val box = inputsRow ?: return
        box.removeAllViews()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Live TV" to { openLiveTv() })
        for (input in passthroughInputs()) {
            val name = (input.loadCustomLabel(this) ?: input.loadLabel(this)).toString()
            items.add(name to { switchTo(input) })
        }
        val swatches = intArrayOf(pal.tile, pal.frame2, Look.withAlpha(pal.tile, 150), accent)
        for ((i, item) in items.withIndex()) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(10f), 0, dpi(16f), 0)
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = dp(20f)
                        setColor(accent)
                    })
                    // Unselected: whitewash fill with a thin outline.
                    addState(intArrayOf(), GradientDrawable().apply {
                        cornerRadius = dp(20f)
                        setColor(pal.card)
                        setStroke(dpi(1f), pal.tileStroke)
                    })
                }
                setOnClickListener { item.second() }
                setOnFocusChangeListener { v, has ->
                    v.softScale(if (has) 1.05f else 1f)
                    if (has) remember(v, "input:$i")
                }
            }
            val sw = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(dp(8f), dp(8f), dp(8f), dp(8f), dp(2f), dp(2f), dp(2f), dp(2f))
                    setColor(swatches[i % swatches.size])
                }
                isDuplicateParentStateEnabled = true
            }
            b.addView(sw, LinearLayout.LayoutParams(dpi(16f), dpi(21f)))
            val t = text(13f, 400, color = pal.text).apply {
                text = item.first
                setTextColor(focusColors(pal.onAccent, pal.text))
                isDuplicateParentStateEnabled = true
            }
            b.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(10f) })
            val lp = LinearLayout.LayoutParams(-2, dpi(40f))
            if (i > 0) lp.marginStart = dpi(10f)
            box.addView(b, lp)
        }
    }

    // ---- clock and greeting ----

    private fun updateClock() {
        val now = Date()
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val ar = if (h in 4..11) "صباح الخير" else "مساء الخير"
        val en = when (h) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        val mode = prefs.greeting
        greeting?.let { g ->
            val second = greeting2
            val text = when {
                mode == 3 -> ""
                second != null -> if (mode == 2) en else ar
                mode == 0 -> "$ar  ·  $en"
                mode == 1 -> ar
                else -> en
            }
            val vis = if (mode == 3) View.GONE else View.VISIBLE
            if (g.visibility != vis) g.visibility = vis
            setIfChanged(g, text)
        }
        greeting2?.let { g2 ->
            val vis = if (mode == 0) View.VISIBLE else View.GONE
            if (g2.visibility != vis) g2.visibility = vis
            setIfChanged(g2, en)
        }
        val locale = Locale.getDefault()
        clock?.let { setIfChanged(it, SimpleDateFormat(if (prefs.clock24) "HH:mm" else "h:mm", locale).format(now)) }
        date?.let {
            val pattern = if (theme.layout == Layout.CARTOUCHE || theme.layout == Layout.LANTERN) "EEEE · d MMMM" else "EEEE, d MMMM"
            var text = SimpleDateFormat(pattern, locale).format(now)
            hijriDate()?.let { h -> text += if (hijriOwnLine) "\n$h" else "  ·  $h" }
            setIfChanged(it, text)
        }
        updatePrayers()
    }

    // ---------------- prayer times ----------------

    private fun hijriDate(): String? {
        if (!prefs.showHijri) return null
        val tz = Days.tz(prefs.place)
        val h = PrayerStore.hijri(this, prefs, Days.today(tz), tz) ?: return null
        return Hijri.format(h, arabic = prefs.greeting != 2)
    }

    /** Fills in the prayer times: the next prayer marked, with how long until it. */
    private fun updatePrayers() {
        val strip = prayers ?: return
        val place = prefs.place
        if (place == null) {
            strip.showMessage("Prayer times  ·  choose your city in Settings → Athan")
            return
        }
        val tz = Days.tz(place)
        val now = System.currentTimeMillis()
        val today = Days.today(tz, now)
        val playing = AthanService.current?.prayer
        val next = PrayerStore.next(this, prefs, now, athanOnly = false)
        // After Isha, tomorrow's times.
        val day = if (playing != null) today else next?.day ?: today
        val t = PrayerStore.times(this, prefs, day) ?: return
        val friday = Days.isFriday(day, tz)
        val english = prefs.greeting == 2
        val shown = Prayer.values().filter { it != Prayer.SUNRISE || prefs.showSunrise }
        val ats = shown.map { Days.epoch(day, t[it.ordinal], tz) }
        val cells = shown.mapIndexed { idx, pr ->
            val at = ats[idx]
            val isNext = if (playing != null) pr == playing else next != null && pr == next.prayer && day == next.day
            val note = when {
                playing != null && pr == playing -> if (english) "Athan" else "الأذان"
                isNext && next != null -> countdown(next.at - now, english)
                else -> ""
            }
            PrayerCell(
                name = if (english) pr.enName(friday) else pr.arName(friday),
                time = Days.clock(t[pr.ordinal], prefs.clock24),
                note = note,
                next = isNext,
                past = !isNext && at <= now,
                quiet = pr == Prayer.SUNRISE,
            )
        }
        // Where now falls on the day's line: between two prayers, as a fraction of the way.
        var nowPos = 0f
        if (ats.isNotEmpty() && now >= ats.first()) {
            nowPos = (ats.size - 1).toFloat()
            for (i in 0 until ats.size - 1) {
                if (now < ats[i + 1]) {
                    nowPos = i + (now - ats[i]).toFloat() / (ats[i + 1] - ats[i]).coerceAtLeast(1L)
                    break
                }
            }
        }
        strip.show(cells, rightToLeft = !english, now = nowPos)
    }

    /** "in 1 h 42 min" / "بعد ١:٤٢", and under an hour "in 42 min" / "بعد ٤٢ دقيقة". */
    private fun countdown(ms: Long, english: Boolean): String {
        val mins = ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        return when {
            english && h > 0 -> if (m == 0) "in $h h" else "in $h h $m min"
            english -> "in $m min"
            h > 0 -> Hijri.arabicDigits("بعد $h:%02d".format(m))
            m <= 1 -> "الآن"
            else -> Hijri.arabicDigits("بعد $m دقيقة")
        }
    }

    // Only touch what changed: setting the same text still makes Android redo the layout.
    private fun setIfChanged(view: TextView, text: String) {
        if (view.text?.toString() != text) view.text = text
    }

    // ---------------- where you are ----------------

    /** Records the selected item as "where you were", unless eTV is moving the selection itself. */
    private fun remember(v: View, key: String) {
        if (rebuilding) return
        val p = pendingFocus
        if (p != null && p !== v) return
        pendingFocus = null
        Nav.homeKey = key
        if (key.startsWith("app:")) {
            lastRowPkg = key.substring(4)
            updateCaption(true)
        }
    }

    /** The selected app's name under the doors (Doorway, Lantern), and Lantern's door sizes. */
    private fun updateCaption(animate: Boolean) {
        val t = tiles().firstOrNull { it.app.pkg == lastRowPkg } ?: (middleView() as? ArchTile)
        caption?.let { setIfChanged(it, t?.app?.label ?: "") }
        shapeLantern(animate)
    }

    private fun viewFor(key: String?): View? = when {
        key == null -> null
        key.startsWith("app:") -> tiles().firstOrNull { it.app.pkg == key.substring(4) }
        key.startsWith("dock:") -> dock.getChildAt(key.substring(5).toIntOrNull() ?: -1)
        key.startsWith("media:") -> nowCard.viewFor(key)
        key.startsWith("input:") -> inputsRow?.getChildAt(key.substring(6).toIntOrNull() ?: -1)
        else -> null
    }

    /** The row app to return to from the dock or the card. */
    private fun rowTarget(): View? =
        tiles().firstOrNull { it.app.pkg == lastRowPkg } ?: middleView()

    /** Puts the "now playing" bar in the middle of the gap between the doors and the dock buttons. */
    private fun placeNowBar() {
        if (theme.layout != Layout.CLASSIC || !::nowCard.isInitialized || root.height == 0) return
        val doorsBottom = rowScroll.bottom - dpi(4f)
        val buttonsTop = dock.top + (dock.height - dpi(44f)) / 2
        val barH = dpi(NOW_BAR_DP)
        val top = (doorsBottom + buttonsTop - barH) / 2
        val lp = nowCard.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top
            nowCard.layoutParams = lp
        }
    }

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
        row.getChildAt((row.childCount - 1) / 2) ?: dock.getChildAt(dockAppsIndex)

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
        val lantern = theme.layout == Layout.LANTERN
        // Doorway and Lantern name the selected app under the doors instead of inside them.
        val named = theme.layout != Layout.DOORWAY && !lantern
        val scale = prefs.tileScale * (if (lantern) 2f else 1f)
        val gap = if (lantern) 0 else dpi(15f * scale)
        var tileW = 0
        for ((i, app) in apps.withIndex()) {
            val t = ArchTile(
                this, app, pal, accent, scale, prefs.iconStyle,
                if (named) prefs.nameMode else 2,
                shape = theme.shape,
                fillOnFocus = theme.fillOnFocus,
                grow = if (lantern) 0f else 1.12f,
            )
            tileW = t.tileW
            val lp = LinearLayout.LayoutParams(t.tileW, t.tileH)
            if (i > 0) lp.marginStart = gap
            t.setOnClickListener { AppRepo.launch(this, app) }
            t.setOnLongClickListener { showTileOptions(app); true }
            t.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                if (has) {
                    remember(v, "app:${app.pkg}")
                    if (lantern) (rowScroll as? CentreScrollView)?.centreOn(v, smooth = !rebuilding)
                }
            }
            if (app.pkg == movingPkg) t.moving = true
            row.addView(t, lp)
        }
        if (lantern) {
            // Half a screen of room at each end, so the first and last doors can reach the middle.
            val side = (rowScroll as CentreScrollView).centrePadding(if (tileW > 0) tileW else dpi(192f))
            row.setPaddingRelative(side, dpi(24f), side, dpi(4f))
        }
        emptyHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        updateCaption(false)
        if (lantern) rowScroll.post { centreLantern(false) }
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
        nowCard.animate().alpha(0f).setDuration(150).start()
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
        if (nowCard.isShowing) nowCard.animate().alpha(1f).setDuration(200).start()
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

    private enum class Zone { ROW, DOCK, BAR, INPUTS }

    private fun zoneOf(v: View): Zone? = when {
        v.parent === row -> Zone.ROW
        v.parent === dock -> Zone.DOCK
        inputsRow != null && v.parent === inputsRow -> Zone.INPUTS
        nowCard.isShowing && isInside(v, nowCard) -> Zone.BAR
        else -> null
    }

    private fun containerOf(z: Zone): ViewGroup = when (z) {
        Zone.ROW -> row
        Zone.DOCK -> dock
        Zone.INPUTS -> inputsRow ?: row
        Zone.BAR -> nowCard
    }

    /**
     * Keeps the remote predictable in every theme. Within the doors, the buttons, the inputs or
     * the "now playing" bar, the arrows move as usual. At the ends of a row Left / Right stay put
     * (except into or out of Cartouche's side rail), and moving into the doors always lands on
     * the app you were on, into the bar on play / pause.
     */
    private fun keepFocusInPlace(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val f = currentFocus ?: return false
        val zone = zoneOf(f) ?: return false

        // Moving within the same group: let Android do it (the row scrolls itself).
        if (FocusFinder.getInstance().findNextFocus(containerOf(zone), f, dir) != null) return false

        val horizontal = dir == View.FOCUS_LEFT || dir == View.FOCUS_RIGHT
        val target = FocusFinder.getInstance().findNextFocus(root, f, dir) ?: return true
        val to = zoneOf(target) ?: return true
        if (to == zone) return true
        if (horizontal && !(dockVertical && (zone == Zone.DOCK || to == Zone.DOCK))) return true
        when (to) {
            Zone.ROW -> rowTarget()?.let { select(it) }
            Zone.BAR -> select(nowCard.play)
            else -> select(target)
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
            // Back during the athan ends it.
            if (AthanService.isPlaying) {
                AthanService.stop()
                updateClock()
                return true
            }
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

    private fun passthroughInputs(): List<TvInputInfo> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        return try {
            tim?.tvInputList?.filter { it.isPassthroughInput }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun switchTo(input: TvInputInfo) {
        val uri = TvContract.buildChannelUriForPassthroughInput(input.id)
        if (!AppRepo.tryStart(this, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Toast.makeText(this, "Couldn't switch input", Toast.LENGTH_SHORT).show()
        }
    }

    /** Live TV: the TV's channel app (Philips' own, or Android's). */
    private fun openLiveTv() {
        if (AppRepo.tryStart(this, Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        for (pkg in listOf("org.droidtv.playtv", "com.google.android.tv", "com.android.tv")) {
            val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null && AppRepo.tryStart(this, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        }
        Toast.makeText(this, "No TV channel app found", Toast.LENGTH_SHORT).show()
    }

    private fun showInputs() {
        val inputs = passthroughInputs()
        if (inputs.isEmpty()) {
            val opened = AppRepo.tryStart(this, Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
                AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!opened) Toast.makeText(this, "No inputs found on this TV", Toast.LENGTH_SHORT).show()
            return
        }
        val names = inputs.map { (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString() }.toTypedArray()
        choose("Inputs", names) { which -> switchTo(inputs[which]) }
    }
}
