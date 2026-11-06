package app.etv.launcher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Android only shows other apps' media sessions (what's playing, and its controls) to apps that
 * have "notification access". This service exists for that permission alone: it ignores every
 * notification and keeps nothing.
 */
class MediaListener : NotificationListenerService()

/** What one app is playing, as shown on the card. */
class NowPlaying(
    val controller: MediaController,
    val pkg: String,
    val app: String,
    val appIcon: Drawable?,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val playing: Boolean,
    val buffering: Boolean,
    private val position: Long,
    private val updatedAt: Long,
    private val speed: Float,
    val duration: Long,
    val canPrev: Boolean,
    val canNext: Boolean,
) {
    /** Where playback is now, worked out from the app's last report. */
    fun positionNow(): Long {
        if (position < 0) return -1
        var p = position
        if (playing && !buffering && updatedAt > 0) p += ((SystemClock.elapsedRealtime() - updatedAt) * speed).toLong()
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

/**
 * Watches every app's media session and picks the one worth showing:
 * - whatever is playing right now (the most recent one, if several are);
 * - otherwise the last one that was playing, for a while after it paused (2 minutes) or stopped
 *   (15 seconds), so you can still resume it from home;
 * - nothing once it's been idle longer than that, or the app closed its session.
 */
class NowPlayingWatcher(private val context: Context, private val onChange: (NowPlaying?) -> Unit) {

    companion object {
        private const val KEEP_PAUSED_MS = 2 * 60_000L
        private const val KEEP_STOPPED_MS = 15_000L
        private const val RECHECK_MS = 5_000L

        /** When each session was last seen playing; kept while eTV is in the background. */
        private val lastSeenPlaying = HashMap<MediaSession.Token, Long>()

        fun component(context: Context) = ComponentName(context, MediaListener::class.java)

        /** True when eTV has been given notification access (needed to see what's playing). */
        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(context)
            return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
        }

        private fun isActive(state: Int) = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ||
            state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING ||
            state == PlaybackState.STATE_SKIPPING_TO_NEXT || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
            state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
    }

    private val handler = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var controllers: List<MediaController> = emptyList()
    private var started = false
    private var shownToken: MediaSession.Token? = null
    private val appCache = HashMap<String, Pair<String, Drawable?>>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onSessionDestroyed() = evaluate()
    }

    private val recheck = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    fun start() {
        if (started) return
        if (!hasAccess(context)) {
            onChange(null)
            return
        }
        try {
            val c = component(context)
            msm.addOnActiveSessionsChangedListener(sessionsListener, c, handler)
            started = true
            setControllers(msm.getActiveSessions(c))
        } catch (e: SecurityException) {
            onChange(null)
            return
        }
        handler.postDelayed(recheck, RECHECK_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(recheck)
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controllers.forEach { try { it.unregisterCallback(callback) } catch (_: Exception) {} }
        controllers = emptyList()
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { try { it.unregisterCallback(callback) } catch (_: Exception) {} }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        evaluate()
    }

    private fun lastActive(c: MediaController, now: Long): Long {
        val s = c.playbackState ?: return lastSeenPlaying[c.sessionToken] ?: 0L
        if (isActive(s.state)) {
            lastSeenPlaying[c.sessionToken] = now
            return now
        }
        return maxOf(s.lastPositionUpdateTime, lastSeenPlaying[c.sessionToken] ?: 0L)
    }

    private fun evaluate() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        val pick = controllers.firstOrNull { c -> c.playbackState?.state?.let { isActive(it) } == true }
            ?: controllers
                .filter { c ->
                    val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
                    if (st == PlaybackState.STATE_ERROR) return@filter false
                    val keep = if (st == PlaybackState.STATE_PAUSED) KEEP_PAUSED_MS else KEEP_STOPPED_MS
                    val last = lastActive(c, now)
                    last > 0 && now - last < keep && c.metadata != null
                }
                .maxByOrNull { lastActive(it, now) }

        // Remember who's playing even when another session is shown.
        controllers.forEach { lastActive(it, now) }
        shownToken = pick?.sessionToken
        onChange(pick?.let { describe(it) })
    }

    private fun describe(c: MediaController): NowPlaying {
        val md = c.metadata
        val st = c.playbackState
        val pkg = c.packageName
        val (app, icon) = appCache.getOrPut(pkg) {
            val pm = context.packageManager
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
            } catch (e: Exception) {
                pkg to null
            }
        }
        fun text(vararg keys: String): String {
            for (k in keys) {
                val v = md?.getText(k)?.toString()?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }
        val title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE).ifEmpty { app }
        val subtitle = text(
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
        ).takeIf { it != title } ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = st?.state ?: PlaybackState.STATE_NONE
        val actions = st?.actions ?: 0L
        return NowPlaying(
            controller = c,
            pkg = pkg,
            app = app,
            appIcon = icon,
            title = title,
            subtitle = subtitle,
            art = art,
            playing = isActive(state),
            buffering = state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING,
            position = st?.position ?: -1,
            updatedAt = st?.lastPositionUpdateTime ?: 0L,
            speed = st?.playbackSpeed?.takeIf { it != 0f } ?: 1f,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            // Apps that report no actions at all usually support everything.
            canPrev = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    // ---- controls ----

    fun playPause(np: NowPlaying) {
        val t = np.controller.transportControls
        if (np.playing) t.pause() else t.play()
    }

    fun previous(np: NowPlaying) = np.controller.transportControls.skipToPrevious()
    fun next(np: NowPlaying) = np.controller.transportControls.skipToNext()

    /** Opens the app that's playing, on its player screen when it offers one. */
    fun open(np: NowPlaying) {
        val pi: PendingIntent? = np.controller.sessionActivity
        if (pi != null) {
            try {
                pi.send()
                return
            } catch (_: Exception) {
            }
        }
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(np.pkg) ?: pm.getLaunchIntentForPackage(np.pkg) ?: return
        AppRepo.tryStart(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Artwork in a small Sidi Bou Said doorway: the picture is clipped to an arch, like the scene
 * through an open door. Without artwork, the app's icon sits in the doorway instead.
 */
class ArchArtView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(v) {
            if (v !== field) {
                field = v
                invalidate()
            }
        }
    var fallback: Drawable? = null
        set(v) {
            if (v !== field) {
                field = v
                invalidate()
            }
        }
    var dimmed = false
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }
    var fill = Color.DKGRAY
    var stroke = Color.GRAY

    private val path = android.graphics.Path()
    private val oval = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = android.graphics.Matrix()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        archPath(path, oval, 0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d, 4f * d)

        canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRect(0f, 0f, w, h, paint)
        val bmp = bitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            // Centre-crop the artwork into the doorway.
            val scale = maxOf(w / bmp.width, h / bmp.height)
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate((w - bmp.width * scale) / 2f, (h - bmp.height * scale) / 2f)
            paint.alpha = if (dimmed) 120 else 255
            canvas.drawBitmap(bmp, matrix, paint)
            paint.alpha = 255
        } else {
            fallback?.let { icon ->
                val s = (minOf(w, h) * 0.56f).toInt()
                val l = ((w - s) / 2f).toInt()
                val t = ((h - s) / 2f + h * 0.08f).toInt()
                icon.setBounds(l, t, l + s, t + s)
                icon.alpha = if (dimmed) 130 else 255
                icon.draw(canvas)
                icon.alpha = 255
            }
        }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = stroke
        canvas.drawPath(path, paint)
    }
}

