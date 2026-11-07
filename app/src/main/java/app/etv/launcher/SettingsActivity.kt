package app.etv.launcher

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
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
 * "Customise", laid out like the TV's own settings: categories on the left, their options on the
 * right. Moving through the categories shows their options straight away; Right goes into them,
 * Left or Back comes out again. Every option changes with one press of OK and shows instantly.
 */
class SettingsActivity : EtvActivity() {

    companion object {
        /** Open on Athan and ask for the city straight away (the first time eTV needs it). */
        const val EXTRA_CHOOSE_CITY = "app.etv.launcher.CHOOSE_CITY"
        private const val ATHAN = 2
    }

    private class Category(val name: String, val about: String)

    private val categories = listOf(
        Category("Look", "The theme, its colours and its background."),
        Category("Home screen", "What the home screen shows, and how the apps look on it."),
        Category("Athan", "Prayer times on the home screen, and the call to prayer when each one comes."),
        Category("Apps", "Hidden apps and your home row."),
        Category("Home button", "Making the remote's Home button open eTV."),
        Category("Updates", "New versions of eTV, straight from GitHub."),
        Category("Remote", "Shortcuts that work on the home screen."),
        Category("About", "This version of eTV, and the TV's own settings."),
    )

    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var menu: LinearLayout
    private lateinit var paneScroll: ScrollView
    internal lateinit var pane: LinearLayout
    internal var pal: Palette = Look.palettes[0]
    internal var accent = 0

    /** The category whose options are showing. */
    private var current = 0

    /** What to select after redrawing: "cat:<n>" or an option's key. */
    internal var focusKey: String? = "cat:0"

    /**
     * True while render() rebuilds the screen. Taking the views away moves the remote's focus to
     * the first category for a moment; that must not count as you choosing it.
     */
    private var rebuilding = false

    /** The category whose options are on the right now (-1 before the first). */
    private var shownPane = -1

    /** Ask for the city once the screen is up. */
    private var chooseCity = false

