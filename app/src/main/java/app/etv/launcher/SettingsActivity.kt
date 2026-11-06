package app.etv.launcher

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** "Customise": every option changes with a single press of OK and shows instantly. */
class SettingsActivity : EtvActivity() {

    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var title: TextView
    private var pal: Palette = Look.palettes[0]
    private var accent = 0
    private var focusKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(40f), 0, dpi(56f))
        }
        title = TextView(this).apply {
            text = "Customise"
            typeface = Fonts.display(this@SettingsActivity, 500)
            sp(30f)
        }
        column.addView(title, LinearLayout.LayoutParams(dpi(560f), -2))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, LinearLayout.LayoutParams(dpi(560f), -2).apply { topMargin = dpi(12f) })
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun <T> next(options: List<T>, current: Int): Int = (current + 1) % options.size

    private fun render() {
        pal = Look.palette(prefs)
        accent = Look.accent(prefs)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        root.setBackgroundColor(pal.bg)
        title.setTextColor(pal.text)
        list.removeAllViews()

        section("Home button")
        row("home", "Home button opens eTV", if (HomeSetup.homeHandled(this)) "Yes" else "Set up", rerender = false) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        section("Look")
        val accentName = Look.accents[prefs.accent.coerceIn(0, Look.accents.size - 1)].first
        row("accent", "Highlight colour", accentName, swatch = accent) {
            prefs.accent = next(Look.accents, prefs.accent)
        }
        row("palette", "Background", pal.name, swatch = pal.tile) {
            prefs.palette = next(Look.palettes, prefs.palette)
        }
        row("arches", "Arches in the background", if (prefs.arches) "On" else "Off") { prefs.arches = !prefs.arches }

        section("Top of the screen")
        val greetings = listOf("Arabic and English", "Arabic", "English", "Off")
        row("greeting", "Greeting", greetings[prefs.greeting.coerceIn(0, 3)]) {
            prefs.greeting = next(greetings, prefs.greeting)
        }
        row("clock", "Clock", if (prefs.clock24) "24-hour" else "12-hour") { prefs.clock24 = !prefs.clock24 }
        row("date", "Date", if (prefs.showDate) "Shown" else "Hidden") { prefs.showDate = !prefs.showDate }

        section("Apps")
        val sizes = listOf("Compact", "Normal", "Large")
        row("size", "Arch size", sizes[prefs.tileSize.coerceIn(0, 2)]) { prefs.tileSize = next(sizes, prefs.tileSize) }
        val styles = listOf("App icon", "Letter")
        row("style", "Inside each arch", styles[prefs.iconStyle.coerceIn(0, 1)]) {
            prefs.iconStyle = next(styles, prefs.iconStyle)
        }
        val names = listOf("Always", "Only on the selected app", "Never")
        row("names", "App names", names[prefs.nameMode.coerceIn(0, 2)]) { prefs.nameMode = next(names, prefs.nameMode) }
        val hiddenCount = prefs.hidden.size
        row("hidden", "Hidden apps", if (hiddenCount == 0) "None" else "$hiddenCount hidden", rerender = false) {
            editHidden()
        }
        row("reset", "Reset the home row", "", rerender = false) { confirmReset() }

        section("Remote shortcuts on the home screen")
        val shortcuts = listOf(
            "1 – 9" to "Open the app in that spot on the home row",
            "0  or  Red" to "All apps",
            "Green  or  Sources" to "Inputs",
            "Yellow" to "Search",
            "Blue" to "This page",
            "Options  or  Info" to "Options for the selected app",
            "Hold OK" to "Options for the selected app",
            "Home (from another app)" to "Back to exactly where you were",
            "Home again,  or  Back" to "The app in the middle of the row",
        )
        for ((key, what) in shortcuts) shortcutLine(key, what)

        section("System")
        row("system", "Android TV settings", "", rerender = false) {
            AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS))
        }

        val footer = noteText(this, pal, "eTV 1.3.1")
        list.addView(footer, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(24f)
            marginStart = dpi(18f)
        })

        // Keep the remote's focus on the row that was just changed.
        val target = (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.tag == focusKey && it.isFocusable }
            ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
        target?.post { target.requestFocus() }
    }

    private fun section(text: String) {
        list.addView(sectionLabel(this, pal, text), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(22f)
            bottomMargin = dpi(6f)
            marginStart = dpi(18f)
        })
    }

    private fun shortcutLine(key: String, what: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(18f), dpi(3f), dpi(18f), dpi(3f))
        }
        val k = TextView(this).apply {
            text = key
            typeface = Fonts.body(this@SettingsActivity, 500)
            sp(13f)
            setTextColor(accent)
        }
        val w = TextView(this).apply {
            text = what
            typeface = Fonts.body(this@SettingsActivity, 300)
            sp(13f)
            setTextColor(pal.label)
        }
        line.addView(k, LinearLayout.LayoutParams(dpi(170f), -2))
        line.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(line, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(
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
        list.addView(r, LinearLayout.LayoutParams(-1, dpi(52f)).apply { topMargin = dpi(4f) })
    }

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

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Reset the home row?")
            .setMessage("Your home row goes back to the first TV apps on this device.")
            .setPositiveButton("Reset") { _, _ -> prefs.resetHomeRow() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
