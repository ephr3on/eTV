package app.etv.launcher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
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

/** A thin rounded progress line. */
class ProgressLine(context: Context) : View(context) {
    var track = Color.GRAY
    var fill = Color.WHITE
    var fraction = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = track
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            paint.color = fill
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/**
 * The "now playing" card: artwork, app, title, a progress line and three controls.
 * It fades in when something plays and fades out when it's gone. Every size inside it is fixed,
 * so its once-a-second progress update never makes the rest of the home screen re-lay out.
 */
@SuppressLint("ViewConstructor")
class NowPlayingCard(context: Context) : LinearLayout(context) {

    var onOpen: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    /** Called when one of the card's parts is selected, with a key for "where you were". */
    var onFocused: ((View, String) -> Unit)? = null

    val openArea = LinearLayout(context)
    val prev = ImageView(context)
    val play = ImageView(context)
    val next = ImageView(context)

    private val artBox = FrameLayout(context)
    private val art = ImageView(context)
    private val artFallback = ImageView(context)
    private val appIcon = ImageView(context)
    private val appName = TextView(context)
    private val stateText = TextView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val progress = ProgressLine(context)
    private val time = TextView(context)
    private val progressRow = LinearLayout(context)

    private var pal: Palette = Look.palettes[0]
    private var accent = Color.WHITE
    private var data: NowPlaying? = null
    private var showing = false

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (showing && data?.playing == true) postDelayed(this, 1_000L)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        alpha = 0f
        val p = context.dpi(12f)
        setPadding(p, p, p, context.dpi(10f))
        clipChildren = false
        clipToPadding = false

        // Top: artwork and what's playing. OK here opens the app.
        openArea.orientation = HORIZONTAL
        openArea.gravity = Gravity.CENTER_VERTICAL
        openArea.isFocusable = true
        openArea.isClickable = true
        val ip = context.dpi(4f)
        openArea.setPadding(ip, ip, ip, ip)
        openArea.setOnClickListener { onOpen?.invoke() }

        artBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.dp(9f))
            }
        }
        artBox.clipToOutline = true
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        artFallback.scaleType = ImageView.ScaleType.FIT_CENTER
        val fb = context.dpi(26f)
        artBox.addView(art, FrameLayout.LayoutParams(-1, -1))
        artBox.addView(artFallback, FrameLayout.LayoutParams(fb, fb, Gravity.CENTER))
        openArea.addView(artBox, LayoutParams(context.dpi(92f), context.dpi(52f)))

        val info = LinearLayout(context).apply { orientation = VERTICAL }
        val appLine = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ai = context.dpi(13f)
        appLine.addView(appIcon, LayoutParams(ai, ai).apply { marginEnd = context.dpi(5f) })
        appName.typeface = Fonts.body(context, 500)
        appName.sp(10.5f)
        appName.letterSpacing = 0.04f
        appName.maxLines = 1
        appName.ellipsize = TextUtils.TruncateAt.END
        appLine.addView(appName, LayoutParams(0, -2, 1f))
        stateText.typeface = Fonts.body(context, 300)
        stateText.sp(10.5f)
        stateText.maxLines = 1
        stateText.gravity = Gravity.END
        appLine.addView(stateText, LayoutParams(context.dpi(62f), -2))
        info.addView(appLine, LayoutParams(-1, -2))

        title.typeface = Fonts.body(context, 500)
        title.sp(13.5f)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title, LayoutParams(-1, -2).apply { topMargin = context.dpi(3f) })

        subtitle.typeface = Fonts.body(context, 300)
        subtitle.sp(11.5f)
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        info.addView(subtitle, LayoutParams(-1, -2).apply { topMargin = context.dpi(1f) })
        openArea.addView(info, LayoutParams(0, -2, 1f).apply { marginStart = context.dpi(11f) })
        addView(openArea, LayoutParams(-1, -2))

        // Middle: progress.
        progressRow.orientation = HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL
        progressRow.addView(progress, LayoutParams(0, context.dpi(3f), 1f))
        time.typeface = Fonts.body(context, 300)
        time.sp(10.5f)
        time.gravity = Gravity.END
        time.maxLines = 1
        time.fontFeatureSettings = "tnum"
        progressRow.addView(time, LayoutParams(context.dpi(84f), -2).apply { marginStart = context.dpi(8f) })
        addView(progressRow, LayoutParams(-1, context.dpi(16f)).apply {
            topMargin = context.dpi(8f)
            marginStart = context.dpi(4f)
            marginEnd = context.dpi(4f)
        })

        // Bottom: previous, play / pause, next.
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        fun button(v: ImageView, iconRes: Int, size: Float, key: String, action: () -> Unit) {
            v.setImageResource(iconRes)
            v.scaleType = ImageView.ScaleType.CENTER
            v.isFocusable = true
            v.isClickable = true
            v.setOnClickListener { action() }
            v.setOnFocusChangeListener { view, has ->
                view.softScale(if (has) 1.08f else 1f)
                if (has) onFocused?.invoke(view, key)
            }
            val s = context.dpi(size)
            controls.addView(v, LayoutParams(s, s).apply {
                marginStart = context.dpi(8f)
                marginEnd = context.dpi(8f)
            })
        }
        button(prev, R.drawable.ic_prev, 38f, "media:prev") { onPrev?.invoke() }
        button(play, R.drawable.ic_pause, 46f, "media:play") { onPlayPause?.invoke() }
        button(next, R.drawable.ic_next, 38f, "media:next") { onNext?.invoke() }
        openArea.setOnFocusChangeListener { v, has -> if (has) onFocused?.invoke(v, "media:open") }
        addView(controls, LayoutParams(-1, -2).apply { topMargin = context.dpi(6f) })
    }

    fun viewFor(key: String): View? = if (!showing) null else when (key) {
        "media:open" -> openArea
        "media:prev" -> prev.takeIf { it.visibility == View.VISIBLE }
        "media:play" -> play
        "media:next" -> next.takeIf { it.visibility == View.VISIBLE }
        else -> null
    }

    val isShowing: Boolean get() = showing

    fun applyTheme(pal: Palette, accent: Int) {
        this.pal = pal
        this.accent = accent
        val d = context.dp(1f)
        background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(Look.withAlpha(pal.tile, 238))
            setStroke(context.dpi(1f), pal.tileStroke)
        }
        openArea.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = 13 * d
                setColor(Look.withAlpha(accent, 36))
                setStroke(context.dpi(1.5f), accent)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 13 * d
                setColor(Color.TRANSPARENT)
            })
        }
        artBox.setBackgroundColor(pal.tileInner)
        appName.setTextColor(accent)
        stateText.setTextColor(pal.muted)
        title.setTextColor(pal.text)
        subtitle.setTextColor(pal.label)
        time.setTextColor(pal.muted)
        progress.track = pal.tileStroke
        progress.fill = accent
        progress.invalidate()
        val iconColors = focusColors(pal.bg, pal.text)
        for (b in listOf(prev, next)) {
            b.background = pillBackground(context, 19f, Color.TRANSPARENT, accent)
            b.imageTintList = iconColors
        }
        play.background = pillBackground(context, 23f, pal.tileStroke, accent)
        play.imageTintList = iconColors
    }

    /** Shows (or updates) the card. */
    fun show(np: NowPlaying) {
        data = np
        setIfChanged(appName, np.app.uppercase())
        setIfChanged(stateText, if (np.buffering) "Loading" else if (np.playing) "Playing" else "Paused")
        setIfChanged(title, np.title)
        setIfChanged(subtitle, np.subtitle)
        subtitle.visibility = if (np.subtitle.isEmpty()) View.INVISIBLE else View.VISIBLE

        if (appIcon.drawable !== np.appIcon) appIcon.setImageDrawable(np.appIcon)
        if (np.art != null) {
            if ((art.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== np.art) art.setImageBitmap(np.art)
            artFallback.visibility = View.GONE
        } else {
            art.setImageDrawable(null)
            if (artFallback.drawable !== np.appIcon) artFallback.setImageDrawable(np.appIcon)
            artFallback.visibility = View.VISIBLE
        }

        val playIcon = if (np.playing) R.drawable.ic_pause else R.drawable.ic_play
        if (play.tag != playIcon) {
            play.tag = playIcon
            play.setImageResource(playIcon)
        }
        play.contentDescription = if (np.playing) "Pause" else "Play"
        setShown(prev, np.canPrev)
        setShown(next, np.canNext)

        val live = np.duration <= 0
        progressRow.visibility = if (live && np.positionNow() < 0) View.INVISIBLE else View.VISIBLE
        updateProgress()

        if (!showing) {
            showing = true
            animate().cancel()
            visibility = View.VISIBLE
            translationY = -context.dp(8f)
            animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        removeCallbacks(ticker)
        if (np.playing) postDelayed(ticker, 1_000L)
    }

    /** Fades the card away. */
    fun hide() {
        removeCallbacks(ticker)
        data = null
        if (!showing) return
        showing = false
        animate().cancel()
        animate().alpha(0f).translationY(-context.dp(8f)).setDuration(320)
            .withEndAction { if (!showing) visibility = View.GONE }
            .start()
    }

    /** Stops the once-a-second progress update (eTV is in the background). */
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
            progress.fraction = pos.toFloat() / np.duration
            setIfChanged(time, "${fmt(pos)} / ${fmt(np.duration)}")
        } else {
            progress.fraction = 0f
            setIfChanged(time, if (pos > 0) fmt(pos) else "Live")
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