/**
 * The "now playing" bar. It sits between the doors and the dock, with no container around it: a small
 * arched window with the artwork, the app and title, the time, and three round controls.
 * The door studs under the date show how far along it is ([onProgress]).
 *
 * Every size inside it is fixed, so its once-a-second update never makes the rest of the home
 * screen re-lay out.
 */
@SuppressLint("ViewConstructor")
class NowPlayingCard(context: Context) : LinearLayout(context) {

    var onOpen: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    /** Called when one of the bar's parts is selected, with a key for "where you were". */
    var onFocused: ((View, String) -> Unit)? = null

    /** How far along playback is (0–1), or -1 when unknown or hidden. Drives the door studs. */
    var onProgress: ((Float) -> Unit)? = null

    val openArea = LinearLayout(context)
    val prev = ImageView(context)
    val play = ImageView(context)
    val next = ImageView(context)

    private val art = ArchArtView(context)
    private val appLine = TextView(context)
    private val title = TextView(context)
    private val time = TextView(context)

    private var pal: Palette = Look.palettes[0]
    private var accent = Color.WHITE
    private var data: NowPlaying? = null
    private var showing = false
    private var lastProgress = -2f

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (showing && data?.playing == true) postDelayed(this, 1_000L)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        alpha = 0f
        setPadding(context.dpi(6f), 0, context.dpi(8f), 0)
        clipChildren = false
        clipToPadding = false

