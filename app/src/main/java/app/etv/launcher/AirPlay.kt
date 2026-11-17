package app.etv.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.flymop.airplaytv.Prefs as AirPrefs
import com.flymop.airplaytv.service.AirPlayService
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * AirPlay in eTV: an always-on receiver, so an iPhone, iPad or Mac on the same Wi-Fi can show its
 * screen, a video, photos or music on the TV at any time.
 *
 * The receiver is the airplay module (flymop/airplay-tv's engine, GPLv3). It runs in a process of
 * its own (":airplay", with [AirPlayActivity]), so nothing that happens in its native code can take
 * the home screen down. This is the glue on eTV's side: starting it, the athan, and the settings.
 */
object AirPlay {

    /** The receiver needs Android 8 (API 26); on older TVs eTV simply doesn't offer it. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 26

    private const val ENABLED = "enabled"

    /** The receiver's own settings (it reads them afresh each time it starts). */
    @Suppress("DEPRECATION")
    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(AirPrefs.NAME, Context.MODE_MULTI_PROCESS)

    /** On unless switched off in Customise → AirPlay. */
    fun enabled(ctx: Context) = supported && prefs(ctx).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(ENABLED, on).commit()
        if (on) ensureRunning(ctx) else stop(ctx)
    }

    /** The receiver itself: only inside the airplay process (the AirPlay screen). */
    val service: AirPlayService? get() = if (supported) AirPlayService.instance else null

    private fun send(ctx: Context, action: String, foreground: Boolean, extras: Intent.() -> Unit = {}) {
        val i = Intent(ctx, AirPlayService::class.java).setAction(action).apply(extras)
        try {
            if (foreground) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) {
            // Android refused a service from the background; eTV tries again when it's on screen.
        }
    }

    /** Starts the receiver if AirPlay is on (nothing happens if it's already running). Safe to call often. */
    fun ensureRunning(ctx: Context) {
        if (!enabled(ctx)) return
        send(ctx, AirPlayService.ACTION_START_SERVER, foreground = true)
    }

    fun stop(ctx: Context) {
        if (!supported) return
        try {
            ctx.stopService(Intent(ctx, AirPlayService::class.java))
        } catch (_: Exception) {
        }
        statusFile(ctx).delete()
    }

    /** After a setting changed: announce again with the new name or options. */
    fun restart(ctx: Context) {
        if (!enabled(ctx)) return
        send(ctx, AirPlayService.ACTION_RESTART, foreground = true)
    }

    // ---------------- the athan ----------------

    /**
     * The athan begins: AirPlay's screen stays behind it, and with [quiet] AirPlay goes silent and
     * what it was playing waits.
     */
    fun holdForAthan(ctx: Context, quiet: Boolean) {
        if (receiverAlive(ctx)) {
            send(ctx, AirPlayService.ACTION_HOLD, foreground = false) {
                putExtra(AirPlayService.EXTRA_QUIET, quiet)
            }
        }
    }

    /** The athan is over: AirPlay's sound comes back, and with [resume] what was playing carries on. */
    fun releaseAfterAthan(ctx: Context, resume: Boolean) {
        if (receiverAlive(ctx)) {
            send(ctx, AirPlayService.ACTION_RELEASE, foreground = false) {
                putExtra(AirPlayService.EXTRA_RESUME, resume)
            }
        }
    }

    // ---------------- for Customise ----------------

    fun name(ctx: Context): String = AirPrefs.getServerName(prefs(ctx))

    fun setName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(AirPrefs.SERVER_NAME, name.trim().take(40)).commit()
        restart(ctx)
    }

    /** The receiver's process is alive (it may have been stopped, or never started). */
    fun receiverAlive(ctx: Context): Boolean {
        if (!supported) return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val name = ctx.packageName + ":airplay"
        return try {
            am.runningAppProcesses?.any { it.processName == name } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun statusFile(ctx: Context) = File(ctx.filesDir, AirPlayService.STATUS_FILE)

    /** The TV's address on the home network, for the hints in Customise. */
    fun address(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** One line about how it's doing, for Customise. */
    fun status(ctx: Context): String {
        if (!supported) return "Needs Android 8 or newer"
        if (!enabled(ctx)) return "Off"
        val lines = try {
            statusFile(ctx).takeIf { it.exists() }?.readLines().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val alive = receiverAlive(ctx)
        return when {
            lines.firstOrNull() == "ready" && alive ->
                "Ready: your Apple devices see “${lines.getOrNull(1) ?: name(ctx)}”"
            lines.firstOrNull() == "error" -> "Couldn't start: ${lines.getOrNull(1) ?: "unknown problem"}"
            else -> "Starting…"
        }
    }
}
