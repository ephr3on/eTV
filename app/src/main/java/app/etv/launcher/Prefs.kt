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

    /** Index into [Look.accents]. */
    var accent: Int
        get() = int("accent", 0)
        set(v) = putInt("accent", v)

    /** Index into [Look.palettes]. */
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

    /** Home screens learnt on this TV, on top of the ones Android reports. */
    var extraHomes: Set<String>
        get() = HashSet(sp.getStringSet("extraHomes", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("extraHomes", HashSet(v)).apply()
}
