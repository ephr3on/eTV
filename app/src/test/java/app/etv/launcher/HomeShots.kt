package app.etv.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
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
