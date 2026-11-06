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
}
