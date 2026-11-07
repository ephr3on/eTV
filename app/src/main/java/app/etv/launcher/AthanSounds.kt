package app.etv.launcher

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The athan recordings and the dua after the athan.
 *
 * The Makkah athan and Makkah's Fajr athan come inside eTV, so they always work, even without
 * internet. The others are fetched once from the Internet Archive when you choose them (the
 * "Azaan of World Mosques" collection, marked public domain) and kept on the TV. Sheikh
 * al-Sha'rawi's dua is fetched the same way. You can also use any recording by its link.
 */
object AthanSounds {
    class Sound(
        val id: String,
        val name: String,
        /** Recorded for Fajr ("as-salatu khayrun min an-nawm"). */
        val fajr: Boolean = false,
        /** Inside the APK, under assets/. */
        val asset: String? = null,
        val url: String? = null,
        /** Another copy, tried when [url] doesn't work. */
        val mirror: String? = null,
    )

    const val DEFAULT = "makkah"
    const val DEFAULT_FAJR = "makkah_fajr"
    const val DEFAULT_DUA = "shaarawy"
    const val CUSTOM = "custom"
    const val CUSTOM_DUA = "custom_dua"

    private const val WORLD = "https://archive.org/download/azaan-of-world-mosques-english/"
    private fun world(file: String) = WORLD + Net.enc(file)

    val athans: List<Sound> = listOf(
        Sound(DEFAULT, "Makkah", asset = "athan/makkah.mp3"),
        Sound(DEFAULT_FAJR, "Makkah  ·  Fajr", fajr = true, asset = "athan/makkah_fajr.mp3"),
        Sound("makkah_mulla", "Makkah  ·  Ali Mulla", url = world("4th Haram Makkah by Mulla.mp3")),
        Sound("makkah_abdulmalik", "Makkah  ·  Abdulmalik al-Mulla", url = world("عبدالملك الملا_مكة (www.alqurantranslation.com).mp3")),
        Sound("makkah_3", "Makkah  ·  another recording", url = world("3rd Haram Makkah.mp3")),
        Sound("aqsa", "Al-Aqsa  ·  Naji Qazzaz", url = world("Al-Aqsa Masjid Naaji Qazzaaz.mp3")),
        Sound("cairo_basit", "Cairo  ·  Abdul Basit Abdul Samad", url = world("Cairo Abdul Baset.mp3")),
        Sound("cairo_husary", "Cairo  ·  Al-Husary", url = world("Cairo Al Hosari.mp3")),
        Sound("cairo_banna", "Cairo  ·  Al-Banna", url = world("Cairo Mohammed Al Banna.mp3")),
        Sound("cairo_shuaisha", "Cairo  ·  Shuaisha", url = world("Cairo Shuaisha.mp3")),
        Sound("cairo_fajr", "Cairo  ·  Fajr", fajr = true, url = world("Cairo AlFajr.mp3")),
        Sound("uaq_fajr", "Umm al-Quwain  ·  Fajr", fajr = true, url = world("AlFajr Umm alQaiwain.mp3")),
        Sound("tunisia", "Tunisia", url = world("Tunisia.mp3")),
        Sound("oman", "Oman", url = world("Oman.mp3")),
    )

    val duas: List<Sound> = listOf(
        Sound(
            DEFAULT_DUA, "Sheikh al-Sha'rawi",
            url = "https://archive.org/download/Islamic_Tape-258_uP_bY_mUSLEm/sharawy_doaa_24.mp3",
            mirror = "https://archive.org/download/Islamic_Tape-258_uP_bY_mUSLEm/sharawy_doaa_24.ogg",
        ),
    )

    /** A sound by id, including your own links. */
    fun find(ctx: Context, id: String): Sound? {
        val p = Prefs(ctx)
        return when (id) {
            CUSTOM -> p.customAthanUrl.takeIf { it.isNotBlank() }?.let { Sound(CUSTOM, "Your own recording", url = it) }
            CUSTOM_DUA -> p.customDuaUrl.takeIf { it.isNotBlank() }?.let { Sound(CUSTOM_DUA, "Your own recording", url = it) }
            else -> athans.firstOrNull { it.id == id } ?: duas.firstOrNull { it.id == id }
        }
    }

    fun name(ctx: Context, id: String): String = when {
        id.isEmpty() -> "Off"
        else -> find(ctx, id)?.name ?: "Makkah"
    }

