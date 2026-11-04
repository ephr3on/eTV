package app.etv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** "Customise": every option changes with a single press of OK and shows instantly. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var title: TextView
    private var pal: Palette = Look.palettes[0]
    private var accent = 0
    private var focusKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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

        section("Look")
        val accentName = Look.accents[prefs.accent.coerceIn(0, Look.accents.size - 1)].first
        row("accent", "Highlight colour", accentName, swatch = accent) {
            prefs.accent = next(Look.accents, prefs.accent)
        }
        row("palette", "Background", pal.name, swatch = pal.tile) {
            prefs.palette = next(Look.palettes, prefs.palette)
        }
        row("arches", "Arches in the background", onOff(prefs.arches)) { prefs.arches = !prefs.arches }

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

        section("System")
        row("default", "Default home app", if (isDefaultHome()) "eTV" else "Not eTV yet", rerender = false) {
            val ok = AppRepo.tryStart(this, Intent(Settings.ACTION_HOME_SETTINGS)) ||
                AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS))
            if (!ok) showDefaultHelp()
        }
        row("system", "Android TV settings", "", rerender = false) {
            AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS))
        }

        val footer = TextView(this).apply {
            text = "eTV 1.0\nOn the home screen: hold OK on an app to move or remove it. Press Menu to come here."
            typeface = Fonts.body(this@SettingsActivity, 300)
            sp(12f)
            setTextColor(pal.muted)
            setLineSpacing(0f, 1.3f)
        }
        list.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(28f) })

        // Keep the remote's focus on the row that was just changed.
        val target = (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.tag == focusKey && it.isFocusable }
            ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
        target?.post { target.requestFocus() }
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun section(text: String) {
        val t = TextView(this).apply {
            this.text = text.uppercase()
            typeface = Fonts.body(this@SettingsActivity, 500)
            sp(11f)
            letterSpacing = 0.18f
            setTextColor(pal.muted)
        }
        list.addView(t, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dpi(22f)
            bottomMargin = dpi(6f)
            marginStart = dpi(18f)
        })
    }

    private fun row(
        key: String,
        label: String,
        value: String,
        swatch: Int? = null,
        rerender: Boolean = true,
        onPress: () -> Unit,
    ) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(18f), 0, dpi(18f), 0)
            isFocusable = true
            isClickable = true
            tag = key
            background = pillBackground(this@SettingsActivity, 14f, android.graphics.Color.TRANSPARENT, pal.tile, accent)
            setOnClickListener {
                focusKey = key
                onPress()
                if (rerender) render()
            }
        }
        val name = TextView(this).apply {
            text = label
            typeface = Fonts.body(this@SettingsActivity, 400)
            sp(15f)
            setTextColor(pal.text)
        }
        r.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        if (swatch != null) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatch)
                    setStroke(dpi(1f), pal.tileStroke)
                }
            }
            r.addView(dot, LinearLayout.LayoutParams(dpi(14f), dpi(14f)).apply { marginEnd = dpi(10f) })
        }
        val v = TextView(this).apply {
            text = value
            typeface = Fonts.body(this@SettingsActivity, 400)
            sp(14f)
            setTextColor(focusColors(accent, pal.muted))
            isDuplicateParentStateEnabled = true
        }
        r.addView(v, LinearLayout.LayoutParams(-2, -2))
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

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun showDefaultHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Make eTV the default")
            .setMessage(
                "This TV has no option for choosing a home app. Press the Home button and pick eTV if asked. " +
                    "On Google TV, see the README for the one-time adb step.",
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
