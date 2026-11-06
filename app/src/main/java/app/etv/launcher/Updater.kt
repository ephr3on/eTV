package app.etv.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates eTV from GitHub.
 *
 * Every build of main that GitHub signs with the eTV key is published as a release named
 * "build-<version code>" with eTV.apk attached. eTV asks GitHub for the latest release, and when its
 * version code is higher than the installed one, it can download it and hand it to Android to
 * install (Android asks you to confirm). Nothing else is ever sent or fetched.
 */
object Updater {
    const val REPO = "ephr3on/eTV"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    class Release(val code: Long, val title: String, val apkUrl: String, val size: Long)

    /** A newer release than the installed one, once a check has found it. */
    @Volatile var available: Release? = null
        private set

    /** What the updater is doing, in words, for the Customise screen. */
    @Volatile var status: String = ""
        private set

    @Volatile var busy = false
        private set

    /** Called on the main thread whenever [status] or [available] changes. */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun set(newStatus: String) {
        status = newStatus
        main.post { onChange?.invoke() }
    }

    /** Checks quietly in the background at most twice a day, if automatic checks are on. */
    fun checkIfDue(context: Context, done: () -> Unit = {}) {
        val prefs = Prefs(context)
        if (!prefs.autoUpdate || busy) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        check(context, done)
    }

    /** Asks GitHub for the latest release. [done] runs on the main thread afterwards. */
    fun check(context: Context, done: () -> Unit = {}) {
        if (busy) return
        val app = context.applicationContext
        busy = true
        set("Checking…")
        Thread {
            val result = try {
                val json = JSONObject(get(LATEST))
                val tag = json.optString("tag_name")
                val code = tag.removePrefix("build-").toLongOrNull()
                val assets = json.optJSONArray("assets")
                var url: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                if (code == null || url.isNullOrEmpty()) {
                    null to "No update published yet"
                } else {
                    val rel = Release(code, json.optString("name").ifEmpty { tag }, url, size)
                    if (code > installedCode(app)) rel to "${rel.title} is ready" else null to "Up to date"
                }
            } catch (e: HttpStatus) {
                null to when (e.code) {
                    404 -> "No update published yet"
                    403, 429 -> "GitHub is busy, try again later"
                    else -> "GitHub answered ${e.code}"
                }
            } catch (e: Exception) {
                null to "Couldn't reach GitHub. Is the TV online?"
            }
            val prefs = Prefs(app)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            prefs.updateCode = result.first?.code ?: 0L
            available = result.first
            busy = false
            set(result.second)
            main.post(done)
        }.start()
    }

    /** Downloads [release] and asks Android to install it. */
    fun install(activity: Activity, release: Release) {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission(activity)
            return
        }
        val app = activity.applicationContext
        busy = true
        set("Downloading… 0%")
        Thread {
            try {
                val file = File(app.cacheDir, "eTV-update.apk")
                download(release, file)

                // Only ever install eTV itself, and only a newer one.
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
                val code = info?.let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
                }
                if (info?.packageName != app.packageName || code == null || code <= installedCode(app)) {
                    throw IllegalStateException("not a newer eTV")
                }

                set("Installing…")
                Prefs(app).justUpdated = true
                startInstall(app, file)
                busy = false
            } catch (e: Exception) {
                busy = false
                set("Download failed. Try again")
            }
        }.start()
    }

    /** Android asks once whether eTV may install apps; this opens that switch. */
    private fun askForInstallPermission(activity: Activity) {
        val opened = AppRepo.tryStart(
            activity,
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
        ) || AppRepo.tryStart(activity, Intent(Settings.ACTION_SECURITY_SETTINGS))
        Toast.makeText(
            activity,
            if (opened) "Allow eTV to install apps, then press Back and choose Install again"
            else "In the TV's settings, allow eTV to install unknown apps, then try again",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun startInstall(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("eTV.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun installFailed(context: Context, message: String?) {
        Prefs(context).justUpdated = false
        set(if (message.isNullOrBlank()) "Install didn't finish. Try again" else "Install didn't finish: $message")
    }

    private class HttpStatus(val code: Int) : Exception()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "eTV-launcher")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw HttpStatus(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(release: Release, to: File) {
        val c = open(release.apkUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode != 200) throw HttpStatus(c.responseCode)
            val total = c.contentLength.toLong().takeIf { it > 0 } ?: release.size
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 5 != shown / 5) {
                                shown = pct
                                set("Downloading… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

/** Hears back from Android's installer: shows its confirmation screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Updater.installFailed(context, "Android couldn't show the install screen")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // eTV restarts as the new version
            else -> Updater.installFailed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
