package app.etv.launcher

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock

/**
 * Where you were in eTV.
 *
 * Coming back with Home from another app returns you to exactly that place (the same app on the
 * home row or in All apps, or the same dock button). Pressing Home again, while already in eTV,
 * goes to the app in the middle of the home row.
 */
object Nav {
    const val SCREEN_HOME = 0
    const val SCREEN_APPS = 1

    /** The eTV screen you were last on. */
    var screen = SCREEN_HOME

    /** What was selected on the home screen: "app:<package>", "dock:<n>", or null for the app in the middle of the row. */
    var homeKey: String? = null

    /** The app selected in All apps. */
    var appsPkg: String? = null

    /** True once eTV has gone behind another app or screen (until you're back in eTV). */
    var away = false

    /** When eTV last went behind something else (elapsed ms). */
    var leftAt = 0L

    private var visible = 0
    private var loaded = false

    /** Restores the place saved before eTV was last closed (the TV restarted, or Android closed eTV). */
    fun load(prefs: Prefs) {
        if (loaded) return
        loaded = true
        screen = prefs.placeScreen
        homeKey = prefs.placeHome
        appsPkg = prefs.placeApps
        away = homeKey != null || screen == SCREEN_APPS
    }

    fun onScreenStart() {
        visible++
    }

    fun onScreenStop(prefs: Prefs) {
        visible = (visible - 1).coerceAtLeast(0)
        if (visible == 0) {
            // No eTV screen is showing any more: something else took over.
            away = true
            leftAt = SystemClock.elapsedRealtime()
            prefs.savePlace(screen, homeKey, appsPkg)
        }
    }
}

/** Every eTV screen: keeps [Nav] up to date, so moving between eTV's own screens never counts as leaving. */
abstract class EtvActivity : Activity() {
    protected lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Nav.load(prefs)
    }

    override fun onStart() {
        super.onStart()
        Nav.onScreenStart()
    }

    override fun onStop() {
        Nav.onScreenStop(prefs)
        super.onStop()
    }
}
