package app.etv.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Pictures of Customise and its panels, as the TV shows them (1920 × 1080).
 * Not a real test: it's how the layout can be looked at without a TV.
 * Run: ./gradlew testDebugUnitTest --tests '*SettingsShots*'  →  app/build/shots/
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsShots {

    private val out = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }

    private fun save(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The settings window with the newest panel over it, as one picture. */
    private fun saveWithSheet(act: SettingsActivity, sheet: Sheet, name: String) {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (v in listOf(act.window.decorView, sheet.window!!.decorView)) {
            v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1920, 1080)
            v.draw(canvas)
        }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setUp(theme: Int) {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.theme = theme
        p.accent = 0
        p.palette = 0
        p.setupShown = true
        p.athanSetupShown = true
        p.place = Place("Hannover, Lower Saxony, Germany", 52.37052, 9.73322, "Europe/Berlin")
        p.prayerSource = PrayerStore.SRC_DEVICE
        p.athanOn = true
        p.autoUpdate = false
        p.setOffset(Prayer.MAGHRIB, 3)
    }

    private fun open(category: Int): SettingsActivity {
        val ctx = RuntimeEnvironment.getApplication()
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CATEGORY, category)
        val c = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun pages() {
        for (theme in listOf(0, 2)) {
            for ((cat, name) in listOf(0 to "look", 1 to "home", 2 to "athan", 3 to "apps")) {
                try {
                    setUp(theme)
                    val act = open(cat)
                    // Into the options, so a selected row shows.
                    act.focusKey = when (name) {
                        "look" -> "theme"
                        "home" -> "date"
                        "athan" -> "athan"
                        else -> "hidden"
                    }
                    act.render()
                    idle()
                    save(act.window.decorView, "settings-$name-theme$theme")
                    if (name == "athan") {
                        for (key in listOf("othersound", "prayer_asr", "volume", "hijri")) {
                            act.focusKey = key
                            act.render()
                            idle()
                            save(act.window.decorView, "settings-athan-$key-theme$theme")
                        }
                    }
                    act.finish()
                } catch (e: Throwable) {
                    File(out, "error-settings-$name-$theme.txt").writeText(e.stackTraceToString())
                }
            }
        }
    }

    /** Lantern's top line with some buttons switched off, in each way of showing the prayer times. */
    @Test
    fun fewerButtons() {
        val lantern = Look.themes.indexOfFirst { it.layout == Layout.LANTERN }
        val doorway = Look.themes.indexOfFirst { it.layout == Layout.DOORWAY }
        for ((theme, style) in listOf(lantern to 0, lantern to 2, doorway to 0)) {
            try {
                setUp(theme)
                val p = Prefs(RuntimeEnvironment.getApplication())
                p.athanOn = false
                p.lanternPrayers = style
                p.hiddenButtons = setOf("inputs")
                val c = Robolectric.buildActivity(MainActivity::class.java).setup()
                idle()
                save(c.get().window.decorView, "buttons-theme$theme-style$style")
                c.pause().stop().destroy()
                p.hiddenButtons = emptySet()
            } catch (e: Throwable) {
                File(out, "error-buttons-$theme-$style.txt").writeText(e.stackTraceToString())
            }
        }
    }

    @Test
    fun panels() {
        for (theme in listOf(0, 2)) {
            try {
                setUp(theme)
                val act = open(2)
                act.focusKey = "fajrsound"
                act.render()
                idle()

                act.pickSound(listOf(Prayer.FAJR))
                idle()
                var sheet = ShadowDialog.getLatestDialog() as Sheet
                saveWithSheet(act, sheet, "panel-fajr-theme$theme")
                // As it looks while one plays (the player itself doesn't run here).
                sheet.row("snd:makkah_mulla")?.requestFocus()
                idle()
                sheet.setDetail("snd:makkah_mulla", "▶  Playing  0:14 / 2:51  ·  OK chooses it", sheet.accent)
                saveWithSheet(act, sheet, "panel-fajr-playing-theme$theme")
                sheet.setDetail("snd:cairo_basit", "Fetching it to play…  (once; it's kept on the TV)", sheet.accent)
                sheet.row("snd:cairo_basit")?.requestFocus()
                idle()
                saveWithSheet(act, sheet, "panel-fajr-fetching-theme$theme")
                sheet.dismiss()
                idle()

                act.pickDua()
                idle()
                sheet = ShadowDialog.getLatestDialog() as Sheet
                saveWithSheet(act, sheet, "panel-dua-theme$theme")
                sheet.dismiss()
                idle()

                act.editPrayer(Prayer.MAGHRIB)
                idle()
                sheet = ShadowDialog.getLatestDialog() as Sheet
                saveWithSheet(act, sheet, "panel-maghrib-theme$theme")
                sheet.row("offset")?.requestFocus()
                idle()
                saveWithSheet(act, sheet, "panel-maghrib-offset-theme$theme")
                sheet.dismiss()
                idle()
                act.finish()
            } catch (e: Throwable) {
                File(out, "error-panels-$theme.txt").writeText(e.stackTraceToString())
            }
        }
    }
}
