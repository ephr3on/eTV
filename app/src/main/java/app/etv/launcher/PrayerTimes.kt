package app.etv.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** The five prayers, and sunrise (shown, but it has no athan). */
enum class Prayer(val ar: String, val en: String) {
    FAJR("الفجر", "Fajr"),
    SUNRISE("الشروق", "Sunrise"),
    DHUHR("الظهر", "Dhuhr"),
    ASR("العصر", "Asr"),
    MAGHRIB("المغرب", "Maghrib"),
    ISHA("العشاء", "Isha");

    val key: String get() = name.lowercase()
    val hasAthan: Boolean get() = this != SUNRISE

    /** On Fridays Dhuhr is Jumu'ah. */
    fun arName(friday: Boolean) = if (this == DHUHR && friday) "الجمعة" else ar
    fun enName(friday: Boolean) = if (this == DHUHR && friday) "Jumu'ah" else en

    companion object {
        val withAthan = listOf(FAJR, DHUHR, ASR, MAGHRIB, ISHA)
    }
}

/** A place the times are for. [tz] is its time zone, so the times are its local times. */
class Place(val name: String, val lat: Double, val lon: Double, val tz: String) {
    /** A short name for the home screen ("Hannover" from "Hannover, Lower Saxony, Germany"). */
    val short: String get() = name.substringBefore(',').trim()
}

/**
 * A way of calculating the times, as aladhan.com numbers them (so the same choice works online and
 * on the TV). Isha is either an angle, or a number of minutes after Maghrib. [tune] nudges each
 * time (Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha) by minutes, as the authority does.
 */
class Method(
    val id: Int,
    val name: String,
    val fajr: Double,
    val isha: Double,
    val ishaMinutes: Int = 0,
    val maghribAngle: Double = 0.0,
    val tune: IntArray = IntArray(6),
)

object Methods {
    val list: List<Method> = listOf(
        Method(3, "Muslim World League", 18.0, 17.0),
        Method(5, "Egyptian General Authority of Survey", 19.5, 17.5),
        Method(4, "Umm al-Qura, Makkah", 18.5, 0.0, ishaMinutes = 90),
        Method(13, "Diyanet, Turkey", 18.0, 17.0, tune = intArrayOf(0, -7, 5, 4, 7, 0)),
        Method(2, "Islamic Society of North America", 15.0, 15.0),
        Method(12, "UOIF, France", 12.0, 12.0),
        Method(1, "University of Islamic Sciences, Karachi", 18.0, 18.0),
        Method(8, "Gulf region", 19.5, 0.0, ishaMinutes = 90),
        Method(9, "Kuwait", 18.0, 17.5),
        Method(10, "Qatar", 18.0, 0.0, ishaMinutes = 90),
        Method(16, "Dubai", 18.2, 18.2),
        Method(18, "Tunisia", 18.0, 18.0),
        Method(19, "Algeria", 18.0, 17.0),
        Method(21, "Morocco", 19.0, 17.0),
        Method(11, "Singapore", 20.0, 18.0),
        Method(14, "Spiritual Administration of Muslims of Russia", 16.0, 15.0),
        Method(7, "Institute of Geophysics, Tehran", 17.7, 14.0, maghribAngle = 4.5),
    )

    fun byId(id: Int): Method = list.firstOrNull { it.id == id } ?: list[0]

    val highLatNames = listOf("Angle-based", "Middle of the night", "One seventh of the night")

    /** aladhan.com's numbers for [highLatNames]. */
    fun highLatId(i: Int) = when (i) {
        1 -> 1
        2 -> 2
        else -> 3
    }
}

/** Days as yyyymmdd numbers, in a place's own time zone. */
object Days {
    fun tz(place: Place?): TimeZone = TimeZone.getTimeZone(place?.tz ?: TimeZone.getDefault().id)

    fun key(c: Calendar) =
        c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

    fun cal(key: Int, tz: TimeZone): Calendar = Calendar.getInstance(tz).apply {
        clear()
        set(key / 10000, key / 100 % 100 - 1, key % 100, 0, 0, 0)
    }

    fun today(tz: TimeZone, now: Long = System.currentTimeMillis()): Int =
        key(Calendar.getInstance(tz).apply { timeInMillis = now })

    fun add(key: Int, days: Int, tz: TimeZone): Int {
        if (days == 0) return key
        val c = cal(key, tz)
        c.add(Calendar.DAY_OF_MONTH, days)
        return key(c)
    }

