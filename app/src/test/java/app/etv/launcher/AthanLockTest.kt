package app.etv.launcher

import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The athan screen ignores the remote, except for the code on the number keys (a test code here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AthanLockTest {

    private fun press(a: AthanActivity, key: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private fun type(a: AthanActivity, digits: String) {
        for (ch in digits) press(a, KeyEvent.KEYCODE_0 + (ch - '0'))
    }

    private val remoteKeys = listOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED,
    )

    @Test
    fun withoutACodeNothingEndsIt() {
        val ctx = RuntimeEnvironment.getApplication()
        AthanService.setCode(ctx, "")
        val a = Robolectric.buildActivity(AthanActivity::class.java).setup().get()
        remoteKeys.forEach { press(a, it) }
        type(a, "0123456789012")
        assertFalse(a.isFinishing)
    }

    @Test
    fun onlyTheCodeEndsIt() {
        val ctx = RuntimeEnvironment.getApplication()
        AthanService.setCode(ctx, "24680")
        assertTrue(AthanService.isCode(ctx, "24680"))
        assertFalse(AthanService.isCode(ctx, "24681"))
        assertFalse(Prefs(ctx).athanCodeHash.contains("24680"))

        val a = Robolectric.buildActivity(AthanActivity::class.java).setup().get()
        remoteKeys.forEach { press(a, it) }
        @Suppress("DEPRECATION")
        a.onBackPressed()
        assertFalse("a remote key closed the athan screen", a.isFinishing)

        type(a, "2468")
        type(a, "13579")
        assertFalse("a wrong code closed the athan screen", a.isFinishing)

        // Digits typed before the code don't matter: the last ones count.
        type(a, "7724680")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the code didn't close the athan screen", a.isFinishing)
    }
}
