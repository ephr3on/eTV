package app.etv.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Pictures of the home screen in every theme, as the TV shows it (1920 × 1080, 960 × 540 dp).
 * Not a real test: it's how the layout can be looked at without a TV.
 * Run: ./gradlew testDebugUnitTest --tests '*HomeShots*'  →  app/build/shots/
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShots {

    private val out = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }

    private fun fakeApps() {
        val ctx = RuntimeEnvironment.getApplication()
        val spm = shadowOf(ctx.packageManager)
        val apps = listOf(
            "com.google.android.youtube.tv" to "YouTube", "com.netflix.ninja" to "Netflix",
            "com.liskovsoft.smarttubetv" to "SmartTube", "com.spotify.tv.android" to "Spotify",
            "org.videolan.vlc" to "VLC", "com.android.chrome" to "Chrome", "com.plexapp.android" to "Plex",
        )
        for ((pkg, label) in apps) {
            try {
                val comp = ComponentName(pkg, "$pkg.Main")
                val ai = spm.addActivityIfNotPresent(comp)
                ai.nonLocalizedLabel = label
                ai.applicationInfo?.nonLocalizedLabel = label
                spm.addIntentFilterForActivity(comp, IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                })
            } catch (e: Exception) {
                println("fake app $pkg: $e")
            }
        }
    }

    private fun save(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Where everything is, in dp, to check alignment and symmetry by numbers.
        val sb = StringBuilder("dp: left top right bottom | centre-x  (screen 960 x 540, centre 480)\n")
        val content = v.findViewById<View>(android.R.id.content) as ViewGroup
        fun walk(g: View, depth: Int) {
            if (g.visibility != View.VISIBLE || g.width == 0) return
            val loc = IntArray(2)
            g.getLocationInWindow(loc)
            val l = loc[0] / 2f
            val t = loc[1] / 2f
            val r = l + g.width / 2f
            val b = t + g.height / 2f
            val label = (g as? TextView)?.text?.toString()?.replace("\n", " / ")?.take(40) ?: ""
            sb.append("  ".repeat(depth)).append(g.javaClass.simpleName).append(' ')
                .append("%.0f %.0f %.0f %.0f | %.0f".format(l, t, r, b, (l + r) / 2f))
                .append(if (label.isNotEmpty()) "  \"$label\"" else "").append('\n')
            if (g is ViewGroup && depth < 3 && g !is android.widget.HorizontalScrollView) {
                for (i in 0 until g.childCount) walk(g.getChildAt(i), depth + 1)
            }
        }
        for (i in 0 until content.childCount) walk(content.getChildAt(i), 0)
        File(out, "bounds-$name.txt").writeText(sb.toString())
    }

    /** Lantern's ways of showing the prayer times, side by side. */
    @Test
    fun lanternPrayers() {
        fakeApps()
        val ctx = RuntimeEnvironment.getApplication()
        val lantern = Look.themes.indexOfFirst { it.layout == Layout.LANTERN }
        for (style in 0..3) {
            for (lang in listOf(0, 2)) {
                try {
                    val p = Prefs(ctx)
                    p.theme = lantern
                    p.setupShown = true
                    p.athanSetupShown = true
                    p.place = Place("Hannover, Lower Saxony, Germany", 52.37052, 9.73322, "Europe/Berlin")
                    p.prayerSource = PrayerStore.SRC_DEVICE
                    p.athanOn = false
                    p.greeting = lang
                    p.lanternPrayers = style
                    val c = Robolectric.buildActivity(MainActivity::class.java).setup()
                    shadowOf(Looper.getMainLooper()).idle()
                    save(c.get().window.decorView, "lantern-style$style-lang$lang")
                    c.pause().stop().destroy()
                } catch (e: Throwable) {
                    File(out, "error-lantern-$style-$lang.txt").writeText(e.stackTraceToString())
                }
            }
        }
    }

    @Test
    fun home() {
        fakeApps()
        val ctx = RuntimeEnvironment.getApplication()
        val langs = (System.getProperty("shots.langs") ?: "0,2").split(',').map { it.trim().toInt() }
        for ((i, t) in Look.themes.withIndex()) {
            for (lang in langs) {
                try {
                    val p = Prefs(ctx)
                    p.theme = i
                    p.setupShown = true
                    p.athanSetupShown = true
                    p.place = Place("Hannover, Lower Saxony, Germany", 52.37052, 9.73322, "Europe/Berlin")
                    p.prayerSource = PrayerStore.SRC_DEVICE
                    p.athanOn = false // no downloads, no alarms
                    p.greeting = lang
                    p.lanternPrayers = 0
                    val c = Robolectric.buildActivity(MainActivity::class.java).setup()
                    shadowOf(Looper.getMainLooper()).idle()
                    save(c.get().window.decorView, "home-${i}-${t.name.lowercase()}-lang$lang")
                    c.pause().stop().destroy()
                } catch (e: Throwable) {
                    File(out, "error-${i}-$lang.txt").writeText(e.stackTraceToString())
                }
            }
        }
    }
}
