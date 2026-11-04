package app.etv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * The Home button helper.
 *
 * Many TVs (Philips included) open their own home screen when Home is pressed, whatever the
 * default is. This service only listens for "a new screen opened" events. When that screen is
 * the TV's own home, it brings eTV straight back. It never reads what is on screen.
 */
class HomeButtonService : AccessibilityService() {

    private var homes: Set<String> = emptySet()
    private var homesLoadedAt = 0L
    private var lastSwitch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        homesLoadedAt = 0L
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
        switchToEtv()
    }

    private fun isKeyboardOrDialog(event: AccessibilityEvent): Boolean {
        val cls = event.className?.toString() ?: return false
        return cls.contains("Dialog") || cls.contains("InputMethod") || cls.contains("Toast")
    }

    private fun currentHomes(): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (now - homesLoadedAt > 30_000L) {
            homes = HomeSetup.otherHomes(this)
            homesLoadedAt = now
        }
        return homes
    }

    private fun switchToEtv() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSwitch < 800L) return
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
