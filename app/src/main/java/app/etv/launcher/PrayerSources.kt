package app.etv.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** Plain HTTP for the prayer-time sources and the athan recordings. */
object Net {
    private const val UA_APP = "eTV-launcher (Android TV)"

    /** Mawaqit's site only answers browsers. */
    private const val UA_BROWSER =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    class HttpError(val code: Int) : Exception("HTTP $code")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(url: String, browser: Boolean, accept: String, referer: String? = null): HttpURLConnection {
        var u = url
        // Follow redirects by hand, so http -> https hops work too.
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", if (browser) UA_BROWSER else UA_APP)
                setRequestProperty("Accept", accept)
                if (browser) setRequestProperty("Accept-Language", "en;q=0.9")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw HttpError(code)
                u = URL(URL(u), loc).toString()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw HttpError(code)
            }
            return c
        }
        throw HttpError(310)
    }

    fun get(url: String, browser: Boolean = false, accept: String = "application/json", referer: String? = null): String {
        val c = open(url, browser, accept, referer)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, to: File) {
        val c = open(url, browser = false, accept = "*/*")
        try {
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
    }

    /** A short reason for a failure, for Settings. */
    fun why(e: Exception): String = when (e) {
        is HttpError -> when (e.code) {
            403 -> "the site refused the TV"
            404 -> "not found"
            429 -> "the site is busy, try later"
            else -> "the site answered ${e.code}"
        }
        is java.net.UnknownHostException -> "no internet"
        is javax.net.ssl.SSLException -> "the TV couldn't make a secure connection"
        is java.net.SocketTimeoutException -> "no answer in time"
        is java.net.ConnectException -> "couldn't reach the site"
        is java.io.IOException -> if (e.message?.contains("space", true) == true) "the TV is full" else "the connection broke off"
        else -> "couldn't connect"
    }
}

/** Finding a city: Open-Meteo's free place search, or the TV's internet connection. */
object Geo {
    fun search(query: String): List<Place> {
        val json = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?name=${Net.enc(query)}&count=12&language=en&format=json"))
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val parts = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }.distinct()
            out.add(Place(parts.joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"),
                r.optString("timezone").ifEmpty { java.util.TimeZone.getDefault().id }))
        }
        return out
    }

    /** Roughly where the TV is, from its internet connection (city level). */
    fun fromInternet(): Place? {
        val o = JSONObject(Net.get("https://ipwho.is/"))
        if (!o.optBoolean("success", false)) return null
        val parts = listOf(o.optString("city"), o.optString("region"), o.optString("country"))
            .filter { it.isNotBlank() }.distinct()
        val tz = o.optJSONObject("timezone")?.optString("id").orEmpty()
        return Place(parts.joinToString(", "), o.getDouble("latitude"), o.getDouble("longitude"),
            tz.ifEmpty { java.util.TimeZone.getDefault().id })
    }
}

/** aladhan.com: times worked out online for a place, a month at a time. */
object Aladhan {
    class Month(val days: Map<Int, IntArray>, val hijri: Map<Int, IntArray>)

    fun month(place: Place, year: Int, month: Int, methodId: Int, hanafi: Boolean, highLat: Int): Month {
        val url = "https://api.aladhan.com/v1/calendar/$year/$month" +
            "?latitude=${place.lat}&longitude=${place.lon}&method=$methodId" +
            "&school=${if (hanafi) 1 else 0}&latitudeAdjustmentMethod=${Methods.highLatId(highLat)}" +
            "&timezonestring=${Net.enc(place.tz)}"
        val data = JSONObject(Net.get(url)).getJSONArray("data")
        val days = HashMap<Int, IntArray>()
        val hijri = HashMap<Int, IntArray>()
        for (i in 0 until data.length()) {
            val d = data.getJSONObject(i)
            val t = d.getJSONObject("timings")
            val date = d.getJSONObject("date")
            val g = date.getJSONObject("gregorian").getString("date").split('-') // dd-MM-yyyy
            if (g.size != 3) continue
            val key = g[2].toInt() * 10000 + g[1].toInt() * 100 + g[0].toInt()
            val names = arrayOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
            val mins = IntArray(6)
            var ok = true
            for (k in names.indices) {
                val v = Days.parseClock(t.optString(names[k]))
                if (v == null) ok = false else mins[k] = v
            }
            if (ok) days[key] = mins
            date.optJSONObject("hijri")?.let { h ->
                val day = h.optString("day").toIntOrNull()
                val mon = h.optJSONObject("month")?.optInt("number") ?: 0
                val yr = h.optString("year").toIntOrNull()
                if (day != null && yr != null && mon in 1..12) hijri[key] = intArrayOf(day, mon, yr)
            }
        }
        return Month(days, hijri)
    }
}

