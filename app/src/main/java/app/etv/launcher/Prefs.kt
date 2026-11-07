package app.etv.launcher

import android.content.Context
import android.content.SharedPreferences

/** Every customisable option lives here, stored in plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("etv", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** Index into [Look.themes]: 0 Classic, 1 Doorway, 2 Riad, 3 Cartouche, 4 Lantern. */
    var theme: Int
        get() = int("theme", 0)
        set(v) = putInt("theme", v)

    /** Index into the current theme's highlight colours. */
    var accent: Int
        get() = int("accent", 0)
        set(v) = putInt("accent", v)

    /** Index into the current theme's backgrounds. */
    var palette: Int
        get() = int("palette", 0)
        set(v) = putInt("palette", v)

    /** 0 = Arabic and English, 1 = Arabic, 2 = English, 3 = off. */
    var greeting: Int
        get() = int("greeting", 0)
        set(v) = putInt("greeting", v)

    var clock24: Boolean
        get() = bool("clock24", true)
        set(v) = putBool("clock24", v)

    var showDate: Boolean
        get() = bool("showDate", true)
        set(v) = putBool("showDate", v)

    /** 0 = compact, 1 = normal, 2 = large. */
    var tileSize: Int
        get() = int("tileSize", 1)
        set(v) = putInt("tileSize", v)

    /** 0 = always, 1 = only on the selected app, 2 = never. */
    var nameMode: Int
        get() = int("nameMode", 0)
        set(v) = putInt("nameMode", v)

    /** 0 = app icon, 1 = letter. */
    var iconStyle: Int
        get() = int("iconStyle", 0)
        set(v) = putInt("iconStyle", v)

    var arches: Boolean
        get() = bool("arches", true)
        set(v) = putBool("arches", v)

    val hasFavourites: Boolean
        get() = sp.contains("favs")

    /** Package names on the home row, in order. */
    var favourites: List<String>
        get() = sp.getString("favs", "")!!.split('|').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("favs", v.joinToString("|")).apply()

    /** Package names hidden from All apps. */
    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    val tileScale: Float
        get() = when (tileSize) {
            0 -> 0.86f
            2 -> 1.16f
            else -> 1f
        }

    fun resetHomeRow() {
        sp.edit().remove("favs").apply()
    }

    /** The Home setup screen has been offered once. */
    var setupShown: Boolean
        get() = bool("setupShown", false)
        set(v) = putBool("setupShown", v)

    /** The Home button helper is allowed to switch back to eTV (it can be paused). */
    var homeHelper: Boolean
        get() = bool("homeHelper", true)
        set(v) = putBool("homeHelper", v)

    /**
     * Until this time (ms), the next screen that isn't eTV is remembered as the TV's own home
     * screen. It runs out quickly so an ordinary app can never be mistaken for one.
     */
    var learnUntil: Long
        get() = sp.getLong("learnUntil", 0L)
        set(v) = sp.edit().putLong("learnUntil", v).apply()

    val learningHome: Boolean
        get() = System.currentTimeMillis() < learnUntil

    /** How the last Home press reached eTV: one of the HOME_VIA_ values. */
    var lastHomeVia: Int
        get() = int("homeVia", HOME_VIA_NONE)
        set(v) = putInt("homeVia", v)

    companion object {
        const val HOME_VIA_NONE = 0
        /** The TV's home screen opened and the helper switched back. */
        const val HOME_VIA_SCREEN = 2
    }

    // Where you were (see Nav). Saved only when eTV goes into the background.
    val placeScreen: Int get() = int("placeScreen", Nav.SCREEN_HOME)
    val placeHome: String? get() = sp.getString("placeHome", null)
    val placeApps: String? get() = sp.getString("placeApps", null)

    fun savePlace(screen: Int, home: String?, apps: String?) {
        sp.edit().putInt("placeScreen", screen).putString("placeHome", home).putString("placeApps", apps).apply()
    }

    /** Calls [onChange] when an option that changes how a screen looks is changed. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in LOOK_KEYS) onChange() }
        sp.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    private val LOOK_KEYS = setOf(
        "accent", "palette", "greeting", "clock24", "showDate", "tileSize", "nameMode", "iconStyle",
        "arches", "favs", "hidden", "updateCode", "nowPlaying", "theme",
        "showPrayers", "showSunrise", "showHijri", "hijriAdjust", "locName", "lanternPrayers",
    )

    /** Show the "now playing" card when another app plays in the background. */
    var nowPlaying: Boolean
        get() = bool("nowPlaying", true)
        set(v) = putBool("nowPlaying", v)

    // ---- Updates from GitHub (see Updater) ----

    /** Check GitHub for a new version twice a day. */
    var autoUpdate: Boolean
        get() = bool("autoUpdate", true)
        set(v) = putBool("autoUpdate", v)

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /** Version code of a newer release found on GitHub, or 0. Shown as a hint on the Settings button. */
    var updateCode: Long
        get() = sp.getLong("updateCode", 0L)
        set(v) = sp.edit().putLong("updateCode", v).apply()

    /** An update was just handed to Android: reopen eTV once it's installed. */
    var justUpdated: Boolean
        get() = bool("justUpdated", false)
        set(v) = putBool("justUpdated", v)

    /** Home screens learnt on this TV, on top of the ones Android reports. */
    var extraHomes: Set<String>
        get() = HashSet(sp.getStringSet("extraHomes", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("extraHomes", HashSet(v)).apply()

    // ---- Athan and prayer times (see PrayerTimes, AthanService) ----

    /** The athan sounds at prayer times. The times can still show on the home screen when it's off. */
    var athanOn: Boolean
        get() = bool("athanOn", true)
        set(v) = putBool("athanOn", v)

    /** Where the prayer times are for; null until a city is chosen. */
    var place: Place?
        get() {
            val name = sp.getString("locName", null) ?: return null
            val lat = sp.getString("locLat", null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString("locLon", null)?.toDoubleOrNull() ?: return null
            return Place(name, lat, lon, sp.getString("locTz", null) ?: java.util.TimeZone.getDefault().id)
        }
        set(v) {
            val e = sp.edit()
            if (v == null) {
                e.remove("locName").remove("locLat").remove("locLon").remove("locTz")
            } else {
                e.putString("locName", v.name).putString("locLat", v.lat.toString())
                    .putString("locLon", v.lon.toString()).putString("locTz", v.tz)
            }
            e.apply()
        }

    /** One of PrayerStore.SRC_ONLINE (aladhan.com), SRC_MOSQUE (Mawaqit) or SRC_DEVICE (calculated here). */
    var prayerSource: Int
        get() = int("prayerSource", PrayerStore.SRC_ONLINE)
        set(v) = putInt("prayerSource", v)

    /** The calculation method, by its aladhan.com number (3 = Muslim World League). */
    var athanMethod: Int
        get() = int("athanMethod", 3)
        set(v) = putInt("athanMethod", v)

    /** Asr when a shadow is twice an object's length (Hanafi) instead of once. */
    var asrHanafi: Boolean
        get() = bool("asrHanafi", false)
        set(v) = putBool("asrHanafi", v)

    /** Fajr and Isha far from the equator: 0 angle-based, 1 middle of the night, 2 one seventh of the night. */
    var highLat: Int
        get() = int("highLat", 0)
        set(v) = putInt("highLat", v)

    /** The Mawaqit mosque whose times are used (its address on mawaqit.net), and its name. */
    var mosqueSlug: String?
        get() = sp.getString("mosqueSlug", null)
        set(v) = sp.edit().putString("mosqueSlug", v).apply()

    var mosqueName: String?
        get() = sp.getString("mosqueName", null)
        set(v) = sp.edit().putString("mosqueName", v).apply()

    /** The athan is played for this prayer (the time still shows when it's off). */
    fun athanFor(p: Prayer): Boolean = p.hasAthan && bool("athan_${p.key}", true)
    fun setAthanFor(p: Prayer, on: Boolean) = putBool("athan_${p.key}", on)

    /** Which recording each prayer's athan uses (see AthanSounds). Makkah, and Makkah's own Fajr athan. */
    fun athanSound(p: Prayer): String =
        sp.getString("sound_${p.key}", null) ?: if (p == Prayer.FAJR) AthanSounds.DEFAULT_FAJR else AthanSounds.DEFAULT
    fun setAthanSound(p: Prayer, id: String) = sp.edit().putString("sound_${p.key}", id).apply()

    /** Minutes added to (or taken from) a prayer's time. */
    fun offset(p: Prayer): Int = int("offset_${p.key}", 0)
    fun setOffset(p: Prayer, minutes: Int) = putInt("offset_${p.key}", minutes)

    /** A fixed time for a prayer (minutes after midnight), or -1 to follow the source. */
    fun fixedTime(p: Prayer): Int = int("fixed_${p.key}", -1)
    fun setFixedTime(p: Prayer, minutes: Int) = putInt("fixed_${p.key}", minutes)

    /** Your own recordings, by link. */
    var customAthanUrl: String
        get() = sp.getString("customAthanUrl", "") ?: ""
        set(v) = sp.edit().putString("customAthanUrl", v).apply()

    var customDuaUrl: String
        get() = sp.getString("customDuaUrl", "") ?: ""
        set(v) = sp.edit().putString("customDuaUrl", v).apply()

    /** The dua after the athan: a sound id from AthanSounds.duas, AthanSounds.CUSTOM_DUA, or "" for none. */
    var dua: String
        get() = sp.getString("dua", null) ?: AthanSounds.DEFAULT_DUA
        set(v) = sp.edit().putString("dua", v).apply()

    /** Pause whatever is playing on the TV while the athan and the dua play. */
    var athanPause: Boolean
        get() = bool("athanPause", true)
        set(v) = putBool("athanPause", v)

    /** ...and carry on with it afterwards. */
    var athanResume: Boolean
        get() = bool("athanResume", true)
        set(v) = putBool("athanResume", v)

    /** Volume for the athan in percent, or -1 to leave the TV's volume as it is. */
    var athanVolume: Int
        get() = int("athanVolume", -1)
        set(v) = putInt("athanVolume", v)

    /** Prayer times on the home screen, with or without sunrise, and the Hijri date under the date. */
    var showPrayers: Boolean
        get() = bool("showPrayers", true)
        set(v) = putBool("showPrayers", v)

    var showSunrise: Boolean
        get() = bool("showSunrise", true)
        set(v) = putBool("showSunrise", v)

    var showHijri: Boolean
        get() = bool("showHijri", true)
        set(v) = putBool("showHijri", v)

    /** How Lantern shows the prayer times: 0 a line, 1 the sun's path, 2 one at a time (chosen), 3 at the foot. */
    var lanternPrayers: Int
        get() = int("lanternPrayers", 2)
        set(v) = putInt("lanternPrayers", v)

    /** Days added to the Hijri date (-2 to 2), for when the month starts by sighting. */
    var hijriAdjust: Int
        get() = int("hijriAdjust", 0)
        set(v) = putInt("hijriAdjust", v)

    /** Your own code to end the athan early: a salted hash of it, its salt and its length (never the code). Empty: the built-in one. */
    var athanCodeHash: String
        get() = sp.getString("athanCodeHash", "") ?: ""
        set(v) = sp.edit().putString("athanCodeHash", v).apply()

    var athanCodeSalt: String
        get() = sp.getString("athanCodeSalt", "") ?: ""
        set(v) = sp.edit().putString("athanCodeSalt", v).apply()

    var athanCodeLength: Int
        get() = int("athanCodeLength", 0)
        set(v) = putInt("athanCodeLength", v)

    /** How many rounds the hash of your code took (codes set in 1.8.5 used 20,000). */
    var athanCodeRounds: Int
        get() = int("athanCodeRounds", 20_000)
        set(v) = putInt("athanCodeRounds", v)

    /** The last athan played, as "<day>-<prayer>", so none plays twice. */
    var lastAthan: String
        get() = sp.getString("lastAthan", "") ?: ""
        set(v) = sp.edit().putString("lastAthan", v).commit().let { }

    /** The city picker has been offered once. */
    var athanSetupShown: Boolean
        get() = bool("athanSetupShown", false)
        set(v) = putBool("athanSetupShown", v)
}