    /** The moment [minutes] after midnight on [key], in [tz]. */
    fun epoch(key: Int, minutes: Int, tz: TimeZone): Long {
        val c = cal(key, tz)
        c.set(Calendar.HOUR_OF_DAY, minutes / 60)
        c.set(Calendar.MINUTE, minutes % 60)
        return c.timeInMillis
    }

    fun isFriday(key: Int, tz: TimeZone) = cal(key, tz).get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

    fun clock(minutes: Int, h24: Boolean): String {
        val h = minutes / 60
        val m = minutes % 60
        val hh = if (h24) h else (if (h % 12 == 0) 12 else h % 12)
        return if (h24) "%02d:%02d".format(hh, m) else "%d:%02d".format(hh, m)
    }

    /** "05:30" or "5:30" to minutes after midnight, or null. */
    fun parseClock(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2})\s*[:.]\s*(\d{2})""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return h * 60 + min
    }
}

/**
 * Prayer times worked out on the TV from the sun's position: the well-known method used by
 * PrayTimes.org and aladhan.com, so with the same settings the two agree to a minute or so.
 */
object PrayCalc {
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x))
    private fun darccot(x: Double) = deg(atan(1.0 / x))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))

    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }

    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(a: Double) = fix(a, 24.0)
    private fun diff(t1: Double, t2: Double) = fixHour(t2 - t1)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** The sun's declination and the equation of time, for a Julian date. */
    private fun sun(jd: Double): DoubleArray {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return doubleArrayOf(decl, eqt)
    }

    /**
     * Minutes after local midnight for Fajr, Sunrise, Dhuhr, Asr, Maghrib and Isha on [key],
     * in [place]'s own time zone.
     */
    fun day(key: Int, place: Place, methodId: Int, hanafi: Boolean, highLat: Int): IntArray {
        val m = Methods.byId(methodId)
        val tz = Days.tz(place)
        val y = key / 10000
        val mo = key / 100 % 100
        val d = key % 100
        val lat = place.lat
        val lng = place.lon
        val zone = tz.getOffset(Days.epoch(key, 12 * 60, tz)) / 3_600_000.0
        val jDate = julian(y, mo, d) - lng / (15.0 * 24.0)

        fun midDay(t: Double) = fixHour(12 - sun(jDate + t)[1])
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sun(jDate + t)[0]
            val noon = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            if (x < -1.0 || x > 1.0) return Double.NaN
            val tt = darccos(x) / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Double, t: Double): Double {
            val decl = sun(jDate + t)[0]
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, t, false)
        }

        val riseSet = 0.833
        // Fajr, Sunrise, Dhuhr, Asr, Sunset, Maghrib, Isha: first guesses, then refined once.
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0)
        repeat(2) {
            val p = DoubleArray(7) { i -> (if (t[i].isNaN()) doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0)[i] else t[i]) / 24.0 }
            val sunset = sunAngleTime(riseSet, p[4], false)
            t = doubleArrayOf(
                sunAngleTime(m.fajr, p[0], true),
                sunAngleTime(riseSet, p[1], true),
                midDay(p[2]),
                asrTime(if (hanafi) 2.0 else 1.0, p[3]),
                sunset,
                if (m.maghribAngle > 0) sunAngleTime(m.maghribAngle, p[5], false) else sunset,
                if (m.ishaMinutes == 0) sunAngleTime(m.isha, p[6], false) else Double.NaN,
            )
        }

        // To local time.
        for (i in t.indices) t[i] += zone - lng / 15.0

        // Far from the equator the sky may never get dark enough: use part of the night instead.
        val sunrise = t[1]
        val sunset = t[4]
        if (!sunrise.isNaN() && !sunset.isNaN()) {
            val night = diff(sunset, sunrise)
            fun portion(angle: Double) = night * when (highLat) {
                1 -> 0.5
                2 -> 1.0 / 7.0
                else -> angle / 60.0
            }
            fun adjust(time: Double, base: Double, angle: Double, ccw: Boolean): Double {
                val limit = portion(angle)
                val gap = if (ccw) diff(time, base) else diff(base, time)
                if (!time.isNaN() && gap <= limit) return time
                return if (ccw) base - limit else base + limit
            }
            t[0] = adjust(t[0], sunrise, m.fajr, true)
            if (m.ishaMinutes == 0) t[6] = adjust(t[6], sunset, m.isha, false)
            if (m.maghribAngle > 0) t[5] = adjust(t[5], sunset, m.maghribAngle, false)
        }
        if (m.ishaMinutes > 0) t[6] = t[5] + m.ishaMinutes / 60.0

        val order = intArrayOf(0, 1, 2, 3, 5, 6) // drop sunset
        return IntArray(6) { i ->
            val h = t[order[i]]
            if (h.isNaN()) {
                // Shouldn't happen after the adjustment above; keep something sensible.
                intArrayOf(5 * 60, 6 * 60, 12 * 60, 15 * 60, 18 * 60, 19 * 60)[i]
            } else {
                (Math.round(fixHour(h + m.tune[i] / 60.0) * 60.0).toInt()) % (24 * 60)
            }
        }
    }
}

