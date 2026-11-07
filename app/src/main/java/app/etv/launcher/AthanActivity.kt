package app.etv.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The athan screen: an open doorway with the prayer's name in it, the door studs lighting up as
 * the athan goes on, then the dua. It sits in its own task, so when it closes you're back exactly
 * where you were (your film, your video, or the home screen).
 *
 * It stays until the athan and the dua are over: the remote's keys do nothing here (only the
 * volume works), and if Home or an app's button hides it, the athan brings it straight back.
 * The one way out early is your code typed on the number keys (set in Settings → Athan).
 */
class AthanActivity : Activity() {

    companion object {
        /** Start the athan for this prayer (when Android wouldn't let the service start by itself). */
        const val EXTRA_START = "app.etv.launcher.ATHAN_START"

        /** The athan screen is on the TV now. */
        @Volatile var visible = false
            private set

        private var shown: AthanActivity? = null

        /** The athan is over: close at once. */
        fun closeNow() {
            shown?.let { if (!it.isFinishing) it.finish() }
        }
    }

    /** The number keys typed so far (the last few). */
    private val typed = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var phase: TextView
    private lateinit var studs: DotsView
    private var sawPlaying = false
    private var openedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this)
        val pal = Look.palette(prefs)
        val accent = Look.accent(prefs)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        root.addView(ArchBackgroundView(this).apply {
            outer = pal.frame1
            innerColor = pal.frame2
        }, FrameLayout.LayoutParams(-1, -1))

        // The doorway.
        val door = DoorwayView(this).apply {
            stroke = accent
            fill = Look.withAlpha(accent, 22)
        }
        root.addView(door, FrameLayout.LayoutParams(dpi(330f), dpi(420f), Gravity.CENTER).apply { topMargin = dpi(10f) })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun tv(sizeSp: Float, weight: Int, display: Boolean, color: Int) = TextView(this).apply {
            typeface = if (display) Fonts.display(this@AthanActivity, weight) else Fonts.body(this@AthanActivity, weight)
            sp(sizeSp)
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val small = tv(18f, 500, true, accent).apply { text = "حان الآن موعد أذان" }
        title = tv(64f, 600, true, pal.text)
        subtitle = tv(14f, 300, false, pal.muted)
        studs = DotsView(this).apply {
            dot = pal.dot
            this.accent = accent
        }
        phase = tv(15f, 500, true, accent)
        col.addView(small, LinearLayout.LayoutParams(-2, -2))
        col.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(18f) })
        col.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14f) })
        col.addView(studs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(30f) })
        col.addView(phase, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(16f) })
        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dpi(40f) })

        setContentView(root)
        openedAt = System.currentTimeMillis()

        // Opened by AthanService.start because the service couldn't start on its own: start it now.
        val start = intent.getIntExtra(EXTRA_START, -1)
        if (start >= 0 && !AthanService.isPlaying) {
            Prayer.values().getOrNull(start)?.let { AthanService.start(this, it, test = false) }
        }
        update()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        shown = this
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        if (shown === this) shown = null
        visible = false
        super.onDestroy()
    }

    private fun update() {
        val s = AthanService.current
        if (s == null) {
            // The athan ended (or never started): close, giving the service a moment to begin.
            if (sawPlaying || System.currentTimeMillis() - openedAt > 4_000L) finish()
            return
        }
        sawPlaying = true
        val prefs = Prefs(this)
        val place = prefs.place
        val tz = Days.tz(place)
        val today = Days.today(tz)
        val friday = Days.isFriday(today, tz)
        set(title, s.prayer.arName(friday))
        val time = PrayerStore.times(this, prefs, today)?.let { Days.clock(it[s.prayer.ordinal], prefs.clock24) }
        val parts = listOfNotNull(s.prayer.enName(friday), time, place?.short)
        set(subtitle, parts.joinToString("  ·  "))
        set(phase, when (s.phase) {
            AthanService.PHASE_DUA -> "الدعاء بعد الأذان"
            AthanService.PHASE_GAP -> ""
            else -> "الأذان"
        })
        studs.progress = s.progress()
    }

    private fun set(v: TextView, text: String) {
        if (v.text?.toString() != text) v.text = text
    }

    /**
     * Every key stops here, except the volume: Back, OK, the arrows and the media keys do nothing.
     * The number keys are remembered; the code ends the athan.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) {
                typed.append(digit)
                val len = AthanService.codeLength(this)
                if (len > 0 && typed.length > len) typed.delete(0, typed.length - len)
                if (len > 0 && AthanService.isCode(this, typed.toString())) {
                    typed.setLength(0)
                    AthanService.stopWithCode()
                    finish()
                }
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back does nothing while the athan plays.
    }
}

/** A large, open Sidi Bou Said doorway: an arch with a fine second line inside it. */
class DoorwayView(context: Context) : View(context) {
    var stroke = 0
    var fill = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        archPath(path, oval, d, d, w - d, h - d, 10f * d)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = stroke
        canvas.drawPath(path, paint)
        val i = 9f * d
        archPath(path, oval, i, i, w - i, h - i, 7f * d)
        paint.strokeWidth = 0.75f * d
        paint.color = Look.withAlpha(stroke, 110)
        canvas.drawPath(path, paint)
    }
}
