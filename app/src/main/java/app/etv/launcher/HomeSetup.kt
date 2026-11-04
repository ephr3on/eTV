package app.etv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Works out who answers the Home button, and whether eTV is in charge of it. */
object HomeSetup {

    /** Stock home screens that are known to hold on to the Home button. */
    private val knownStockHomes = setOf(
        "com.google.android.tvlauncher",          // Android TV Home (Philips, Sony, …)
        "com.google.android.apps.tv.launcherx",   // Google TV
        "com.google.android.leanbacklauncher",    // older Android TV
        "com.google.android.tungsten.setupwraith", // Google TV fallback home
    )

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** True when Android itself sends Home to eTV. */
    fun isDefaultHome(context: Context): Boolean {
        val info = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == context.packageName
    }

    /** True when the eTV Home button helper is switched on in Accessibility. */
    fun helperEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, HomeButtonService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    /** Pressing Home ends up in eTV, one way or the other. */
    fun homeHandled(context: Context): Boolean =
        isDefaultHome(context) || (helperEnabled(context) && Prefs(context).homeHelper)

    /** Every home screen on the TV except eTV. Settings screens are never treated as home. */
    fun otherHomes(context: Context): Set<String> {
        val result = HashSet<String>()
        val found = context.packageManager.queryIntentActivities(homeIntent(), 0)
        for (ri in found) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (ri.priority < -100) continue // boot-time fallback screens
            result.add(pkg)
        }
        result.addAll(knownStockHomes)
        result.addAll(Prefs(context).extraHomes)
        result.remove(context.packageName)
        return result.filterNot { isSettingsLike(it) }.toSet()
    }

    fun isSettingsLike(pkg: String): Boolean =
        pkg == "android" || pkg.contains("settings", ignoreCase = true)

    /** Whether this TV has a "Home app" picker in its settings. */
    fun hasHomePicker(context: Context): Boolean =
        Intent(Settings.ACTION_HOME_SETTINGS).resolveActivity(context.packageManager) != null
}
