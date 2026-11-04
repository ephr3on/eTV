package app.etv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens eTV when the TV finishes starting up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // Newer Android versions may block this; the Home button helper covers it then.
        }
    }
}
