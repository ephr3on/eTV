package app.etv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The Home button helper.
 *
 * Many TVs (Philips included) open their own home screen when Home is pressed, whatever the
 * default is. The helper watches which app opens, and when that is the TV's own home screen, it
 * switches straight back to eTV.
 *
 * It deliberately does not intercept remote buttons: on Android TV, doing that makes holding
 * Volume up / down move only one step.
 *
 * It never reads what is on screen, and it keeps and sends nothing.
 */
class HomeButtonService : AccessibilityService() {

    private var homes: Set<String> = emptySet()
    private var homesLoadedAt = 0L
    private var lastSwitch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        homesLoadedAt = 0L
        // Just after the TV starts, newer Android versions stop the boot receiver from opening eTV.
        // The helper is allowed to, so it does it here.
        val prefs = Prefs(this)
        if (prefs.homeHelper && SystemClock.elapsedRealtime() < 4 * 60_000L) switchToEtv()
    }

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
            switchToEtv()
            return
        }

        if (!prefs.homeHelper) return
        if (pkg !in currentHomes()) return

        // Opened on purpose from eTV (e.g. from All apps): leave it alone.
        val now = SystemClock.elapsedRealtime()
        if (pkg == AppRepo.lastLaunchPkg && now - AppRepo.lastLaunchAt < 4_000L) return

        // A short pause stops eTV and the TV's own home screen from bouncing between each other.
        if (now - lastSwitch < 350L) return
        switchToEtv()
        prefs.lastHomeVia = Prefs.HOME_VIA_SCREEN
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

    private fun switchToEtv() {
        lastSwitch = SystemClock.elapsedRealtime()
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
