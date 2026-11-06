package app.etv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens eTV when the TV finishes starting up, and again right after eTV updates itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Unit
            // Only after an update eTV installed itself (eTV clears the flag once it's back on screen).
            Intent.ACTION_MY_PACKAGE_REPLACED -> if (!Prefs(context).justUpdated) return
            else -> return
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // Newer Android versions may block this; the Home button helper covers it then.
        }
    }
}
