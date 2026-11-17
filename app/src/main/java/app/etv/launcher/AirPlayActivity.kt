package app.etv.launcher

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.flymop.airplaytv.service.AirPlayService

/**
 * What an iPhone, iPad or Mac plays on the TV through AirPlay: its screen, a video, or music
 * (with the artwork in an arched window). It comes up by itself when something starts, in its
 * own task, so when it closes you're back where you were. Like the receiver, it runs in the
 * ":airplay" process, apart from the home screen.
 *
 * The remote: Back ends a mirrored screen or a video (and hides its bar first); for music Back just
 * goes back, the music carries on (and shows in Now playing on the home screen). Leaving a
 * mirrored screen for another one ends it too; videos and music carry on in the background. OK or Play / Pause
 * pauses; Left / Right skip 10 seconds in a video, or to the previous / next song.
 */
class AirPlayActivity : Activity() {

    private enum class Mode { WAITING, CODE, MIRROR, VIDEO, MUSIC }

    companion object {
        /** The AirPlay screen is on the TV now. */
        @Volatile var visible = false
            private set

        private const val SEEK_MS = 10_000L
        private const val OSD_MS = 4_000L
        private const val HINT_MS = 4_500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pal: Palette
    private var accent = 0

    private lateinit var root: FrameLayout
    private lateinit var mirrorBox: AspectBox
    private lateinit var mirrorSurface: SurfaceView
    private lateinit var videoBox: AspectBox
    private lateinit var videoSurface: SurfaceView
    private lateinit var room: FrameLayout // the calm screens: music, the code, connecting
    private lateinit var music: LinearLayout
    private lateinit var art: ArchArtView
    private lateinit var musicTitle: TextView
    private lateinit var musicSub: TextView
    private lateinit var musicState: TextView
    private lateinit var musicBar: LineProgress
    private lateinit var musicTimes: TextView
    private lateinit var door: FrameLayout
    private lateinit var doorTop: TextView
    private lateinit var doorBig: TextView
    private lateinit var doorNote: TextView
    private lateinit var osd: LinearLayout
    private lateinit var osdTitle: TextView
    private lateinit var osdBar: LineProgress
    private lateinit var osdTimes: TextView
    private lateinit var hint: TextView

    private var mode = Mode.WAITING
    private var openedAt = 0L
    private var shownAnything = false
    private var lastSomethingAt = 0L
    private var lastCover: ByteArray? = null
    private var osdUntil = 0L
    private var osdShown = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 250L)
        }
    }

    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400L).start() }

    /**
     * Leaving a mirrored screen (Home, another app) ends it after a moment: the phone's screen
     * isn't something to keep hearing in the background. Not when the athan covers it.
     */
    private val leftMirror = Runnable {
        val s = AirPlay.service ?: return@Runnable
        if (!visible && mode == Mode.MIRROR && !s.athan && !isFinishing) end()
    }

    // ---------------- set up ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // This screen lives in the airplay process: read eTV's look afresh (it may have changed).
        @Suppress("DEPRECATION")
        getSharedPreferences("etv", Context.MODE_MULTI_PROCESS)
        val prefs = Prefs(this)
        pal = Look.palette(prefs)
        accent = Look.accent(prefs)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // A mirrored screen, and a video sent as a link: each fills the TV at its own shape.
        mirrorSurface = SurfaceView(this)
        mirrorBox = AspectBox(this).apply { addView(mirrorSurface, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(mirrorBox, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoSurface = SurfaceView(this)
        videoBox = AspectBox(this).apply { addView(videoSurface, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(videoBox, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        mirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { AirPlay.service?.setVideoSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AirPlay.service?.setVideoSurface(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { AirPlay.service?.clearVideoSurface(h.surface) }
        })
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { AirPlay.service?.setVideoPlaybackSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AirPlay.service?.setVideoPlaybackSurface(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { AirPlay.service?.clearVideoPlaybackSurface(h.surface) }
        })

        room = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        room.addView(ArchBackgroundView(this).apply {
            outer = pal.frame1
            innerColor = pal.frame2
        }, FrameLayout.LayoutParams(-1, -1))
        buildMusic()
        buildDoor()
        root.addView(room, FrameLayout.LayoutParams(-1, -1))

        buildOsd()

        // A quiet line at the top for the first moments: how to end it.
        hint = TextView(this).apply {
            typeface = Fonts.body(this@AirPlayActivity, 400)
            sp(13f)
            setTextColor(Color.WHITE)
            setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(Look.withAlpha(Color.BLACK, 150))
            }
            alpha = 0f
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dpi(28f)
            marginEnd = dpi(36f)
        })

        setContentView(root)
        openedAt = SystemClock.elapsedRealtime()
        show(Mode.WAITING)
    }

    private fun tv(sizeSp: Float, weight: Int, display: Boolean, color: Int) = TextView(this).apply {
        typeface = if (display) Fonts.display(this@AirPlayActivity, weight) else Fonts.body(this@AirPlayActivity, weight)
        sp(sizeSp)
        setTextColor(color)
        includeFontPadding = false
    }

    private fun buildMusic() {
        music = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        art = ArchArtView(this).apply {
            fill = pal.surface
            stroke = Look.withAlpha(accent, 160)
            fallback = getDrawable(R.drawable.ic_play)?.mutate()?.apply { setTint(pal.muted) }
        }
        music.addView(art, LinearLayout.LayoutParams(dpi(250f), dpi(320f)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(14f, 500, false, accent).apply { text = "AirPlay" }, LinearLayout.LayoutParams(-2, -2))
        musicTitle = tv(36f, 500, true, pal.text).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(musicTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16f) })
        musicSub = tv(17f, 300, false, pal.muted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(musicSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12f) })
        musicBar = LineProgress(this).apply {
            track = pal.tileStroke
            fillColor = accent
        }
        col.addView(musicBar, LinearLayout.LayoutParams(-1, dpi(6f)).apply { topMargin = dpi(34f) })
        musicTimes = tv(13f, 400, false, pal.label)
        col.addView(musicTimes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        musicState = tv(14f, 500, true, accent)
        col.addView(musicState, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(18f) })
        col.addView(tv(12.5f, 300, false, pal.label).apply {
            text = "OK  play / pause     ◀ ▶  previous / next     Back  home, the music carries on"
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(30f) })
        music.addView(col, LinearLayout.LayoutParams(dpi(520f), -2).apply { marginStart = dpi(56f) })

        room.addView(music, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun buildDoor() {
        door = FrameLayout(this)
        door.addView(DoorwayView(this).apply {
            stroke = accent
            fill = Look.withAlpha(accent, 22)
        }, FrameLayout.LayoutParams(dpi(330f), dpi(400f), Gravity.CENTER))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        doorTop = tv(16f, 500, true, accent).apply { gravity = Gravity.CENTER }
        doorBig = tv(30f, 500, true, pal.text).apply { gravity = Gravity.CENTER }
        doorNote = tv(13.5f, 300, false, pal.muted).apply { gravity = Gravity.CENTER }
        col.addView(doorTop, LinearLayout.LayoutParams(-2, -2))
        col.addView(doorBig, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(22f) })
        col.addView(doorNote, LinearLayout.LayoutParams(dpi(260f), -2).apply { topMargin = dpi(22f) })
        door.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dpi(30f) })
        room.addView(door, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildOsd() {
        osd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(56f), dpi(60f), dpi(56f), dpi(36f))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Look.withAlpha(Color.BLACK, 210), Color.TRANSPARENT),
            )
            alpha = 0f
        }
        osdTitle = tv(22f, 500, true, Color.WHITE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        osd.addView(osdTitle, LinearLayout.LayoutParams(-1, -2))
        osdBar = LineProgress(this).apply {
            track = Look.withAlpha(Color.WHITE, 70)
            fillColor = accent
        }
        osd.addView(osdBar, LinearLayout.LayoutParams(-1, dpi(6f)).apply { topMargin = dpi(16f) })
        osdTimes = tv(13f, 400, false, Look.withAlpha(Color.WHITE, 200))
        osd.addView(osdTimes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        root.addView(osd, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    // ---------------- lifecycle ----------------

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(leftMirror)
    }

    override fun onStop() {
        super.onStop()
        if (mode == Mode.MIRROR && !isFinishing) handler.postDelayed(leftMirror, 2_000L)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- what to show ----------------

    private fun currentMode(s: AirPlayService): Mode? = when {
        s.activeRemotePin.value != null -> Mode.CODE
        s.videoLocation.value != null -> Mode.VIDEO
        s.mirroringActive.value || s.mirrorStarting -> Mode.MIRROR
        s.audioOnly.value && s.connectionCount.value > 0 -> Mode.MUSIC
        else -> null
    }

    private fun update() {
        val s = AirPlay.service
        val now = SystemClock.elapsedRealtime()
        val m = s?.let { currentMode(it) }
        if (m == null) {
            // Nothing (any more): close. Give a device that's just connecting a moment first, and a
            // screen that just stopped mirroring a moment to start a video instead.
            val waited = now - if (shownAnything) lastSomethingAt else openedAt
            val patience = when {
                !shownAnything -> 12_000L
                mode == Mode.MIRROR || mode == Mode.VIDEO -> 1_500L
                else -> 2_500L
            }
            if (waited > patience) finish()
            return
        }
        shownAnything = true
        lastSomethingAt = now
        if (m != mode) show(m)
        when (m) {
            Mode.CODE -> {
                set(doorBig, s.activeRemotePin.value?.chunked(2)?.joinToString(" ") ?: "")
            }
            Mode.MIRROR -> mirrorBox.aspect = s.videoAspect.value
            Mode.VIDEO -> {
                videoBox.aspect = s.videoPlaybackAspect.value
                val info = s.videoPlaybackInfo.value
                set(osdTitle, s.videoTitle.value.ifEmpty { "AirPlay" })
                osdBar.progress = if (info.durationMs > 0) info.positionMs.toFloat() / info.durationMs else -1f
                set(osdTimes, buildString {
                    append(
                        when {
                            info.buffering -> "Loading…"
                            !info.playing -> "Paused"
                            else -> "Playing"
                        },
                    )
                    append("      ")
                    append(clock(info.positionMs))
                    if (info.durationMs > 0) append("  /  ").append(clock(info.durationMs))
                })
                // The bar stays while paused; otherwise it fades a few seconds after the last key.
                setOsd(!info.playing || info.buffering || now < osdUntil)
            }
            Mode.MUSIC -> {
                val t = s.trackInfo.value
                set(musicTitle, t.title.ifEmpty { "AirPlay" })
                set(musicSub, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString("  ·  ")
                    .ifEmpty { "Music from your device" })
                val cover = s.coverArt.value
                if (cover !== lastCover) {
                    lastCover = cover
                    art.bitmap = cover?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (_: Exception) { null } }
                }
                val dur = s.durationMs.value
                val pos = s.currentPositionMs()
                musicBar.progress = if (dur > 0) pos.toFloat() / dur else -1f
                set(musicTimes, if (dur > 0) "${clock(pos)}  /  ${clock(dur)}" else "")
                set(musicState, if (s.held) "Paused for the athan" else if (s.playing.value) "" else "Paused")
                art.dimmed = !s.playing.value
            }
            Mode.WAITING -> Unit
        }
    }

    private fun show(m: Mode) {
        mode = m
        val video = m == Mode.MIRROR || m == Mode.VIDEO
        mirrorBox.visibility = if (m == Mode.MIRROR) View.VISIBLE else View.GONE
        videoBox.visibility = if (m == Mode.VIDEO) View.VISIBLE else View.GONE
        room.visibility = if (video) View.GONE else View.VISIBLE
        music.visibility = if (m == Mode.MUSIC) View.VISIBLE else View.GONE
        door.visibility = if (m == Mode.CODE || m == Mode.WAITING) View.VISIBLE else View.GONE
        if (m != Mode.VIDEO) {
            osd.animate().cancel()
            osd.alpha = 0f
            osdShown = false
        }
        when (m) {
            Mode.CODE -> {
                set(doorTop, "AirPlay")
                set(doorNote, "Type this code on your iPhone, iPad or Mac")
            }
            Mode.WAITING -> {
                set(doorTop, "AirPlay")
                set(doorBig, "Connecting…")
                set(doorNote, "")
            }
            Mode.MIRROR -> flashHint("Back ends AirPlay")
            Mode.VIDEO -> {
                osdUntil = SystemClock.elapsedRealtime() + OSD_MS
                flashHint("Back ends AirPlay")
            }
            Mode.MUSIC -> Unit
        }
    }

    private fun flashHint(text: String) {
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, HINT_MS)
    }

    private fun set(v: TextView, text: String) {
        if (v.text?.toString() != text) v.text = text
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ---------------- the remote ----------------

    private fun setOsd(on: Boolean) {
        if (on == osdShown) return
        osdShown = on
        osd.animate().cancel()
        osd.animate().alpha(if (on) 1f else 0f).setDuration(250L).start()
    }

    private fun showOsd() {
        osdUntil = SystemClock.elapsedRealtime() + OSD_MS
        setOsd(true)
    }

    /** Ends what's on the TV (the device is told) and closes. */
    private fun end() {
        AirPlay.service?.disconnectSessions()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = AirPlay.service ?: return super.onKeyDown(keyCode, event)
        val playPause = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        when (mode) {
            Mode.VIDEO -> when {
                playPause -> { s.toggleVideoPlayPause(); showOsd(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> { s.setVideoPlaying(true); showOsd(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE -> { s.setVideoPlaying(false); showOsd(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    s.seekVideoBy(-SEEK_MS); showOsd(); return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    s.seekVideoBy(SEEK_MS); showOsd(); return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_INFO -> { showOsd(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> { end(); return true }
            }
            Mode.MUSIC -> when {
                playPause -> { s.togglePlayPause(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!s.playing.value) s.togglePlayPause(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (s.playing.value) s.togglePlayPause(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    s.previousTrack(); return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    s.nextTrack(); return true
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> { end(); return true }
            }
            Mode.MIRROR -> when {
                playPause -> { s.togglePlayPause(); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> { end(); return true }
            }
            else -> Unit
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (mode) {
            // The bar first, then the video.
            Mode.VIDEO -> if (osdShown && AirPlay.service?.videoPlaybackInfo?.value?.playing == true) {
                osdUntil = 0L
                setOsd(false)
            } else end()
            Mode.MIRROR, Mode.CODE, Mode.WAITING -> end()
            // Music carries on in the background (Now playing on the home screen has it).
            Mode.MUSIC -> finish()
        }
    }
}

/** Keeps its child at [aspect] (width / height), as large as fits, centred. */
class AspectBox(context: Context) : FrameLayout(context) {
    var aspect = 16f / 9f
        set(v) {
            if (v > 0f && v != field) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w == 0 || h == 0) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) {
            ch = h
            cw = (h * aspect).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
        )
    }
}

/** A thin rounded line: the track, and how far along in the accent colour (-1: just the track). */
class LineProgress(context: Context) : View(context) {
    var track = 0
    var fillColor = 0
    var progress = -1f
        set(v) {
            val c = if (v < 0f) -1f else v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        val sw = height * 0.5f
        val y = height / 2f
        val l = sw
        val r = width - sw
        paint.strokeWidth = sw
        paint.color = track
        canvas.drawLine(l, y, r, y, paint)
        if (progress >= 0f) {
            paint.color = fillColor
            canvas.drawLine(l, y, l + (r - l) * progress, y, paint)
        }
    }
}
