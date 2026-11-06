package app.etv.launcher

import android.app.Activity
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
import android.widget.Toast

/** "Home button": makes the remote's Home key open eTV, with the TV's own status shown live. */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
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
            setPadding(0, dpi(40f), 0, dpi(48f))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, LinearLayout.LayoutParams(dpi(600f), -2))
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
        list.removeAllViews()

        val isDefault = HomeSetup.isDefaultHome(this)
        val helperOn = HomeSetup.helperEnabled(this)
        val handled = isDefault || (helperOn && prefs.homeHelper)

        val title = TextView(this).apply {
            text = "Home button"
            typeface = Fonts.display(this@SetupActivity, 500)
            sp(30f)
            setTextColor(pal.text)
        }
        list.addView(title)

        val status = TextView(this).apply {
            text = if (handled) "Home opens eTV" else "Home still opens the TV's own home screen"
            typeface = Fonts.body(this@SetupActivity, 500)
            sp(16f)
            setTextColor(if (handled) accent else pal.label)
        }
        list.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6f) })

        // How the last Home press actually reached eTV: the quickest way to see what's going on.
        if (helperOn && prefs.homeHelper) {
            val how = when (prefs.lastHomeVia) {
                Prefs.HOME_VIA_KEY ->
                    "The helper catches Home as soon as you press it, so the TV's own home screen never opens."
                Prefs.HOME_VIA_SCREEN ->
                    "This TV doesn't hand the Home button to the helper, so its own home screen can show for a " +
                        "moment before eTV comes back. If Home ever stays there, try Step 2 below, or " +
                        "turn the helper off and on again in Accessibility."
                else -> "Press Home on the remote once to test it."
            }
            add(noteText(this, pal, how), top = 6f)
        }

        add(
            noteText(
                this, pal,
                "Philips and many other TVs open their own home screen when you press Home, even after you " +
                    "choose another one. The eTV Home button helper catches the Home button before the TV does " +
                    "and opens eTV. Every other button goes straight through. It never reads what's on screen, " +
                    "and it keeps and sends nothing.",
            ),
            top = 14f,
        )

        // Step 1: the helper.
        add(sectionLabel(this, pal, "Step 1"), top = 24f)
        val helperRow = settingRow(
            this, pal, accent,
            "Turn on the Home button helper",
            if (helperOn) "On" else "Off",
        )
        clickable(helperRow, "helper") { openAccessibility() }
        add(helperRow, top = 6f, height = 52f)
        if (!helperOn) {
            add(
                noteText(
                    this, pal,
                    "In the list that opens, choose \"eTV Home button\", switch it on and confirm. " +
                        "On Philips you'll usually find it under Accessibility > Services. Then press Back to come here.",
                ),
                top = 8f,
            )
        }

        if (helperOn) {
            val pauseRow = settingRow(
                this, pal, accent,
                "Switch Home back to eTV",
                if (prefs.homeHelper) "Yes" else "Paused",
            )
            clickable(pauseRow, "pause") {
                prefs.homeHelper = !prefs.homeHelper
                render()
            }
            add(pauseRow, top = 4f, height = 52f)

            // Step 2: only needed if Home still lands somewhere else.
            add(sectionLabel(this, pal, "Step 2 · only if Home still doesn't open eTV"), top = 24f)
            val learnRow = settingRow(this, pal, accent, "Teach eTV your TV's home screen", "")
            clickable(learnRow, "learn") {
                prefs.learnUntil = System.currentTimeMillis() + 45_000L
                Toast.makeText(this, "Now press Home on the remote", Toast.LENGTH_LONG).show()
            }
            add(learnRow, top = 6f, height = 52f)
            add(
                noteText(
                    this, pal,
                    "Choose this, then press Home within 45 seconds. eTV remembers the screen that opens " +
                        "and switches away from it from now on.",
                ),
                top = 8f,
            )
            val learnt = prefs.extraHomes
            if (learnt.isNotEmpty()) {
                val forget = settingRow(this, pal, accent, "Forget learnt home screens", "${learnt.size}")
                clickable(forget, "forget") {
                    prefs.extraHomes = emptySet()
                    render()
                }
                add(forget, top = 6f, height = 52f)
            }
        }

        // Other ways.
        add(sectionLabel(this, pal, "Other ways"), top = 24f)
        if (HomeSetup.hasHomePicker(this)) {
            val pick = settingRow(this, pal, accent, "Choose eTV as the Home app", if (isDefault) "eTV" else "")
            clickable(pick, "picker") {
                AppRepo.tryStart(this, Intent(Settings.ACTION_HOME_SETTINGS))
            }
            add(pick, top = 6f, height = 52f)
        }
        add(
            noteText(
                this, pal,
                "If the TV ever asks which app to use for Home, choose eTV and then Always.\n" +
                    "With a computer: run make-default.sh from the eTV folder (see the README). " +
                    "It sets eTV as the Home app and turns the helper on in one go.",
            ),
            top = 8f,
        )

        val done = settingRow(this, pal, accent, "Done", "")
        clickable(done, "done") { finish() }
        add(done, top = 24f, height = 52f)

        val target = (0 until list.childCount).map { list.getChildAt(it) }
            .firstOrNull { it.tag == focusKey && it.isFocusable }
            ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
        target?.post { target.requestFocus() }
    }

    private fun clickable(v: View, key: String, onPress: () -> Unit) {
        v.tag = key
        v.setOnClickListener {
            focusKey = key
            onPress()
        }
    }

    private fun add(v: View, top: Float, height: Float? = null) {
        val h = if (height == null) -2 else dpi(height)
        list.addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = dpi(top) })
    }

    private fun openAccessibility() {
        val ok = AppRepo.tryStart(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) ||
            AppRepo.tryStart(this, Intent(Settings.ACTION_SETTINGS))
        if (!ok) Toast.makeText(this, "Open Settings > Accessibility on the TV", Toast.LENGTH_LONG).show()
    }
}
