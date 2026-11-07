package app.etv.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent

/**
 * Sets an alarm for the next athan. Android wakes eTV for it even when another app is in front;
 * the home screen also checks once a minute, in case an alarm comes late.
 */
object AthanScheduler {
    const val ACTION_ALARM = "app.etv.launcher.ATHAN_ALARM"
    private const val EXTRA_PRAYER = "prayer"
    private const val EXTRA_DAY = "day"
    private const val EXTRA_AT = "at"

    /** An alarm that comes later than this (the TV was asleep) is skipped rather than played late. */
    private const val LATE_MS = 10 * 60_000L

    private fun pending(ctx: Context, intent: Intent?): PendingIntent {
        val i = intent ?: Intent(ctx, AthanReceiver::class.java).setAction(ACTION_ALARM)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 4711, i, flags)
    }

    /** Sets (or clears) the alarm for the next athan. Safe to call any time. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val p = Prefs(app)
        try { am.cancel(pending(app, null)) } catch (_: Exception) {}
        if (!p.athanOn) return
        val next = PrayerStore.next(app, p, System.currentTimeMillis() + 1_000L, athanOnly = true) ?: return
        val intent = Intent(app, AthanReceiver::class.java).setAction(ACTION_ALARM)
            .putExtra(EXTRA_PRAYER, next.prayer.ordinal)
            .putExtra(EXTRA_DAY, next.day)
            .putExtra(EXTRA_AT, next.at)
        val pi = pending(app, intent)
        try {
            when {
                Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms() ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
                else -> am.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, null), pi)
            }
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
                else am.setExact(AlarmManager.RTC_WAKEUP, next.at, pi)
            } catch (_: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, next.at, pi)
            }
        }
    }

    fun onAlarm(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        val prayer = Prayer.values().getOrNull(intent.getIntExtra(EXTRA_PRAYER, -1))
        val day = intent.getIntExtra(EXTRA_DAY, 0)
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val late = System.currentTimeMillis() - at
        if (prayer != null && p.athanOn && p.athanFor(prayer) && late in -60_000L..LATE_MS) fire(ctx, prayer, day)
        schedule(ctx)
    }

    /**
     * The home screen's once-a-minute check: plays an athan whose time has just come, if the alarm
     * hasn't already.
     */
    fun checkDue(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.athanOn) return
        val now = System.currentTimeMillis()
        val due = PrayerStore.next(ctx, p, now - 2 * 60_000L, athanOnly = true) ?: return
        if (due.at <= now) fire(ctx, due.prayer, due.day)
    }

    private fun fire(ctx: Context, prayer: Prayer, day: Int) {
        val p = Prefs(ctx)
        val key = "$day-${prayer.ordinal}"
        if (p.lastAthan == key) return
        p.lastAthan = key
        // The TV is in standby: nobody is there to hear it.
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return
        AthanService.start(ctx, prayer, test = false)
    }
}

/** Hears the athan alarm, and sets it again when the TV starts, eTV updates or the clock changes. */
class AthanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AthanScheduler.ACTION_ALARM -> AthanScheduler.onAlarm(context, intent)
            else -> {
                AthanScheduler.schedule(context)
                PrayerSync.refreshIfDue(context)
            }
        }
    }
}

/**
 * Plays the athan, then the dua after it.
 *
 * First whatever is playing on the TV is paused (through the apps' own media controls, and
 * Android's audio focus), and only once it has stopped does the athan begin, with the athan
 * screen over everything. Afterwards the TV's volume is put back exactly as it was, the athan
 * screen closes, and what was playing carries on.
 *
 * Nothing on the remote ends it early: not OK, Back, Stop or Pause. Home or an app's own button
 * brings the athan screen straight back, and anything that starts playing is paused again.
 * Only your code typed on the remote's number keys ([isCode]) ends it.
 */
class AthanService : Service() {