/**
 * Mawaqit (mawaqit.net): the times thousands of mosques publish themselves, including many in
 * Germany. Mosques are found with the site's own search; a mosque's page carries its whole year.
 */
object Mawaqit {
    class Mosque(val slug: String, val name: String, val where: String, val lat: Double?, val lon: Double?, val today: IntArray?)

    private const val SITE = "https://mawaqit.net"

    fun search(word: String?, near: Place?): List<Mosque> {
        val q = if (word != null) "word=${Net.enc(word)}" else "lat=${near!!.lat}&lon=${near.lon}"
        val body = Net.get("$SITE/api/2.0/mosque/search?$q", browser = true, referer = "$SITE/")
        val arr: JSONArray = when (val v = org.json.JSONTokener(body).nextValue()) {
            is JSONArray -> v
            is JSONObject -> listOf("data", "results", "mosques", "items").firstNotNullOfOrNull { v.optJSONArray(it) } ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<Mosque>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val slug = m.optString("slug")
            if (slug.isEmpty()) continue
            val where = m.optString("localisation").ifEmpty { m.optString("label") }
            out.add(Mosque(
                slug, m.optString("name").ifEmpty { slug }, where,
                m.optDouble("latitude").takeIf { !it.isNaN() }, m.optDouble("longitude").takeIf { !it.isNaN() },
                todayFrom(m.optJSONArray("times"), m.optString("shuruq")),
            ))
        }
        return out
    }

    /** A mosque's times as [fajr, sunrise, dhuhr, asr, maghrib, isha]; sunrise may be missing (5 times). */
    private fun todayFrom(a: JSONArray?, shuruq: String?): IntArray? {
        if (a == null) return null
        val v = (0 until a.length()).mapNotNull { Days.parseClock(a.optString(it)) }
        return when {
            v.size >= 6 -> v.take(6).toIntArray()
            v.size == 5 -> {
                val sr = shuruq?.let { Days.parseClock(it) } ?: -1
                intArrayOf(v[0], sr, v[1], v[2], v[3], v[4])
            }
            else -> null
        }
    }

    class Year(val days: Map<Int, IntArray>)

