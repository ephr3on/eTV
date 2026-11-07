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
            mirror = "https://archive.org/download/alshaarawi/alshaarawi-doaa-24.mp3",
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
    private val failedWhy = ConcurrentHashMap<String, String>()
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
        hasFailed(s) -> "couldn't download: ${whyFailed(s)}"
        else -> "downloads once"
    }

    /** Why the last download of [s] failed, in a few words. */
    fun whyFailed(s: Sound): String = failedWhy[s.id] ?: "try again"

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
            val raw = File(dir(app), "${s.id}.download")
            // Named .mp3 from the start: some TVs' players go by the name.
            val clean = File(dir(app), "${s.id}.new.mp3")
            var why = "couldn't connect"
            var ok = false
            for (u in listOfNotNull(url, s.mirror)) {
                try {
                    Net.download(u, raw)
                    if (!cleanAudio(raw, clean)) {
                        why = "the link isn't a recording"
                        continue
                    }
                    // Only a hint: the file is kept either way, and played with the same player later.
                    if (!playable(clean)) android.util.Log.w("eTV", "athan: ${s.id} didn't open in the check")
                    // Drop older copies of the same sound (an earlier link).
                    dir(app).listFiles()?.filter { it.name.startsWith("${s.id}-") && it.name != target.name }?.forEach { it.delete() }
                    target.delete()
                    ok = clean.renameTo(target)
                    if (ok) break
                    why = "couldn't save it on the TV"
                } catch (e: Exception) {
                    why = Net.why(e)
                }
            }
            raw.delete()
            clean.delete()
            if (ok) {
                state.remove(s.id)
                failedWhy.remove(s.id)
            } else {
                state[s.id] = FAILED
                failedAt[s.id] = System.currentTimeMillis()
                failedWhy[s.id] = why
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

    /**
     * Copies the recording in [from] to [to] without its ID3 tags (cover pictures and all), which
     * some TVs' players refuse. False when there's no MP3 or Ogg audio in it (an error page, say).
     */
    internal fun cleanAudio(from: File, to: File): Boolean {
        val b = from.readBytes()
        if (b.size < 1024) return false
        if (b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) {
            to.writeBytes(b)
            return true
        }
        var start = 0
        // ID3v2 tags at the start (there can be more than one).
        while (b.size - start > 10 && b[start] == 'I'.code.toByte() && b[start + 1] == 'D'.code.toByte() && b[start + 2] == '3'.code.toByte()) {
            val flags = b[start + 5].toInt()
            val size = ((b[start + 6].toInt() and 0x7f) shl 21) or ((b[start + 7].toInt() and 0x7f) shl 14) or
                ((b[start + 8].toInt() and 0x7f) shl 7) or (b[start + 9].toInt() and 0x7f)
            start += 10 + size + if (flags and 0x10 != 0) 10 else 0
        }
        // The first real MPEG audio frame (two in a row, so it's not a chance match).
        val limit = minOf(b.size - 4, start + 256 * 1024)
        var first = -1
        var i = start.coerceAtLeast(0)
        while (i < limit) {
            val len = frameLength(b, i)
            if (len > 0 && (i + len + 4 > b.size || frameLength(b, i + len) > 0)) {
                first = i
                break
            }
            i++
        }
        if (first < 0) return false
        var end = b.size
        // An ID3v1 tag at the end.
        if (end - 128 > first && b[end - 128] == 'T'.code.toByte() && b[end - 127] == 'A'.code.toByte() && b[end - 126] == 'G'.code.toByte()) end -= 128
        to.outputStream().use { it.write(b, first, end - first) }
        return true
    }

    /** The length of the MPEG audio frame starting at [i], or 0 if there isn't one. */
    private fun frameLength(b: ByteArray, i: Int): Int {
        if (i < 0 || i + 4 > b.size) return 0
        val h1 = b[i].toInt() and 0xff
        val h2 = b[i + 1].toInt() and 0xff
        val h3 = b[i + 2].toInt() and 0xff
        if (h1 != 0xff || (h2 and 0xe0) != 0xe0) return 0
        val version = (h2 shr 3) and 3 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        val layer = (h2 shr 1) and 3 // 1 = layer III
        val bitrateIdx = (h3 shr 4) and 15
        val rateIdx = (h3 shr 2) and 3
        val padding = (h3 shr 1) and 1
        if (version == 1 || layer != 1 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return 0
        val mpeg1 = version == 3
        val kbps = if (mpeg1) intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIdx]
        else intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIdx]
        val rate = intArrayOf(44100, 48000, 32000)[rateIdx] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        return (if (mpeg1) 144 else 72) * kbps * 1000 / rate + padding
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