    // ---- what's on the TV ----

    private const val DOWNLOADING = 1
    private const val FAILED = 2
    private val state = ConcurrentHashMap<String, Int>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever a download starts, finishes or fails (Settings shows it). */
    var onChange: (() -> Unit)? = null

    private fun changed() = main.post { onChange?.invoke() }

    private fun dir(ctx: Context) = File(ctx.filesDir, "athan").apply { mkdirs() }

    /** Where a downloaded sound is kept: named after its link, so a new link is fetched afresh. */
    fun file(ctx: Context, s: Sound): File? {
        val url = s.url ?: return null
        return File(dir(ctx), "${s.id}-${Integer.toHexString(url.hashCode())}.mp3")
    }

    fun isReady(ctx: Context, s: Sound): Boolean = s.asset != null || file(ctx, s)?.exists() == true

    fun isDownloading(s: Sound) = state[s.id] == DOWNLOADING
    fun hasFailed(s: Sound) = state[s.id] == FAILED

    /** A word for Settings: ready, being fetched, or failed. */
    fun statusOf(ctx: Context, s: Sound): String = when {
        isReady(ctx, s) -> ""
        isDownloading(s) -> "downloading…"
        hasFailed(s) -> "couldn't download"
        else -> "downloads once"
    }

    /** Fetches [s] if it isn't on the TV yet. Failed downloads are retried after an hour, or at once with [force]. */
    fun ensure(ctx: Context, s: Sound, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        if (isReady(ctx, s)) {
            done?.invoke(true)
            return
        }
        val url = s.url ?: return
        if (state[s.id] == DOWNLOADING) return
        if (!force && state[s.id] == FAILED && System.currentTimeMillis() - (failedAt[s.id] ?: 0L) < 60 * 60_000L) return
        val target = file(ctx, s) ?: return
        val app = ctx.applicationContext
        state[s.id] = DOWNLOADING
        changed()
        Thread {
            val tmp = File(target.path + ".part")
            val ok = try {
                val got = listOfNotNull(url, s.mirror).any { u ->
                    try {
                        Net.download(u, tmp)
                        playable(tmp)
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!got) throw IllegalStateException("not a sound")
                // Drop older copies of the same sound (an earlier link).
                dir(app).listFiles()?.filter { it.name.startsWith("${s.id}-") && it.name != target.name }?.forEach { it.delete() }
                tmp.renameTo(target)
            } catch (e: Exception) {
                tmp.delete()
                false
            }
            if (ok) state.remove(s.id) else {
                state[s.id] = FAILED
                failedAt[s.id] = System.currentTimeMillis()
            }
            changed()
            main.post { done?.invoke(ok) }
        }.start()
    }

    /** Fetches every sound the current choices need (quietly; nothing happens if they're all here). */
    fun ensureChosen(ctx: Context, force: Boolean = false) {
        val p = Prefs(ctx)
        if (!p.athanOn) return
        val ids = Prayer.withAthan.filter { p.athanFor(it) }.map { p.athanSound(it) }.toMutableSet()
        if (p.dua.isNotEmpty()) ids.add(p.dua)
        for (id in ids) find(ctx, id)?.let { ensure(ctx, it, force) }
    }

    /** Sounds the current choices need that aren't on the TV yet. */
    fun missing(ctx: Context): List<Sound> {
        val p = Prefs(ctx)
        val ids = Prayer.withAthan.filter { p.athanFor(it) }.map { p.athanSound(it) }.toMutableSet()
        if (p.dua.isNotEmpty()) ids.add(p.dua)
        return ids.mapNotNull { find(ctx, it) }.filter { !isReady(ctx, it) }
    }

    private fun playable(f: File): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(f.path)
            mp.prepare()
            mp.duration > 1_000 || mp.duration < 0
        } catch (e: Exception) {
            false
        } finally {
            mp.release()
        }
    }

    /**
     * Points [mp] at [s]. False when it isn't on the TV (yet).
     */
    fun setSource(ctx: Context, mp: MediaPlayer, s: Sound): Boolean {
        val asset = s.asset
        if (asset != null) {
            ctx.assets.openFd(asset).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            return true
        }
        val f = file(ctx, s) ?: return false
        if (!f.exists()) return false
        mp.setDataSource(f.path)
        return true
    }
}