    /**
     * The next [ahead] days of [slug]'s times, from its page. Falls back to just today's times
     * from the search when the page can't be read.
     */
    fun times(slug: String, name: String?, place: Place, ahead: Int = 60): Year {
        val tz = Days.tz(place)
        val today = Days.today(tz)
        try {
            val html = Net.get("$SITE/en/$slug", browser = true, accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val conf = confData(html) ?: throw IllegalStateException("no times on the page")
            val cal = conf.optJSONArray("calendar")
            val out = HashMap<Int, IntArray>()
            if (cal != null && cal.length() == 12) {
                for (i in 0..ahead) {
                    val key = Days.add(today, i, tz)
                    val month = cal.optJSONObject(key / 100 % 100 - 1) ?: continue
                    val t = todayFrom(month.optJSONArray((key % 100).toString()), null) ?: continue
                    if (t[1] >= 0) out[key] = t
                }
            }
            if (out[today] == null) {
                todayFrom(conf.optJSONArray("times"), conf.optString("shuruq"))?.let { out[today] = it }
            }
            if (out.isNotEmpty()) return Year(fillSunrise(out, place))
        } catch (e: Exception) {
            if (name.isNullOrEmpty()) throw e
        }
        // The page didn't work: the search still has today's times.
        val found = search(name, null).firstOrNull { it.slug == slug }?.today
            ?: throw IllegalStateException("the mosque didn't answer")
        return Year(fillSunrise(mapOf(today to found), place))
    }

    /** Some mosques don't publish sunrise; work it out for those days. */
    private fun fillSunrise(days: Map<Int, IntArray>, place: Place): Map<Int, IntArray> =
        days.mapValues { (key, t) ->
            if (t[1] >= 0) t else t.copyOf().also { it[1] = PrayCalc.day(key, place, 3, false, 0)[1] }
        }

    /** Pulls the `var confData = {...};` object out of a mosque page. */
    private fun confData(html: String): JSONObject? {
        var from = 0
        while (true) {
            val i = html.indexOf("confData", from)
            if (i < 0) return null
            from = i + 8
            val eq = html.indexOf('=', from)
            val open = html.indexOf('{', from)
            if (eq < 0 || open < 0 || open < eq || open - eq > 10) continue
            val end = matchBrace(html, open)
            if (end < 0) continue
            try {
                val o = JSONObject(html.substring(open, end + 1))
                if (o.has("calendar") || o.has("times")) return o
            } catch (_: Exception) {
            }
        }
    }

    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var inString = false
        var quote = '"'
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == quote) inString = false
            } else when (c) {
                '"', '\'' -> { inString = true; quote = c }
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }
}

/** Keeps the stored times fresh from the chosen source. */
object PrayerSync {
    @Volatile var busy = false
        private set
    private var lastTry = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Fetches quietly when the stored times are old or missing (not more than every half hour). */
    fun refreshIfDue(ctx: Context) {
        val p = Prefs(ctx)
        if (busy || !PrayerStore.needsRefresh(ctx, p)) return
        if (System.currentTimeMillis() - lastTry < 30 * 60_000L) return
        refresh(ctx)
    }

    /** Fetches now. [done] gets a short result, on the main thread. */
    fun refresh(ctx: Context, done: ((ok: Boolean, message: String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val place = p.place
        if (place == null || p.prayerSource == PrayerStore.SRC_DEVICE) {
            PrayerStore.status = ""
            done?.invoke(true, "")
            return
        }
        if (busy) return
        busy = true
        lastTry = System.currentTimeMillis()
        Thread {
            val sig = PrayerStore.signature(p)
            val tz = Days.tz(place)
            val today = Days.today(tz)
            var ok = false
            val msg = try {
                if (p.prayerSource == PrayerStore.SRC_MOSQUE) {
                    val slug = p.mosqueSlug ?: throw IllegalStateException("no mosque chosen")
                    val y = Mawaqit.times(slug, p.mosqueName, place)
                    PrayerStore.put(app, sig, y.days, emptyMap(), today)
                    ok = true
                    "Times from ${p.mosqueName ?: "the mosque"} on mawaqit.net"
                } else {
                    val days = HashMap<Int, IntArray>()
                    val hijri = HashMap<Int, IntArray>()
                    val c = Calendar.getInstance(tz)
                    repeat(2) {
                        val m = Aladhan.month(place, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, p.athanMethod, p.asrHanafi, p.highLat)
                        days.putAll(m.days)
                        hijri.putAll(m.hijri)
                        c.set(Calendar.DAY_OF_MONTH, 1)
                        c.add(Calendar.MONTH, 1)
                    }
                    if (days.isEmpty()) throw IllegalStateException("no times in the answer")
                    PrayerStore.put(app, sig, days, hijri, today)
                    ok = true
                    "Times from aladhan.com"
                }
            } catch (e: Exception) {
                val src = if (p.prayerSource == PrayerStore.SRC_MOSQUE) "mawaqit.net" else "aladhan.com"
                "Couldn't update from $src (${if (e is IllegalStateException) e.message else Net.why(e)}); the TV works the times out itself until then"
            }
            PrayerStore.status = msg
            busy = false
            PrayerStore.notifyChanged()
            AthanScheduler.schedule(app)
            main.post { done?.invoke(ok, msg) }
        }.start()
    }
}