/** Hijri dates: from aladhan.com when it gave them, otherwise Umm al-Qura via Android's ICU. */
object Hijri {
    private val arMonths = arrayOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )
    private val enMonths = arrayOf(
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Akhir", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah",
    )

    /** Day, month (1-12) and year, or null when this Android version can't tell. */
    fun icu(key: Int, tz: TimeZone): IntArray? {
        if (Build.VERSION.SDK_INT < 24) return null
        return try {
            val c = android.icu.util.IslamicCalendar(android.icu.util.TimeZone.getTimeZone(tz.id))
            c.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            c.timeInMillis = Days.epoch(key, 12 * 60, tz)
            intArrayOf(
                c.get(android.icu.util.Calendar.DAY_OF_MONTH),
                c.get(android.icu.util.Calendar.MONTH) + 1,
                c.get(android.icu.util.Calendar.YEAR),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun format(h: IntArray, arabic: Boolean): String {
        val m = (h[1] - 1).coerceIn(0, 11)
        return if (arabic) arabicDigits("${h[0]} ${arMonths[m]} ${h[2]} هـ")
        else "${h[0]} ${enMonths[m]} ${h[2]} AH"
    }

    fun arabicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '0'..'9') ('٠' + (ch - '0')) else ch)
        return sb.toString()
    }
}

/**
 * The prayer times eTV shows and calls the athan for.
 *
 * Times from aladhan.com or a mosque are kept in a small file, so they're there straight away and
 * without internet. Whenever they're missing (no internet yet, or a day the source didn't cover),
 * the TV works them out itself with the same method, so there are always times once a city is set.
 * Your own minute adjustments and fixed times are applied on top.
 */
object PrayerStore {
    const val SRC_ONLINE = 0
    const val SRC_MOSQUE = 1
    const val SRC_DEVICE = 2

    private var loaded = false
    private var sig = ""
    private var fetchedAt = 0L
    private val days = HashMap<Int, IntArray>()
    private val hijri = HashMap<Int, IntArray>()

    /** What the last update from the internet said, for Settings. */
    @Volatile var status: String = ""

    /** Called on the main thread when the times change. */
    val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun notifyChanged() {
        main.post { listeners.toList().forEach { it() } }
    }