    companion object {
        private const val ACTION_PLAY = "app.etv.launcher.ATHAN_PLAY"
        private const val EXTRA_PRAYER = "prayer"
        private const val EXTRA_TEST = "test"
        private const val CHANNEL = "athan"
        private const val NOTE_ID = 4712
        private const val MAX_MS = 15 * 60_000L

        const val PHASE_ATHAN = 0
        const val PHASE_GAP = 1
        const val PHASE_DUA = 2

        /** The athan playing now, if any. */
        @Volatile var current: AthanService? = null
            private set

        val isPlaying: Boolean get() = current != null

        /** Starts the athan for [prayer]. [test] plays it now, from Settings. */
        fun start(ctx: Context, prayer: Prayer, test: Boolean): Boolean {
            val i = Intent(ctx, AthanService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_PRAYER, prayer.ordinal)
                .putExtra(EXTRA_TEST, test)
            return try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                // Android refused a service from the background: the athan screen can start it instead.
                try {
                    ctx.startActivity(
                        Intent(ctx, AthanActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(AthanActivity.EXTRA_START, prayer.ordinal),
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        /**
         * The only way to end the athan early: the code typed on the remote's number keys. eTV
         * comes with one built in; Settings → Athan can replace it with your own. This repository
         * is public, so neither is ever kept as digits: only a slow, salted hash of it.
         */
        private const val BUILT_IN_SALT = "23ebccfd4f591bfcd89d80d1ff6cdf2f"
        private const val BUILT_IN_HASH = "f4080c4044024f58920bc2844846740ae35231a53ec65f64bcc95d381fd4e9cb"
        private const val BUILT_IN_LENGTH = 9
        private const val BUILT_IN_ROUNDS = 150_000
        private const val OWN_ROUNDS = 150_000

        /** You've replaced the built-in code with your own. */
        fun hasOwnCode(ctx: Context) = Prefs(ctx).athanCodeHash.isNotEmpty()

        /** How many digits the code has. */
        fun codeLength(ctx: Context) = if (hasOwnCode(ctx)) Prefs(ctx).athanCodeLength else BUILT_IN_LENGTH

        /** True when [digits] is the code. Slow on purpose: call it off the main thread. */
        fun isCode(ctx: Context, digits: String): Boolean {
            val p = Prefs(ctx)
            return if (p.athanCodeHash.isNotEmpty()) {
                digits.length == p.athanCodeLength && hash(digits, p.athanCodeSalt, p.athanCodeRounds) == p.athanCodeHash
            } else {
                digits.length == BUILT_IN_LENGTH && hash(digits, BUILT_IN_SALT, BUILT_IN_ROUNDS) == BUILT_IN_HASH
            }
        }

        /** Sets your own code, or goes back to the built-in one with an empty [digits]. */
        fun setCode(ctx: Context, digits: String) {
            val p = Prefs(ctx)
            if (digits.isEmpty()) {
                p.athanCodeHash = ""
                p.athanCodeSalt = ""
                p.athanCodeLength = 0
                return
            }
            val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            p.athanCodeSalt = salt
            p.athanCodeRounds = OWN_ROUNDS
            p.athanCodeHash = hash(digits, salt, OWN_ROUNDS)
            p.athanCodeLength = digits.length
        }

        /** PBKDF2-HMAC-SHA1 of [digits], hex. */
        internal fun hash(digits: String, salt: String, rounds: Int): String {
            val spec = javax.crypto.spec.PBEKeySpec(digits.toCharArray(), salt.toByteArray(), rounds, 256)
            val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return key.joinToString("") { "%02x".format(it) }
        }

        /** Ends the athan (and the dua) now: only for the code. */
        fun stopWithCode() {
            current?.finishAthan()
        }
    }

    var prayer: Prayer = Prayer.DHUHR
        private set
    var phase = PHASE_ATHAN
        private set
    var test = false
        private set

    /** The athan's own sound turned out to be missing, so Makkah's played instead. */
    var usedFallback = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var paused: MutableList<MediaController> = ArrayList()
    private var savedVolume = -1
    private var savedMuted = false
    private var finished = true

    /** The sound is the athan's: everything else has been paused and the athan is playing. */
    private var started = false

    /** Something was playing that eTV couldn't see: it was paused with the remote's Pause key. */
    private var pausedByKey = false

    /** The apps eTV paused, by package: their media sessions are looked up again to carry on. */
    private val pausedPackages = LinkedHashSet<String>()
    private var focusLost = false
    private var lastScreenAt = 0L

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Something else wants to play: the guard takes the sound back for the athan.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            focusLost = true
        }
    }

    /**
     * Keeps the athan in charge while it plays: pauses anything that starts playing, takes the
     * sound back, and brings the athan screen back if Home or another app hid it.
     */
    private val guard = object : Runnable {
        override fun run() {
            if (finished) return
            val p = Prefs(this@AthanService)
            if (p.athanPause) {
                pauseOthers()
                if (focusLost) {
                    focusLost = false
                    abandonFocus()
                    requestFocus(transient = p.athanResume)
                }
            }
            if (!AthanActivity.visible && System.currentTimeMillis() - lastScreenAt > 1_200L) showScreen()
            handler.postDelayed(this, 500L)
        }
    }

    private val timeout = Runnable { finishAthan() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this promptly after startForegroundService, whatever the request.
        goForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                val pr = Prayer.values().getOrNull(intent.getIntExtra(EXTRA_PRAYER, -1)) ?: Prayer.DHUHR
                begin(pr, intent.getBooleanExtra(EXTRA_TEST, false))
            }
            else -> if (finished) stopSelfNow()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!finished) finishAthan()
        super.onDestroy()
    }