    private val prayersChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(56f), dpi(40f), dpi(56f), 0)
        }

        // Left: the title and the categories.
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            text = "Customise"
            sp(28f)
            includeFontPadding = false
        }
        left.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(22f) })
        menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(menu, LinearLayout.LayoutParams(-1, -2))
        columns.addView(left, LinearLayout.LayoutParams(dpi(230f), -1))

        // A hairline between the two.
        columns.addView(View(this).also { it.tag = "divider" }, LinearLayout.LayoutParams(dpi(1f), -1).apply {
            marginStart = dpi(28f)
            marginEnd = dpi(36f)
            bottomMargin = dpi(40f)
        })

        // Right: the options of the current category.
        paneScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dpi(48f))
        }
        pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paneScroll.addView(pane, FrameLayout.LayoutParams(-1, -2))
        columns.addView(paneScroll, LinearLayout.LayoutParams(0, -1, 1f))

        root.addView(columns, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (intent?.getBooleanExtra(EXTRA_CHOOSE_CITY, false) == true) {
            current = ATHAN
            focusKey = "location"
            chooseCity = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Show the updater's progress as it happens.
        Updater.onChange = { render() }
        AthanSounds.onChange = { render() }
        PrayerStore.listeners.add(prayersChanged)
        render()
        if (chooseCity) {
            chooseCity = false
            root.post { pickLocation() }
        }
    }

    override fun onPause() {
        Updater.onChange = null
        AthanSounds.onChange = null
        PrayerStore.listeners.remove(prayersChanged)
        super.onPause()
    }

    private fun <T> next(options: List<T>, current: Int): Int = (current + 1) % options.size

    // ---------------- drawing ----------------

    internal fun render() {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    private fun rebuild() {
        pal = Look.palette(prefs)
        accent = Look.accent(prefs)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        root.setBackgroundColor(pal.bg)
        title.setTextColor(pal.text)
        title.typeface = Fonts.display(this, 500)
        root.findViewWithTag<View>("divider")?.setBackgroundColor(pal.tileStroke)

        // Remember where you were before the views go.
        val key = focusKey
        val scrollY = paneScroll.scrollY
        val samePane = shownPane == current
        val oldIndex = (0 until pane.childCount).firstOrNull { pane.getChildAt(it).tag == key } ?: -1

        menu.removeAllViews()
        for ((i, c) in categories.withIndex()) menu.addView(categoryButton(i, c), LinearLayout.LayoutParams(-1, dpi(46f)).apply {
            topMargin = dpi(4f)
        })
        showPane(current)
        // Same options as before: stay scrolled where you were.
        if (samePane) paneScroll.scrollTo(0, scrollY)

        val target = when {
            key == null -> null
            key.startsWith("cat:") -> menu.getChildAt(key.substring(4).toIntOrNull() ?: 0)
            else -> pane.findViewWithTag(key) ?: nearestOption(oldIndex)
        } ?: menu.getChildAt(current)
        // At once, before anything is drawn, so focus never visibly jumps.
        target?.requestFocus()
        focusKey = (target?.tag as? String) ?: key
    }

    /** A category on the left: a small doorway marks the one whose options are showing. */
    private fun categoryButton(index: Int, c: Category): LinearLayout {
        val isCurrent = index == current
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), 0, dpi(14f), 0)
            isFocusable = true
            isClickable = true
            tag = "cat:$index"
            background = pillBackground(this@SettingsActivity, 14f, Color.TRANSPARENT, pal.card, accent)
            setOnClickListener { enterPane() }
            setOnFocusChangeListener { _, has ->
                if (has && !rebuilding) {
                    focusKey = "cat:$index"
                    if (current != index) {
                        current = index
                        refreshMenuMarks()
                        showPane(index)
                    }
                }
            }
        }
        val mark = View(this).apply { tag = "mark" }
        b.addView(mark, LinearLayout.LayoutParams(dpi(12f), dpi(16f)))
        val name = TextView(this).apply {
            text = c.name
            tag = "name"
            sp(15f)
        }
        b.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12f) })
        styleCategory(b, isCurrent)
        return b
    }

    private fun styleCategory(b: View, isCurrent: Boolean) {
        val radii = floatArrayOf(dp(6f), dp(6f), dp(6f), dp(6f), dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
        b.findViewWithTag<View>("mark")?.background = GradientDrawable().apply {
            cornerRadii = radii
            if (isCurrent) setColor(accent) else {
                setColor(Color.TRANSPARENT)
                setStroke(dpi(1f), pal.tileStroke)
            }
        }
        b.findViewWithTag<TextView>("name")?.apply {
            typeface = Fonts.body(this@SettingsActivity, if (isCurrent) 500 else 400)
            setTextColor(if (isCurrent) pal.text else pal.label)
        }
    }

    private fun refreshMenuMarks() {
        for (i in 0 until menu.childCount) styleCategory(menu.getChildAt(i), i == current)
    }

    /** Fills the right side with one category's options. */
    private fun showPane(index: Int) {
        pane.removeAllViews()
        paneScroll.scrollTo(0, 0)
        shownPane = index
        val c = categories[index]
        pane.addView(TextView(this).apply {
            text = c.name
            typeface = Fonts.display(this@SettingsActivity, 500)
            sp(22f)
            includeFontPadding = false
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })
        pane.addView(noteText(this, pal, c.about), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(6f)
            bottomMargin = dpi(14f)
        })
        when (c.name) {
            "Look" -> lookPane()
            "Home screen" -> homeScreenPane()
            "Athan" -> athanPane()
            "Apps" -> appsPane()
            "Home button" -> homeButtonPane()
            "Updates" -> updatesPane()
            "Remote" -> remotePane()
            "About" -> aboutPane()
        }
    }

    // ---------------- the categories ----------------

    private fun lookPane() {
        val theme = Look.theme(prefs)
        row("theme", "Theme", theme.name, rerender = false) { pickTheme() }
        note(theme.blurb)
        row("accent", "Highlight colour", Look.accentName(prefs), swatch = accent) {
            prefs.accent = next(theme.accents, prefs.accent)
        }
        if (theme.palettes.size > 1) {
            row("palette", "Background", pal.name, swatch = pal.tile) {
                prefs.palette = next(theme.palettes, prefs.palette)
            }
        }
        if (theme.layout == Layout.CLASSIC || theme.layout == Layout.DOORWAY) {
            row("arches", "Arches in the background", if (prefs.arches) "On" else "Off") { prefs.arches = !prefs.arches }
        }
    }

    private fun homeScreenPane() {
        val theme = Look.theme(prefs)
        group("Top of the screen")
        val greetings = listOf("Arabic and English", "Arabic", "English", "Off")
        row("greeting", "Greeting", greetings[prefs.greeting.coerceIn(0, 3)]) {
            prefs.greeting = next(greetings, prefs.greeting)
        }
        row("clock", "Clock", if (prefs.clock24) "24-hour" else "12-hour") { prefs.clock24 = !prefs.clock24 }
        row("date", "Date", if (prefs.showDate) "Shown" else "Hidden") { prefs.showDate = !prefs.showDate }

        group("Apps on the home screen")
        val sizes = listOf("Compact", "Normal", "Large")
        row("size", "Size", sizes[prefs.tileSize.coerceIn(0, 2)]) { prefs.tileSize = next(sizes, prefs.tileSize) }
        val styles = listOf("App icon", "Letter")
        row("style", "Inside each door", styles[prefs.iconStyle.coerceIn(0, 1)]) {
            prefs.iconStyle = next(styles, prefs.iconStyle)
        }
        if (theme.layout != Layout.DOORWAY && theme.layout != Layout.LANTERN) {
            // Doorway and Lantern name the selected app under the doors instead.
            val names = listOf("Always", "Only on the selected app", "Never")
            row("names", "App names", names[prefs.nameMode.coerceIn(0, 2)]) { prefs.nameMode = next(names, prefs.nameMode) }
        }

        group("Now playing")
        val npAccess = NowPlayingWatcher.hasAccess(this)
        val npValue = when {
            !prefs.nowPlaying -> "Off"
            !npAccess -> "Needs one permission"
            else -> "On"
        }
        row("nowplaying", "Show what's playing in the background", npValue, rerender = false) {
            if (prefs.nowPlaying && !npAccess) explainNowPlayingAccess() else {
                prefs.nowPlaying = !prefs.nowPlaying
                render()
            }
        }
    }

    private fun appsPane() {
        val hiddenCount = prefs.hidden.size
        row("hidden", "Hidden apps", if (hiddenCount == 0) "None" else "$hiddenCount hidden", rerender = false) {
            editHidden()
        }
        row("reset", "Reset the home row", "", rerender = false) { confirmReset() }
        note("To add an app to the home screen, hide it or uninstall it, hold OK on it in All apps.")
    }

    private fun homeButtonPane() {
        val handled = HomeSetup.homeHandled(this)
        row("home", "Home button opens eTV", if (handled) "Yes" else "Set up", rerender = false) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        note(
            if (handled) "Home already brings you here. The setup page shows how, and can change it."
            else "Your TV still opens its own home screen. The setup page walks you through it.",
        )
    }

    private fun updatesPane() {
        val release = Updater.available
        val updateValue = when {
            Updater.busy -> Updater.status
            release != null && (Updater.status.isEmpty() || Updater.status.endsWith("is ready")) ->
                "Install ${release.title}"
            release != null -> Updater.status // a failed download or install says to try again
            Updater.status.isNotEmpty() -> "${Updater.status}  ·  Check again"
            prefs.updateCode > 0 -> "A new version is ready  ·  Check"
            else -> "Check now"
        }
        row("update", "New version", updateValue, rerender = false) {
            when {
                Updater.busy -> Unit
                release != null -> Updater.install(this, release)
                else -> Updater.check(this)
            }
        }
        row("autoupdate", "Look for new versions by itself", if (prefs.autoUpdate) "Twice a day" else "Off") {
            prefs.autoUpdate = !prefs.autoUpdate
        }
        note("You have eTV ${Updater.installedName(this)}, build ${Updater.installedCode(this)}.")
    }

    private fun remotePane() {
        val shortcuts = listOf(
            "1 – 9" to "Open the app in that spot on the home row",
            "0  or  Red" to "All apps",
            "Green  or  Sources" to "Inputs",
            "Yellow" to "Search",
            "Blue" to "This page",
            "Options  or  Info" to "Options for the selected app",
            "Hold OK" to "Options for the selected app",
            "Home (from an app)" to "Back to exactly where you were",
            "Home again,  or  Back" to "The app in the middle of the row",
            "OK, Back  or  Stop" to "During the athan: end it",
        )
        for ((key, what) in shortcuts) shortcutLine(key, what)
    }

    private fun aboutPane() {
        row("system", "Android TV settings", "", rerender = false) {
            AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS))
        }
        note("eTV ${Updater.installedName(this)}  ·  build ${Updater.installedCode(this)}\ngithub.com/${Updater.REPO}")
    }

    // ---------------- building blocks ----------------

    internal fun group(text: String) {
        pane.addView(sectionLabel(this, pal, text), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(18f)
            bottomMargin = dpi(4f)
            marginStart = dpi(18f)
        })
    }

    internal fun note(text: String) {
        pane.addView(noteText(this, pal, text), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(6f)
            bottomMargin = dpi(4f)
            marginStart = dpi(18f)
            marginEnd = dpi(18f)
        })
    }

    private fun shortcutLine(key: String, what: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(18f), dpi(5f), dpi(18f), dpi(5f))
        }
        val k = TextView(this).apply {
            text = key
            typeface = Fonts.body(this@SettingsActivity, 500)
            sp(13.5f)
            setTextColor(accent)
        }
        val w = TextView(this).apply {
            text = what
            typeface = Fonts.body(this@SettingsActivity, 300)
            sp(13.5f)
            setTextColor(pal.label)
        }
        line.addView(k, LinearLayout.LayoutParams(dpi(180f), -2))
        line.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        pane.addView(line, LinearLayout.LayoutParams(-1, -2))
    }

    internal fun row(
        key: String,
        label: String,
        value: String,
        swatch: Int? = null,
        rerender: Boolean = true,
        onPress: () -> Unit,
    ) {
        val r = settingRow(this, pal, accent, label, value, swatch)
        r.tag = key
        r.setOnClickListener {
            focusKey = key
            onPress()
            if (rerender) render()
        }
        r.setOnFocusChangeListener { _, has -> if (has && !rebuilding) focusKey = key }
        pane.addView(r, LinearLayout.LayoutParams(-1, dpi(52f)).apply { topMargin = dpi(4f) })
    }

    // ---------------- moving between the two sides ----------------

    /** The row was pressed away (the option it belonged to is gone): the option now in its place. */
    private fun nearestOption(index: Int): View? {
        if (index < 0) return null
        val rows = (0 until pane.childCount).map { pane.getChildAt(it) }
        return rows.drop(index).firstOrNull { it.isFocusable } ?: rows.lastOrNull { it.isFocusable }
    }

    private fun firstOption(): View? =
        (0 until pane.childCount).map { pane.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun enterPane() {
        firstOption()?.requestFocus()
    }

    private fun inMenu(v: View?) = v != null && v.parent === menu
    private fun inPane(v: View?) = v != null && v.parent === pane

    /**
     * Right from a category goes into its options; Left from an option comes back to its category.
     * Up / Down stop at the ends of each side.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (inMenu(f)) {
                    enterPane()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (inPane(f)) {
                    menu.getChildAt(current)?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                    val group: ViewGroup? = when {
                        inMenu(f) -> menu
                        inPane(f) -> pane
                        else -> null
                    }
                    if (group != null && f != null && FocusFinder.getInstance().findNextFocus(group, f, dir) == null) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back from the options returns to the categories; from the categories it closes.
        if (inPane(currentFocus)) {
            menu.getChildAt(current)?.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------- dialogs ----------------

    private fun editHidden() {
        val all = AppRepo.all(this)
        val hidden = prefs.hidden
        val apps = all.filter { it.pkg in hidden }
        if (apps.isEmpty()) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Hidden apps")
                .setMessage("Nothing is hidden. In All apps, hold OK on an app and choose \"Hide from All apps\".")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { true }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Untick to show again")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                val keep = HashSet(hidden)
                apps.forEachIndexed { i, a -> if (!checked[i]) keep.remove(a.pkg) }
                prefs.hidden = keep
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The card needs notification access; TVs hide that switch, so offer both ways to give it. */
    internal fun explainNowPlayingAccess() {
        val canOpen = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .resolveActivity(packageManager) != null
        val msg = "To show what's playing, Android needs to let eTV see media from other apps " +
            "(\"notification access\"). eTV only reads what's playing; it ignores every notification.\n\n" +
            (if (canOpen) "Choose Open settings and switch on \"eTV Now playing\".\n\nOr, from your Mac:\n"
            else "This TV hides that switch, so give it once from your Mac:\n") +
            "adb shell cmd notification allow_listener app.etv.launcher/app.etv.launcher.MediaListener"
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("One permission for Now playing")
            .setMessage(msg)
            .setNegativeButton("Turn it off") { _, _ ->
                prefs.nowPlaying = false
                render()
            }
            .setNeutralButton("Close", null)
        if (canOpen) {
            b.setPositiveButton("Open settings") { _, _ ->
                AppRepo.tryStart(this, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        b.show()
    }

    /** Every theme with a line about it; picking one shows it at once. */
    private fun pickTheme() {
        val labels = Look.themes.map { "${it.name}  —  ${it.blurb}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, prefs.theme.coerceIn(0, Look.themes.size - 1)) { d, which ->
                if (which != prefs.theme) {
                    // Each theme has its own colours: start from its first ones.
                    prefs.accent = 0
                    prefs.palette = 0
                    prefs.theme = which
                }
                d.dismiss()
                focusKey = "theme"
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Reset the home row?")
            .setMessage("Your home row goes back to the first TV apps on this device.")
            .setPositiveButton("Reset") { _, _ -> prefs.resetHomeRow() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