    /** Everything the stored times depend on: change any of it and they're fetched again. */
    fun signature(p: Prefs): String {
        val place = p.place ?: return ""
        val where = "%.4f,%.4f".format(java.util.Locale.ROOT, place.lat, place.lon)
        return when (p.prayerSource) {
            SRC_MOSQUE -> "mosque|${p.mosqueSlug}"
            SRC_ONLINE -> "aladhan|$where|${p.athanMethod}|${p.asrHanafi}|${p.highLat}"
            else -> "device"
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "prayer-times.json")

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            sig = o.optString("sig")
            fetchedAt = o.optLong("at")
            readMap(o.optJSONObject("days"), days)
            readMap(o.optJSONObject("hijri"), hijri)
        } catch (_: Exception) {
        }
    }

    private fun readMap(o: JSONObject?, into: HashMap<Int, IntArray>) {
        if (o == null) return
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.optJSONArray(k) ?: continue
            val key = k.toIntOrNull() ?: continue
            into[key] = IntArray(a.length()) { a.optInt(it) }
        }
    }

    private fun writeMap(m: Map<Int, IntArray>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k.toString(), JSONArray().apply { v.forEach { put(it) } })
        return o
    }

    /**
     * Keeps freshly fetched times. Days from the same source are merged; days long gone are dropped.
     */
    @Synchronized
    fun put(ctx: Context, newSig: String, newDays: Map<Int, IntArray>, newHijri: Map<Int, IntArray>, today: Int) {
        load(ctx)
        if (newSig != sig) days.clear()
        sig = newSig
        fetchedAt = System.currentTimeMillis()
        days.putAll(newDays)
        hijri.putAll(newHijri)
        days.keys.filter { it < today - 3 }.forEach { days.remove(it) } // works for yyyymmdd across months well enough
        hijri.keys.filter { it < today - 3 }.forEach { hijri.remove(it) }
        try {
            val o = JSONObject()
            o.put("sig", sig)
            o.put("at", fetchedAt)
            o.put("days", writeMap(days))
            o.put("hijri", writeMap(hijri))
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** True when the times should be fetched from the internet again. */
    @Synchronized
    fun needsRefresh(ctx: Context, p: Prefs): Boolean {
        if (p.place == null || p.prayerSource == SRC_DEVICE) return false
        if (p.prayerSource == SRC_MOSQUE && p.mosqueSlug.isNullOrEmpty()) return false
        load(ctx)
        if (sig != signature(p)) return true
        val tz = Days.tz(p.place)
        val today = Days.today(tz)
        val ahead = if (p.prayerSource == SRC_MOSQUE) 7 else 10
        for (i in 0..ahead) if (days[Days.add(today, i, tz)] == null) return true
        val maxAge = if (p.prayerSource == SRC_MOSQUE) 7 else 20
        return System.currentTimeMillis() - fetchedAt > maxAge * 86_400_000L
    }

    /** When the stored times were last fetched (0 for never). */
    @Synchronized
    fun fetchedAt(ctx: Context, p: Prefs): Long {
        load(ctx)
        return if (sig == signature(p)) fetchedAt else 0L
    }

    /** True when [key]'s times come from the chosen source rather than the TV's own calculation. */
    @Synchronized
    fun fromSource(ctx: Context, p: Prefs, key: Int): Boolean {
        if (p.prayerSource == SRC_DEVICE) return false
        load(ctx)
        return sig == signature(p) && days[key] != null
    }

    /** The source's times for [key] before your adjustments; null until a city is chosen. */
    @Synchronized
    fun raw(ctx: Context, p: Prefs, key: Int): IntArray? {
        val place = p.place ?: return null
        load(ctx)
        if (p.prayerSource != SRC_DEVICE && sig == signature(p)) {
            days[key]?.let { if (it.size >= 6) return it }
        }
        return PrayCalc.day(key, place, p.athanMethod, p.asrHanafi, p.highLat)
    }

    /** The times for [key] with your adjustments: minutes after midnight, Fajr to Isha (with Sunrise). */
    fun times(ctx: Context, p: Prefs, key: Int): IntArray? {
        val r = raw(ctx, p, key) ?: return null
        return IntArray(6) { i ->
            val pr = Prayer.values()[i]
            val fixed = p.fixedTime(pr)
            if (fixed >= 0) fixed else (r[i] + p.offset(pr)).coerceIn(0, 24 * 60 - 1)
        }
    }

    /** The Hijri date for [key] (with your adjustment), or null. */
    fun hijri(ctx: Context, p: Prefs, key: Int, tz: TimeZone): IntArray? {
        val k = Days.add(key, p.hijriAdjust, tz)
        synchronized(this) {
            load(ctx)
            hijri[k]?.let { return it }
        }
        return Hijri.icu(k, tz)
    }

    class Upcoming(val prayer: Prayer, val day: Int, val minutes: Int, val at: Long, val friday: Boolean)

    /**
     * The first prayer after [after]: any of the five, or with [athanOnly] only those with the athan
     * switched on. Looks at today and the next two days.
     */
    fun next(ctx: Context, p: Prefs, after: Long, athanOnly: Boolean): Upcoming? {
        val place = p.place ?: return null
        val tz = Days.tz(place)
        val today = Days.today(tz, after)
        for (d in 0..2) {
            val key = Days.add(today, d, tz)
            val t = times(ctx, p, key) ?: return null
            val friday = Days.isFriday(key, tz)
            for (pr in Prayer.withAthan) {
                if (athanOnly && !p.athanFor(pr)) continue
                val at = Days.epoch(key, t[pr.ordinal], tz)
                if (at > after) return Upcoming(pr, key, t[pr.ordinal], at, friday)
            }
        }
        return null
    }
}
