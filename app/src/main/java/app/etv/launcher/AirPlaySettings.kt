package app.etv.launcher

import android.text.InputType
import com.flymop.airplaytv.Prefs as AirPrefs

/**
 * Customise → AirPlay: on / off, the name your Apple devices see, a code for new devices, and the
 * picture. Every change announces the TV again straight away.
 */
internal fun SettingsActivity.airplayPane() {
    if (!AirPlay.supported) {
        note("AirPlay needs Android 8 or newer, and this TV has an older version.")
        return
    }
    val ap = AirPlay.prefs(this)
    val on = AirPlay.enabled(this)

    row(
        "airplay", "AirPlay",
        detail = if (on) AirPlay.status(this) else "Off. Apple devices don't see this TV.",
        toggle = on,
    ) {
        AirPlay.setEnabled(this, !on)
        // The receiver takes a moment to start: show how it went.
        pane.postDelayed({ if (!isFinishing) render() }, 1_500L)
    }
    if (!on) return

    row("airplay_name", "Name on your devices", AirPlay.name(this), opens = true, rerender = false) {
        askText("Name on your devices", "eTV", AirPlay.name(this), InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name ->
            AirPlay.setName(this, name)
            render()
        }
    }
    val pin = ap.getBoolean(AirPrefs.REQUIRE_PIN, AirPrefs.DEF_REQUIRE_PIN)
    row(
        "airplay_pin", "Ask for a code",
        detail = if (pin) "The TV shows a code to type on the device before it can play"
        else "Anyone on your Wi-Fi can play on the TV",
        toggle = pin,
    ) {
        ap.edit().putBoolean(AirPrefs.REQUIRE_PIN, !pin).apply()
        AirPlay.restart(this)
    }

    group("Picture")
    val sizes = listOf(AirPrefs.AUTO, "3840x2160", "1920x1080", "1280x720")
    val sizeNames = listOf("As the TV", "4K", "1080p", "720p")
    val size = sizes.indexOf(ap.getString(AirPrefs.RESOLUTION, AirPrefs.DEF_RESOLUTION)).coerceAtLeast(0)
    row("airplay_size", "Mirrored screens", sizeNames[size], detail = "The largest picture devices send", opens = true, rerender = false) {
        menu(
            "Mirrored screens",
            sizeNames,
            note = "Devices send at most this size. Lower it if mirroring stutters on a busy Wi-Fi.",
            current = size,
        ) { which ->
            ap.edit().putString(AirPrefs.RESOLUTION, sizes[which]).apply()
            AirPlay.restart(this)
            focusKey = "airplay_size"
            render()
        }
    }
    val h265 = ap.getBoolean(AirPrefs.H265_ENABLED, AirPrefs.DEF_H265_ENABLED)
    row(
        "airplay_h265", "H.265 video",
        detail = "Sharper mirroring from newer devices; switch off if the picture stays black",
        toggle = h265,
    ) {
        ap.edit().putBoolean(AirPrefs.H265_ENABLED, !h265).apply()
        AirPlay.restart(this)
    }

    val direct = ap.getBoolean(AirPrefs.DIRECT_VIDEO, AirPrefs.DEF_DIRECT_VIDEO)
    row(
        "airplay_direct", "Through the TV's picture processing",
        detail = if (direct) "Sharper scaling; the sound can drop out for a moment when a mirrored video changes shape"
        else "Off: the sound carries straight on from one video to the next",
        toggle = direct,
    ) {
        ap.edit().putBoolean(AirPrefs.DIRECT_VIDEO, !direct).commit()
        AirPlay.restart(this)
    }

    group("How to")
    val name = AirPlay.name(this)
    val where = AirPlay.address()?.let { "  The TV is at $it." } ?: ""
    note(
        "On an iPhone or iPad on the same Wi-Fi: Control Centre → Screen Mirroring → $name. " +
            "In an app (Photos, Music, YouTube, Safari): the AirPlay icon → $name. " +
            "On a Mac: Control Centre → Screen Mirroring.$where",
    )
    note("During the athan AirPlay goes quiet like everything else, and carries on afterwards.")
}
