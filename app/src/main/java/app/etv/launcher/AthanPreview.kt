package app.etv.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Lets you hear an athan or a dua in Customise before choosing it.
 *
 * Moving onto a recording plays it after a short pause (so just passing over one stays quiet),
 * fading in from the start. A recording that isn't on the TV yet is fetched first, once, and kept,
 * so choosing it afterwards is instant. Play / Pause on the remote pauses it, or plays it again.
 *
 * This is only a preview: it doesn't touch the athan itself, the TV's volume, or the remote lock
 * (for that, "Listen now" plays the real thing). Whatever else is playing pauses while it plays.
 *
 * [show] tells the panel what to write under a recording: "" puts back its usual line.
 */
class AthanPreview(ctx: Context, private val show: (AthanSounds.Sound, String) -> Unit) {
    private val app = ctx.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** The recording you're on (it plays, or is about to, or is being fetched). */
    private var wanted: AthanSounds.Sound? = null
    private var fetching: String? = null
    private var paused = false
    private var startedAt = 0L
    private var lastText = ""
    private var hasFocus = false

    private val audio: AudioManager get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            when {
                change == AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    wanted?.let { s -> stopPlayer(); say(s, "") }
                }
                change < 0 -> pause()
            }
        }
    }

    /** Hear [s] in a moment; null stops. Moving to another recording stops the one playing. */
    fun want(s: AthanSounds.Sound?, delayMs: Long = 650L) {
        val before = wanted
        handler.removeCallbacks(begin)
        handler.removeCallbacks(tick)
        stopPlayer()
        if (before != null && before.id != s?.id) say(before, "")
        wanted = s
        fetching = null
        if (s != null) handler.postDelayed(begin, delayMs)
    }

    /** Play / Pause: pauses, carries on, or plays it again from the start. */
    fun toggle() {
        val s = wanted ?: return
        val mp = player
        when {
            mp == null -> {
                handler.removeCallbacks(begin)
                begin.run()
            }
            paused -> {
                takeFocus()
                try {
                    mp.start()
                    paused = false
                    handler.post(tick)
                } catch (_: Exception) {
                    stopPlayer()
                    say(s, CANT_PLAY)
                }
            }
            else -> pause()
        }
    }

    /** Everything off: the panel closed, or Customise went away. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        wanted?.let { say(it, "") }
        wanted = null
        fetching = null
        if (hasFocus) {
            @Suppress("DEPRECATION")
            try { audio.abandonAudioFocus(focusListener) } catch (_: Exception) {}
            hasFocus = false
        }
    }

    // ---------------- playing ----------------

    private val begin = object : Runnable {
        override fun run() {
            val s = wanted ?: return
            when {
                AthanSounds.isReady(app, s) -> play(s)
                // We asked for it and it didn't come: say why, and stop asking.
                fetching == s.id && AthanSounds.hasFailed(s) ->
                    say(s, "Couldn't fetch it to play (${AthanSounds.whyFailed(s)}). OK still chooses it.")
                else -> {
                    if (fetching != s.id) {
                        fetching = s.id
                        if (!AthanSounds.isDownloading(s)) AthanSounds.ensure(app, s, force = true)
                    }
                    say(s, "Fetching it to play…  (once; it's kept on the TV)")
                    handler.postDelayed(this, 500L)
                }
            }
        }
    }

    private fun play(s: AthanSounds.Sound) {
        stopPlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            if (!AthanSounds.setSource(app, mp, s)) {
                mp.release()
                say(s, "It isn't on the TV yet")
                return
            }
            mp.prepare()
            mp.setOnCompletionListener { handler.post { finished(s) } }
            mp.setOnErrorListener { _, _, _ ->
                handler.post {
                    stopPlayer()
                    say(s, CANT_PLAY)
                }
                true
            }
            mp.setVolume(0f, 0f)
            takeFocus()
            mp.start()
            player = mp
            paused = false
            startedAt = SystemClock.uptimeMillis()
            handler.post(tick)
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            say(s, CANT_PLAY)
        }
    }

    /** Fades in over the first second and keeps the time under the recording up to date. */
    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val s = wanted ?: return
            if (paused) return
            val since = SystemClock.uptimeMillis() - startedAt
            val v = (since / FADE_MS.toFloat()).coerceIn(0f, 1f)
            try { mp.setVolume(v, v) } catch (_: Exception) {}
            say(s, "▶  Playing  ${position(mp)}  ·  OK chooses it")
            handler.postDelayed(this, 250L)
        }
    }

    private fun pause() {
        val mp = player ?: return
        val s = wanted ?: return
        if (paused) return
        try { mp.pause() } catch (_: Exception) {}
        paused = true
        handler.removeCallbacks(tick)
        say(s, "❚❚  Paused  ${position(mp)}  ·  Play / Pause carries on")
    }

    private fun finished(s: AthanSounds.Sound) {
        val length = player?.let { try { clock(it.duration) } catch (_: Exception) { "" } } ?: ""
        stopPlayer()
        if (wanted?.id != s.id) return
        say(s, "Heard to the end${if (length.isNotEmpty()) " ($length)" else ""}  ·  OK chooses it  ·  Play / Pause again")
    }

    private fun stopPlayer() {
        handler.removeCallbacks(tick)
        player?.let {
            try { it.setOnCompletionListener(null) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        paused = false
    }

    private fun takeFocus() {
        if (hasFocus) return
        @Suppress("DEPRECATION")
        hasFocus = try {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun say(s: AthanSounds.Sound, text: String) {
        val key = "${s.id}|$text"
        if (key == lastText) return
        lastText = key
        show(s, text)
    }

    private fun position(mp: MediaPlayer): String = try {
        val d = mp.duration
        if (d > 0) "${clock(mp.currentPosition)} / ${clock(d)}" else clock(mp.currentPosition)
    } catch (_: Exception) {
        ""
    }

    private fun clock(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val FADE_MS = 1_000L
        const val CANT_PLAY = "Couldn't play it on this TV. OK still chooses it."
    }
}
