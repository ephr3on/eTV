package app.etv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The Home button helper.
 *
 * Many TVs (Philips included) open their own home screen when Home is pressed, whatever the
 * default is. The helper handles that in two ways:
 *
 * 1. It catches the Home button itself, before the TV acts on it, and opens eTV. The TV's own
 *    home screen never opens. Every other button passes straight through, untouched.
 * 2. Backup, for TVs that don't pass Home to helpers: it watches which app opens, and when that
 *    is the TV's own home screen, it switches straight back to eTV.
 *
 * It never reads what is on screen, and it keeps and sends nothing.
 */
class HomeButtonService : AccessibilityService() {

    private var homes: Set<String> = emptySet()
    private var homesLoadedAt = 0L
    private var lastSwitch = 0L
    private var lastKeyCatch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        homesLoadedAt = 0L
        // Just after the TV starts, newer Android versions stop the boot receiver from opening eTV.
        // The helper is allowed to, so it does it here.
        val prefs = Prefs(this)
        if (prefs.homeHelper && SystemClock.elapsedRealtime() < 4 * 60_000L) switchToEtv(force = true)
    }

    /** Way 1: the Home button itself. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val prefs = Prefs(this)
        // Paused, or learning the TV's home screen: let the TV have Home as usual.
        if (!prefs.homeHelper || prefs.learningHome) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastKeyCatch = SystemClock.elapsedRealtime()
            closeSystemPanels()
            switchToEtv(force = true)
            prefs.lastHomeVia = Prefs.HOME_VIA_KEY
        }
        // Swallow the press, its repeats and its release, so the TV never sees Home.
        return true
    }

    /** Way 2: the TV's own home screen opened anyway. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return

        val prefs = Prefs(this)

        // "Teach eTV": the next full screen that isn't eTV is the TV's own home.
        if (prefs.learningHome) {
            if (HomeSetup.isSettingsLike(pkg) || isKeyboardOrDialog(event)) return
            prefs.extraHomes = prefs.extraHomes + pkg
            prefs.learnUntil = 0L
            homesLoadedAt = 0L
            switchToEtv(force = true)
            return
        }

        if (!prefs.homeHelper) return
        if (pkg !in currentHomes()) return

        // Opened on purpose from eTV (e.g. from All apps): leave it alone.
        val now = SystemClock.elapsedRealtime()
        if (pkg == AppRepo.lastLaunchPkg && now - AppRepo.lastLaunchAt < 4_000L) return

        switchToEtv(force = false)
        // Only record this when the key wasn't caught a moment ago (then it's just the TV catching up).
        if (now - lastKeyCatch > 1_500L) prefs.lastHomeVia = Prefs.HOME_VIA_SCREEN
    }

    private fun isKeyboardOrDialog(event: AccessibilityEvent): Boolean {
        val cls = event.className?.toString() ?: return false
        return cls.contains("Dialog") || cls.contains("InputMethod") || cls.contains("Toast")
    }

    private fun currentHomes(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - homesLoadedAt > 30_000L) {
            homes = HomeSetup.otherHomes(this)
            homesLoadedAt = now
        }
        return homes
    }

    /** Closes the TV's own menus and panels the way the real Home button would. */
    private fun closeSystemPanels() {
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {
            // Not allowed on some Android versions; nothing else depends on it.
        }
    }

    /**
     * [force] is for a real Home press: always go to eTV. Otherwise a short pause stops eTV and the
     * TV's own home screen from bouncing between each other.
     */
    private fun switchToEtv(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSwitch < 350L) return
        lastSwitch = now
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(MainActivity.EXTRA_FROM_HOME_KEY, true)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}
}