        // Artwork, app and title. OK here opens the app on its player.
        openArea.orientation = HORIZONTAL
        openArea.gravity = Gravity.CENTER_VERTICAL
        openArea.isFocusable = true
        openArea.isClickable = true
        openArea.setPadding(context.dpi(4f), 0, context.dpi(12f), 0)
        openArea.setOnClickListener { onOpen?.invoke() }
        openArea.setOnFocusChangeListener { v, has -> if (has) onFocused?.invoke(v, "media:open") }
        openArea.addView(art, LayoutParams(context.dpi(28f), context.dpi(35f)))

        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appLine.typeface = Fonts.body(context, 500)
        appLine.sp(9.5f)
        appLine.letterSpacing = 0.12f
        appLine.maxLines = 1
        appLine.ellipsize = TextUtils.TruncateAt.END
        text.addView(appLine, LayoutParams(-1, -2))
        title.typeface = Fonts.body(context, 400)
        title.sp(13f)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.includeFontPadding = false
        text.addView(title, LayoutParams(-1, -2).apply { topMargin = context.dpi(2f) })
        openArea.addView(text, LayoutParams(context.dpi(196f), -2).apply { marginStart = context.dpi(10f) })
        addView(openArea, LayoutParams(-2, context.dpi(38f)))

        time.typeface = Fonts.body(context, 300)
        time.sp(10.5f)
        time.gravity = Gravity.CENTER
        time.maxLines = 1
        time.fontFeatureSettings = "tnum"
        addView(time, LayoutParams(context.dpi(74f), -2))