    // ---------------- start ----------------

    private fun begin(pr: Prayer, isTest: Boolean) {
        // An athan already playing just carries on (the alarm and the minute check can both fire).
        if (!finished) return
        finished = false
        started = false
        focusLost = false
        current = this
        prayer = pr
        test = isTest
        usedFallback = false
        paused = ArrayList()
        pausedPackages.clear()
        pausedByKey = false
        val p = Prefs(this)

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eTV:athan").apply { acquire(MAX_MS + 60_000L) }
        } catch (_: Exception) {
        }
        saveVolume()
        goForeground()
        handler.postDelayed(timeout, MAX_MS)

        // 1. Stop whatever is playing: through its own media controls, or (for anything eTV can't
        // see) the remote's Pause key; and the audio focus, which makes the rest pause too. Then
        // cover the screen.
        if (p.athanPause) {
            val soundBefore = audio().isMusicActive
            pauseOthers()
            if (soundBefore && paused.isEmpty()) {
                pausedByKey = true
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            requestFocus(transient = p.athanResume)
        }
        showScreen()

        // 2. Once the TV has really gone quiet (three seconds at most), the athan.
        val since = System.currentTimeMillis()
        val waitForQuiet = object : Runnable {
            override fun run() {
                if (finished) return
                val waited = System.currentTimeMillis() - since
                val quiet = paused.none { isPlaying(it) } && !audio().isMusicActive
                if (!quiet && waited < 3_000L) {
                    if (waited > 1_000L) pauseOthers() // something slow to listen: ask again
                    handler.postDelayed(this, 100L)
                    return
                }
                started = true
                setVolume(p.athanVolume)
                openSession()
                playAthan()
                handler.postDelayed(guard, 1_000L)
            }
        }
        handler.postDelayed(waitForQuiet, if (p.athanPause) 150L else 0L)
    }

