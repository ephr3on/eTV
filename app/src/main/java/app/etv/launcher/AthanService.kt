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
 * While it plays, whatever was playing on the TV is paused (through Android's audio focus, and
 * directly through the apps' media controls when eTV may see them), and the athan screen comes up.
 * Afterwards everything is put back as it was and what was playing carries on. OK, Back, or the
 * remote's Pause / Stop end it early.
 */
class AthanService : Service() {

    companion object {
        private const val ACTION_PLAY = "app.etv.launcher.ATHAN_PLAY"
        private const val ACTION_STOP = "app.etv.launcher.ATHAN_STOP"
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

        /** Ends the athan (and the dua) now. */
        fun stop() {
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
    private var paused: List<MediaController> = emptyList()
    private var savedVolume = -1
    private var savedMuted = false
    private var finished = true

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Someone started something on purpose: let them.
        if (change == AudioManager.AUDIOFOCUS_LOSS) handler.post { finishAthan() }
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
            ACTION_STOP -> finishAthan()
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
        if (!finished) cleanUp(resume = false) // a new athan replaces one still playing
        finished = false
        current = this
        prayer = pr
        test = isTest
        usedFallback = false
        val p = Prefs(this)

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eTV:athan").apply { acquire(MAX_MS + 60_000L) }
        } catch (_: Exception) {
        }

        setVolume(p.athanVolume)
        paused = if (p.athanPause) pauseOthers() else emptyList()
        requestFocus(transient = p.athanResume)
        openSession()
        goForeground()
        showScreen()
        handler.postDelayed(timeout, MAX_MS)
        playAthan()
    }

    private fun showScreen() {
        try {
            startActivity(Intent(this, AthanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Without the screen the athan still plays; the remote's Stop or Back on eTV ends it.
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

    /** Pauses every other app that's playing, when eTV may see them (the Now playing permission). */
    private fun pauseOthers(): List<MediaController> {
        if (!NowPlayingWatcher.hasAccess(this)) return emptyList()
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(NowPlayingWatcher.component(this))
                .filter { c ->
                    val st = c.playbackState?.state
                    c.packageName != packageName &&
                        (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
                }
                .onEach { c -> try { c.transportControls.pause() } catch (_: Exception) {} }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resumeOthers(list: List<MediaController>) {
        if (list.isEmpty()) return
        // Give the apps a moment to take back the sound first.
        handler.postDelayed({
            for (c in list) {
                try {
                    if (c.playbackState?.state == PlaybackState.STATE_PAUSED) c.transportControls.play()
                } catch (_: Exception) {
                }
            }
        }, 900L)
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

    /** Sets the TV's volume for the athan (and un-mutes it), remembering how it was. */
    private fun setVolume(percent: Int) {
        savedVolume = -1
        savedMuted = false
        if (percent <= 0) return
        try {
            val am = audio()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                savedMuted = true
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            val v = Math.round(max * percent / 100f).coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (_: Exception) {
        }
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            val am = audio()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            if (savedMuted && Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {
        }
        savedVolume = -1
    }

    /** A media session, so the remote's Pause and Stop end the athan. */
    private fun openSession() {
        try {
            val s = MediaSession(this, "eTV athan")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() = finishAthan()
                override fun onStop() = finishAthan()
                override fun onPlay() {}
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
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
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

    /** Ends the athan now and puts the TV back as it was. */
    fun finishAthan() {
        if (finished) return
        cleanUp(resume = true)
        stopSelfNow()
    }

    private fun cleanUp(resume: Boolean) {
        finished = true
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        abandonFocus()
        restoreVolume()
        val was = paused
        paused = emptyList()
        if (resume && Prefs(this).athanResume) resumeOthers(was)
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        if (current === this) current = null
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
