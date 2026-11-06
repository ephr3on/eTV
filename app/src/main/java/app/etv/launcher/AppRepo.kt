package app.etv.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast

class AppEntry(
    val pkg: String,
    val cls: String,
    val label: String,
    /** True when the app has a TV (Leanback) entry point. */
    val isTv: Boolean,
) {
    val component: ComponentName get() = ComponentName(pkg, cls)

    val monogram: String
        get() = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
}

/** Lists launchable apps once and keeps them (and their icons) in memory until something changes. */
object AppRepo {
    private var cache: List<AppEntry>? = null
    private val icons = HashMap<String, Drawable>()

    /** Goes up whenever apps are installed, removed or changed, so screens know to refresh. */
    @Volatile var version = 0
        private set

    fun invalidate() {
        version++
        cache = null
        icons.clear()
    }

    /**
     * Re-reads the list of apps. Returns true only when something eTV shows changed: an app added or
     * removed, renamed, or (for packages in [updated]) possibly a new icon.
     */
    fun refresh(context: Context, updated: Set<String>): Boolean {
        val old = cache
        cache = null
        val fresh = all(context)
        val same = old != null && old.size == fresh.size && old.indices.all { i ->
            val a = old[i]
            val b = fresh[i]
            a.pkg == b.pkg && a.cls == b.cls && a.label == b.label && a.isTv == b.isTv
        }
        val newIcons = updated.filter { pkg -> fresh.any { it.pkg == pkg } }
        newIcons.forEach { icons.remove(it) }
        if (same && newIcons.isEmpty()) return false
        version++
        return true
    }

    fun all(context: Context): List<AppEntry> {
        cache?.let { return it }
        val pm = context.packageManager
        val byPkg = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName) continue
                if (byPkg.containsKey(ai.packageName)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                byPkg[ai.packageName] = AppEntry(ai.packageName, ai.name, label, tv)
            }
        }

        // TV entry points first, so a TV app's proper activity wins over its phone one.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val list = byPkg.values.sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    fun find(context: Context, pkg: String): AppEntry? = all(context).firstOrNull { it.pkg == pkg }

    fun icon(context: Context, app: AppEntry): Drawable {
        icons[app.pkg]?.let { return it }
        val pm = context.packageManager
        val d = try {
            pm.getActivityIcon(app.component)
        } catch (e: Exception) {
            pm.defaultActivityIcon
        }
        icons[app.pkg] = d
        return d
    }

    /** The app eTV opened last, so the Home button helper never switches away from it by mistake. */
    @Volatile var lastLaunchPkg: String? = null
        private set
    @Volatile var lastLaunchAt = 0L
        private set

    fun launch(context: Context, app: AppEntry) {
        lastLaunchPkg = app.pkg
        lastLaunchAt = SystemClock.elapsedRealtime()
        val category = if (app.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!tryStart(context, intent)) {
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(context: Context, pkg: String) {
        tryStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun uninstall(activity: Activity, pkg: String) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        if (!tryStart(activity, intent)) openAppInfo(activity, pkg)
    }

    fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