    private fun showScreen() {
        lastScreenAt = System.currentTimeMillis()
        try {
            startActivity(
                Intent(this, AthanActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (_: Exception) {
            // Without the screen the athan still plays; the guard tries again.
        }
    }

    // ---------------- playing ----------------

    private fun playAthan() {
        phase = PHASE_ATHAN
        val p = Prefs(this)
        val chosen = AthanSounds.find(this, p.athanSound(prayer))
        val fallback = AthanSounds.find(this, if (prayer == Prayer.FAJR) AthanSounds.DEFAULT_FAJR else AthanSounds.DEFAULT)
        val tries = listOfNotNull(chosen, fallback).distinctBy { it.id }
        playFirst(tries, onDone = { afterAthan() })
        // Fetch it for next time if it was missing.
        if (chosen != null && !AthanSounds.isReady(this, chosen)) AthanSounds.ensure(this, chosen)
    }

    private fun afterAthan() {
        if (finished) return
        val p = Prefs(this)
        val dua = p.dua.takeIf { it.isNotEmpty() }?.let { AthanSounds.find(this, it) }
        if (dua == null || !AthanSounds.isReady(this, dua)) {
            if (dua != null) AthanSounds.ensure(this, dua)
            finishAthan()
            return
        }
        phase = PHASE_GAP
        releasePlayer()
        // A short quiet moment between the athan and the dua.
        handler.postDelayed({
            if (finished) return@postDelayed
            phase = PHASE_DUA
            updateSession()
            playFirst(listOf(dua), onDone = { finishAthan() })
        }, 1_800L)
    }

    /** Plays the first of [sounds] that works; [onDone] when it ends (or none could play). */
    private fun playFirst(sounds: List<AthanSounds.Sound>, onDone: () -> Unit) {
        releasePlayer()
        for ((i, s) in sounds.withIndex()) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                if (!AthanSounds.setSource(this, mp, s)) {
                    mp.release()
                    continue
                }
                mp.prepare()
                mp.setOnCompletionListener { onDone() }
                mp.setOnErrorListener { _, _, _ ->
                    handler.post { onDone() }
                    true
                }
                mp.start()
                player = mp
                if (i > 0) usedFallback = true
                return
            } catch (e: Exception) {
                mp.release()
            }
        }
        handler.post { onDone() }
    }

    private fun releasePlayer() {
        player?.let {
            try { it.setOnCompletionListener(null) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    /** How far the current part (athan or dua) has got, 0–1, or -1 when unknown. */
    fun progress(): Float {
        val mp = player ?: return if (phase == PHASE_GAP) 1f else -1f
        return try {
            val d = mp.duration
            if (d > 0) (mp.currentPosition.toFloat() / d).coerceIn(0f, 1f) else -1f
        } catch (_: Exception) {
            -1f
        }
    }

    // ---------------- the rest of the TV ----------------

    private fun isPlaying(c: MediaController): Boolean {
        val st = try { c.playbackState?.state } catch (_: Exception) { null }
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING ||
            st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING
    }

    /**
     * Pauses every other app that's playing (when eTV may see them: the Now playing permission),
     * and remembers them, so they carry on afterwards.
     */
    private fun pauseOthers() {
        if (!NowPlayingWatcher.hasAccess(this)) return
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            for (c in msm.getActiveSessions(NowPlayingWatcher.component(this))) {
                if (c.packageName == packageName || !isPlaying(c)) continue
                try { c.transportControls.pause() } catch (_: Exception) {}
                if (paused.none { it.sessionToken == c.sessionToken }) paused.add(c)
                pausedPackages.add(c.packageName)
            }
        } catch (_: Exception) {
        }
    }

    /** Presses a media key on the remote's behalf; it goes to the app that's playing. */
    private fun mediaKey(code: Int) {
        try {
            val am = audio()
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {
        }
    }

    /**
     * Carries on with what was playing: once the athan screen has gone and the app is in front
     * again, and again a little later if it didn't take. Apps often rebuild their player while
     * hidden, so their media sessions are looked up afresh each time, by app.
     */
    private fun resumeOthers(packages: Set<String>, old: List<MediaController>, byKey: Boolean) {
        if (packages.isEmpty() && !byKey) return
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        fun sessionsNow(): List<MediaController> {
            if (!NowPlayingWatcher.hasAccess(app)) return old
            return try {
                val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                msm.getActiveSessions(NowPlayingWatcher.component(app)).filter { it.packageName in packages }
            } catch (_: Exception) {
                old
            }
        }
        fun carryOn() {
            if (current === this@AthanService || isPlaying) return // another athan began meanwhile
            for (c in sessionsNow().ifEmpty { old }) {
                try {
                    if (!isPlaying(c)) c.transportControls.play()
                } catch (_: Exception) {
                }
            }
            if (byKey && !(app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
        main.postDelayed({ carryOn() }, 1_200L)
        main.postDelayed({ carryOn() }, 3_000L)
        main.postDelayed({ carryOn() }, 6_000L)
    }

    private fun audio() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Asks the other apps to be quiet. Transient: they carry on afterwards; otherwise they stay paused. */
    private fun requestFocus(transient: Boolean) {
        val type = if (transient) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(type)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .build()
                focus = r
                audio().requestAudioFocus(r)
            } else {
                @Suppress("DEPRECATION")
                audio().requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, type)
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focus?.let { audio().abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio().abandonAudioFocus(focusListener)
            }
        } catch (_: Exception) {
        }
        focus = null
    }

    /** Remembers the TV's volume and mute exactly as they are, to put them back afterwards. */
    private fun saveVolume() {
        try {
            val am = audio()
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedMuted = Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            savedVolume = -1
            savedMuted = false
        }
    }

    /** Sets the TV's volume for the athan (and un-mutes it), when a level is chosen in Settings. */
    private fun setVolume(percent: Int) {
        if (percent <= 0) return
        try {
            val am = audio()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            val v = Math.round(max * percent / 100f).coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * Puts the TV's volume and mute back exactly as they were before the athan, whatever changed
     * them in between, so what was playing is heard again at the same level.
     */
    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            val am = audio()
            if (Build.VERSION.SDK_INT >= 23) {
                val mutedNow = am.isStreamMute(AudioManager.STREAM_MUSIC)
                if (mutedNow && !savedMuted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != savedVolume) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            }
            if (Build.VERSION.SDK_INT >= 23 && savedMuted && !am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (_: Exception) {
        }
        savedVolume = -1
    }

    /**
     * A media session, so the remote's media keys come to the athan, which ignores them: Pause,
     * Stop and the rest neither end it nor start anything else playing.
     */
    private fun openSession() {
        try {
            val s = MediaSession(this, "eTV athan")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() {}
                override fun onStop() {}
                override fun onPlay() {}
                override fun onSkipToNext() {}
                override fun onSkipToPrevious() {}
                override fun onFastForward() {}
                override fun onRewind() {}
                override fun onSeekTo(pos: Long) {}
            }, handler)
            @Suppress("DEPRECATION")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            session = s
            updateSession()
            s.isActive = true
        } catch (_: Exception) {
        }
    }

    private fun updateSession() {
        val s = session ?: return
        try {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build(),
            )
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, if (phase == PHASE_DUA) "الدعاء بعد الأذان" else "أذان ${prayer.ar}")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "eTV")
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    // ---------------- ending ----------------

    /** Ends the athan (when it and the dua are over, or for the code) and puts the TV back as it was. */
    fun finishAthan() {
        if (finished) return
        cleanUp()
        stopSelfNow()
    }

    private fun cleanUp() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        // In this order: the sound goes back to the other apps, the volume as it was, the athan
        // screen closes, and then (once the app is in front again) what was playing carries on.
        abandonFocus()
        restoreVolume()
        if (current === this) current = null
        AthanActivity.closeNow()
        val was = paused.toList()
        val packages = LinkedHashSet(pausedPackages)
        val byKey = pausedByKey
        paused = ArrayList()
        pausedPackages.clear()
        pausedByKey = false
        if (Prefs(this).athanResume) resumeOthers(packages, was, byKey)
        started = false
        try { wake?.release() } catch (_: Exception) {}
        wake = null
    }

    private fun stopSelfNow() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Athan", NotificationManager.IMPORTANCE_LOW))
            }
            val b = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val n = b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle("أذان ${prayer.ar}")
                .setContentText("eTV  ·  Athan")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTE_ID, n)
        } catch (_: Exception) {
        }
    }
}