        // Previous, play / pause, next: round, like the studs on a door.
        fun button(v: ImageView, iconRes: Int, size: Float, key: String, action: () -> Unit) {
            v.setImageResource(iconRes)
            v.scaleType = ImageView.ScaleType.CENTER
            v.isFocusable = true
            v.isClickable = true
            v.setOnClickListener { action() }
            v.setOnFocusChangeListener { view, has ->
                view.softScale(if (has) 1.1f else 1f)
                if (has) onFocused?.invoke(view, key)
            }
            val sz = context.dpi(size)
            addView(v, LayoutParams(sz, sz).apply { marginStart = context.dpi(5f) })
        }
        button(prev, R.drawable.ic_prev, 30f, "media:prev") { onPrev?.invoke() }
        button(play, R.drawable.ic_pause, 36f, "media:play") { onPlayPause?.invoke() }
        button(next, R.drawable.ic_next, 30f, "media:next") { onNext?.invoke() }
    }

    fun viewFor(key: String): View? = if (!showing) null else when (key) {
        "media:open" -> openArea
        "media:prev" -> prev.takeIf { it.isFocusable }
        "media:play" -> play
        "media:next" -> next.takeIf { it.isFocusable }
        else -> null
    }

    val isShowing: Boolean get() = showing

    fun applyTheme(pal: Palette, accent: Int) {
        this.pal = pal
        this.accent = accent
        val d = context.dp(1f)
        // No container: just the artwork, text and controls resting between the doors and the dock.
        background = null
        openArea.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = 19 * d
                setColor(Look.withAlpha(accent, 34))
                setStroke(context.dpi(1.5f), accent)
            })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        art.fill = pal.tileInner
        art.stroke = pal.tileStroke
        art.invalidate()
        appLine.setTextColor(accent)
        title.setTextColor(pal.text)
        time.setTextColor(pal.muted)
        val iconColors = focusColors(pal.bg, pal.text)
        for (b in listOf(prev, next)) {
            b.background = studBackground(pal.tileInner, Color.TRANSPARENT, accent)
            b.imageTintList = iconColors
        }
        play.background = studBackground(pal.tileInner, pal.tileStroke, accent)
        play.imageTintList = iconColors
    }

    /** A round stud: filled softly, accent when selected. */
    private fun studBackground(fill: Int, stroke: Int, focus: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(focus)
        })
        addState(intArrayOf(), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(context.dpi(1f), stroke)
        })
    }

    /** Shows (or updates) the bar. */
    fun show(np: NowPlaying) {
        data = np
        val state = if (np.buffering) "LOADING" else if (np.playing) "PLAYING" else "PAUSED"
        setIfChanged(appLine, "${np.app.uppercase()}  ·  $state")
        setIfChanged(title, if (np.subtitle.isNotEmpty()) "${np.title}  —  ${np.subtitle}" else np.title)
        art.bitmap = np.art
        art.fallback = np.appIcon
        art.dimmed = !np.playing

        val playIcon = if (np.playing) R.drawable.ic_pause else R.drawable.ic_play
        if (play.tag != playIcon) {
            play.tag = playIcon
            play.setImageResource(playIcon)
        }
        play.contentDescription = if (np.playing) "Pause" else "Play"
        setShown(prev, np.canPrev)
        setShown(next, np.canNext)
        updateProgress()

        if (!showing) {
            showing = true
            animate().cancel()
            visibility = View.VISIBLE
            translationY = context.dp(6f)
            animate().alpha(1f).translationY(0f).setDuration(280).start()
        }
        removeCallbacks(ticker)
        if (np.playing) postDelayed(ticker, 1_000L)
    }

    /** Fades the bar away. */
    fun hide() {
        removeCallbacks(ticker)
        data = null
        report(-1f)
        if (!showing) return
        showing = false
        animate().cancel()
        animate().alpha(0f).translationY(context.dp(6f)).setDuration(340)
            .withEndAction { if (!showing) visibility = View.GONE }
            .start()
    }

    /** Stops the once-a-second update (eTV is in the background). */
    fun pause() = removeCallbacks(ticker)

    fun resume() {
        removeCallbacks(ticker)
        if (showing && data?.playing == true) post(ticker)
    }

    private fun setShown(v: View, on: Boolean) {
        val want = if (on) View.VISIBLE else View.INVISIBLE
        if (v.visibility != want) v.visibility = want
        v.isFocusable = on
    }

    private fun updateProgress() {
        val np = data ?: return
        val pos = np.positionNow()
        if (np.duration > 0 && pos >= 0) {
            report(pos.toFloat() / np.duration)
            setIfChanged(time, "${fmt(pos)} / ${fmt(np.duration)}")
        } else {
            report(-1f)
            setIfChanged(time, if (pos > 0) fmt(pos) else "Live")
        }
    }

    private fun report(p: Float) {
        if (p != lastProgress) {
            lastProgress = p
            onProgress?.invoke(p)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun setIfChanged(v: TextView, text: String) {
        if (v.text?.toString() != text) v.text = text
    }
}
