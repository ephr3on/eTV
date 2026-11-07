package app.etv.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** One prayer on the home screen. */
class PrayerCell(
    val name: String,
    val time: String,
    /** Under the next prayer: how long until it ("بعد ١:٤٢", "in 1 h 42 min", or "Athan" while it plays). */
    val note: String,
    val next: Boolean,
    /** Already prayed today. */
    val past: Boolean,
    /** Sunrise: shown quieter (it has no athan). */
    val quiet: Boolean,
)

/** How the prayer times are drawn. */
enum class PrayerShape {
    /** A line through the day, a marker for each prayer. */
    LINE,

    /** The sun's path: the prayers on an arc, from Fajr low on one side to Isha low on the other. */
    ARC,

    /** One at a time, like Lantern's doors: the next prayer large, its neighbours fading away. */
    CAROUSEL,
}

/** The colours one theme gives the prayer times. */
class PrayerColors(
    val name: Int,
    val time: Int,
    val muted: Int,
    /** The day's line, and the markers of the prayers still to come. */
    val line: Int,
    /** The part of the line already passed today, the next prayer's name and the countdown. */
    val accent: Int,
    /** The little doorway that marks the next prayer. */
    val doorFill: Int,
    val doorStroke: Int,
    /** The wall behind, to cut the line around an open marker. */
    val bg: Int,
    /** Brass studs on that doorway (Riad); 0 for none. */
    val studs: Int = 0,
)

/**
 * Today's prayer times on the home screen, as a line through the day: a marker for each prayer,
 * the line filled in up to now, and the next prayer standing in a small doorway with how long
 * until it underneath. Prayers already passed are quieter.
 *
 * Arabic names read right to left (Fajr on the right); English ones left to right. [vertical]
 * stacks them top to bottom instead, for a corner of the screen (Doorway).
 *
 * Everything is drawn here, at a fixed size: nothing can be cut off, nothing can be selected,
 * and the once-a-minute update never moves anything else on the screen.
 */
@SuppressLint("ViewConstructor")
class PrayerStripView(context: Context, private val vertical: Boolean) : View(context) {

    companion object {
        /** Across: how far down the day's line runs (for lining other things up with it). */
        const val LINE_DP = 36f

        /** Down: the height of [n] prayers, one of them the next (with its countdown). */
        fun verticalHeightDp(n: Int) = n * 34f + 18f + 4f

        /** Arc: its size, and how far down its top (the highest point of the sun's path) is. */
        const val ARC_WIDTH_DP = 520f
        const val ARC_HEIGHT_DP = 172f
        const val ARC_TOP_DP = 38f

        /** Carousel: its size, and how far down the middle of the next prayer's time is. */
        const val CAROUSEL_WIDTH_DP = 540f
        const val CAROUSEL_HEIGHT_DP = 92f
        const val CAROUSEL_AXIS_DP = 44f
    }

    /** How to draw across (Doorway's top-to-bottom list ignores it). */
    var shape = PrayerShape.LINE
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private var colors = PrayerColors(Color.WHITE, Color.WHITE, Color.GRAY, Color.GRAY, Color.WHITE, 0, Color.WHITE, Color.BLACK)
    private var cells: List<PrayerCell> = emptyList()
    private var rtl = false
    /** Where now is on the line: 0 at the first prayer, 1 at the second, ... (may be below 0 or past the last). */
    private var nowPos = -1f
    private var message: String? = null

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val door = Path()
    private val oval = RectF()

    /** Fonts: the theme's display face for the names, the body face for the numbers. */
    private var nameFace: Typeface = Fonts.display(context, 500)
    private var nameFaceNext: Typeface = Fonts.display(context, 600)
    private var timeFace: Typeface = Fonts.body(context, 400)
    private var timeFaceNext: Typeface = Fonts.body(context, 600)

    init {
        isFocusable = false
        notePaint.typeface = Fonts.body(context, 500)
        timePaint.fontFeatureSettings = "tnum"
        notePaint.fontFeatureSettings = "tnum"
    }

    // ---------------- sizes (dp) ----------------

    /** Horizontal: the width of each prayer's column. */
    var columnDp = 84f

    /** Vertical: the whole width. */
    var widthDp = 160f

    private val nameSp get() = if (vertical) 15f else 15.5f
    private val timeSp get() = if (vertical) 15f else 18f
    private val noteSp = 11.5f

    // Horizontal rows: name, line, time, countdown.
    private val hNameBase = 17f
    private val hLine = LINE_DP
    private val hTimeBase = 66f
    private val hNoteBase = 86f
    private val hHeight = 94f

    // Vertical rows.
    private val vRow = 34f
    private val vNextExtra = 18f
    private val vLineX = 9f

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    fun applyTheme(c: PrayerColors) {
        colors = c
        nameFace = Fonts.display(context, 500)
        nameFaceNext = Fonts.display(context, 600)
        timeFace = Fonts.body(context, 400)
        timeFaceNext = Fonts.body(context, 600)
        notePaint.typeface = Fonts.body(context, 500)
        invalidate()
    }

    /** A single quiet line instead of the times (no city chosen yet). */
    fun showMessage(text: String) {
        if (message == text && cells.isEmpty()) return
        message = text
        cells = emptyList()
        requestLayout()
        invalidate()
    }

    fun show(list: List<PrayerCell>, rightToLeft: Boolean, now: Float) {
        val sizeChanged = list.size != cells.size || message != null || list.any { it.next } != cells.any { it.next }
        message = null
        cells = list
        rtl = rightToLeft
        nowPos = now
        if (sizeChanged) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val msg = message
        if (msg != null) {
            notePaint.textSize = sp(12f)
            setMeasuredDimension((notePaint.measureText(msg) + 4 * d).toInt(), (24 * d).toInt())
            return
        }
        val n = cells.size.coerceAtLeast(1)
        if (!vertical && shape == PrayerShape.ARC) {
            setMeasuredDimension((ARC_WIDTH_DP * d).toInt(), (ARC_HEIGHT_DP * d).toInt())
            return
        }
        if (!vertical && shape == PrayerShape.CAROUSEL) {
            setMeasuredDimension((CAROUSEL_WIDTH_DP * d).toInt(), (CAROUSEL_HEIGHT_DP * d).toInt())
            return
        }
        if (vertical) {
            val h = n * vRow + (if (cells.any { it.next }) vNextExtra else 0f) + 4f
            setMeasuredDimension((widthDp * d).toInt(), (h * d).toInt())
        } else {
            setMeasuredDimension((n * columnDp * d).toInt(), (hHeight * d).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        message?.let {
            notePaint.textSize = sp(12f)
            notePaint.color = colors.muted
            notePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, 2 * d, 16 * d, notePaint)
            return
        }
        if (cells.isEmpty()) return
        when {
            vertical -> drawVertical(canvas)
            shape == PrayerShape.ARC -> drawArc(canvas)
            shape == PrayerShape.CAROUSEL -> drawCarousel(canvas)
            else -> drawHorizontal(canvas)
        }
    }

    // ---------------- the sun's path ----------------

    private fun drawArc(c: Canvas) {
        val n = cells.size
        val w = ARC_WIDTH_DP * d
        val cx = w / 2f
        val rx = w / 2f - 28f * d
        val ry = 96f * d
        val cy = ARC_TOP_DP * d + ry
        // Fajr low on the reading side, Isha low on the other; evenly along the arc.
        val start = 166f
        val end = 14f
        fun angle(pos: Float): Float {
            val t = if (n > 1) pos / (n - 1) else 0.5f
            val a = start + (end - start) * t
            return if (rtl) 180f - a else a
        }
        fun px(a: Float) = cx + rx * Math.cos(Math.toRadians(a.toDouble())).toFloat()
        fun py(a: Float) = cy - ry * Math.sin(Math.toRadians(a.toDouble())).toFloat()

        // The path, then the part already passed.
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        shape.style = Paint.Style.STROKE
        shape.strokeCap = Paint.Cap.ROUND
        shape.strokeWidth = 1f * d
        shape.color = colors.line
        c.drawArc(oval, 180f + 8f, 164f, false, shape)
        if (nowPos > 0f) {
            val p = nowPos.coerceAtMost((n - 1).toFloat())
            val a0 = angle(0f)
            val a1 = angle(p)
            shape.strokeWidth = 2f * d
            shape.color = Look.withAlpha(colors.accent, 200)
            // Canvas angles run clockwise from 3 o'clock; ours anticlockwise, so negate.
            c.drawArc(oval, -a0, a0 - a1, false, shape)
            if (nowPos < n - 1) nowMark(c, px(a1), py(a1))
        }

        for ((i, cell) in cells.withIndex()) {
            val a = angle(i.toFloat())
            val x = px(a)
            val y = py(a)
            marker(c, cell, x, y)

            // Name and time inside the arc, under their marker.
            namePaint.textAlign = Paint.Align.CENTER
            namePaint.typeface = if (cell.next) nameFaceNext else nameFace
            namePaint.textSize = sp(if (cell.quiet) 13f else 14.5f)
            namePaint.color = when {
                cell.next -> colors.accent
                cell.past || cell.quiet -> colors.muted
                else -> colors.name
            }
            c.drawText(cell.name, x, y + 24f * d, namePaint)

            timePaint.textAlign = Paint.Align.CENTER
            timePaint.typeface = if (cell.next) timeFaceNext else timeFace
            timePaint.textSize = sp(if (cell.next) 18f else if (cell.quiet) 14f else 16f)
            timePaint.color = if (cell.past || cell.quiet) colors.muted else colors.time
            c.drawText(cell.time, x, y + 45f * d, timePaint)

            if (cell.note.isNotEmpty()) {
                notePaint.textAlign = Paint.Align.CENTER
                notePaint.textSize = sp(noteSp)
                notePaint.color = colors.accent
                c.drawText(cell.note, x, y + 63f * d, notePaint)
            }
        }
    }

    // ---------------- one at a time ----------------

    private fun drawCarousel(c: Canvas) {
        val n = cells.size
        val mid = cells.indexOfFirst { it.next }.let { if (it < 0) 0 else it }
        val w = CAROUSEL_WIDTH_DP * d
        val cx = w / 2f
        val axis = CAROUSEL_AXIS_DP * d
        // Neighbours either side, smaller and fainter the further they are, like the doors below.
        val offsets = floatArrayOf(0f, 128f, 228f)
        val alphas = intArrayOf(255, 170, 90)
        for (k in 2 downTo 0) {
            for (side in if (k == 0) intArrayOf(0) else intArrayOf(-1, 1)) {
                val i = mid + side * k
                if (i < 0 || i >= n) continue
                val cell = cells[i]
                // Earlier prayers on the reading side: the left in English, the right in Arabic.
                val dir = if (rtl) -side else side
                val x = cx + dir * offsets[k] * d
                val alpha = alphas[k]
                if (k == 0) {
                    // The next prayer: its name above, its time large on the line, the countdown below.
                    namePaint.textAlign = Paint.Align.CENTER
                    namePaint.typeface = nameFaceNext
                    namePaint.textSize = sp(17f)
                    namePaint.color = colors.accent
                    c.drawText(cell.name, x, axis - 20f * d, namePaint)
                    timePaint.textAlign = Paint.Align.CENTER
                    timePaint.typeface = timeFaceNext
                    timePaint.textSize = sp(27f)
                    timePaint.color = colors.time
                    c.drawText(cell.time, x, axis + 10f * d, timePaint)
                    if (cell.note.isNotEmpty()) {
                        notePaint.textAlign = Paint.Align.CENTER
                        notePaint.textSize = sp(12.5f)
                        notePaint.color = colors.accent
                        c.drawText(cell.note, x, axis + 32f * d, notePaint)
                    }
                    // A fine bead either side of the time, setting it apart from its neighbours.
                    val half = timePaint.measureText(cell.time) / 2f + 18f * d
                    for (sx in floatArrayOf(-1f, 1f)) {
                        val mx = x + sx * half
                        shape.style = Paint.Style.FILL
                        shape.color = Look.withAlpha(colors.accent, 120)
                        c.drawCircle(mx, axis, 2f * d, shape)
                    }
                } else {
                    namePaint.textAlign = Paint.Align.CENTER
                    namePaint.typeface = nameFace
                    namePaint.textSize = sp(if (k == 1) 14f else 12.5f)
                    namePaint.color = Look.withAlpha(if (cell.past || cell.quiet) colors.muted else colors.name, alpha)
                    c.drawText(cell.name, x, axis - 14f * d, namePaint)
                    timePaint.textAlign = Paint.Align.CENTER
                    timePaint.typeface = timeFace
                    timePaint.textSize = sp(if (k == 1) 17f else 14.5f)
                    timePaint.color = Look.withAlpha(if (cell.past || cell.quiet) colors.muted else colors.time, alpha)
                    c.drawText(cell.time, x, axis + 9f * d, timePaint)
                }
            }
        }
    }

    // ---------------- across ----------------

    private fun drawHorizontal(c: Canvas) {
        val n = cells.size
        val col = columnDp * d
        fun cx(i: Int) = (if (rtl) n - 1 - i else i) * col + col / 2f
        val ly = hLine * d

        // The day's line, then the part already passed.
        val first = cx(0)
        val last = cx(n - 1)
        shape.style = Paint.Style.STROKE
        shape.strokeCap = Paint.Cap.ROUND
        shape.strokeWidth = 1f * d
        shape.color = colors.line
        c.drawLine(first, ly, last, ly, shape)
        if (nowPos > 0f) {
            val p = nowPos.coerceAtMost((n - 1).toFloat())
            val i = p.toInt().coerceAtMost(n - 2)
            val x = cx(i) + (cx(i + 1) - cx(i)) * (p - i)
            shape.strokeWidth = 2f * d
            shape.color = Look.withAlpha(colors.accent, 200)
            c.drawLine(first, ly, x, ly, shape)
            if (nowPos < n - 1) nowMark(c, x, ly)
        }

        for ((i, cell) in cells.withIndex()) {
            val x = cx(i)
            marker(c, cell, x, ly)

            namePaint.textAlign = Paint.Align.CENTER
            namePaint.typeface = if (cell.next) nameFaceNext else nameFace
            namePaint.textSize = sp(if (cell.quiet) nameSp - 1.5f else nameSp)
            namePaint.color = when {
                cell.next -> colors.accent
                cell.past || cell.quiet -> colors.muted
                else -> colors.name
            }
            c.drawText(cell.name, x, hNameBase * d, namePaint)

            timePaint.textAlign = Paint.Align.CENTER
            timePaint.typeface = if (cell.next) timeFaceNext else timeFace
            timePaint.textSize = sp(if (cell.next) timeSp + 1f else if (cell.quiet) timeSp - 2f else timeSp)
            timePaint.color = when {
                cell.next -> colors.time
                cell.past || cell.quiet -> colors.muted
                else -> colors.time
            }
            c.drawText(cell.time, x, hTimeBase * d, timePaint)

            if (cell.note.isNotEmpty()) {
                notePaint.textAlign = Paint.Align.CENTER
                notePaint.textSize = sp(noteSp)
                notePaint.color = colors.accent
                c.drawText(cell.note, x, hNoteBase * d, notePaint)
            }
        }
    }

    // ---------------- down ----------------

    private fun drawVertical(c: Canvas) {
        val w = widthDp * d
        val lineX = if (rtl) w - vLineX * d else vLineX * d
        val textStart = if (rtl) w - 28 * d else 28 * d
        val textEnd = if (rtl) 4 * d else w - 4 * d
        val row = vRow * d

        // Where each row's marker sits.
        val ys = FloatArray(cells.size)
        var y = 0f
        for ((i, cell) in cells.withIndex()) {
            ys[i] = y + row / 2f
            y += row + if (cell.next) vNextExtra * d else 0f
        }

        shape.style = Paint.Style.STROKE
        shape.strokeCap = Paint.Cap.ROUND
        shape.strokeWidth = 1f * d
        shape.color = colors.line
        c.drawLine(lineX, ys.first(), lineX, ys.last(), shape)
        if (nowPos > 0f) {
            val p = nowPos.coerceAtMost((cells.size - 1).toFloat())
            val i = p.toInt().coerceAtMost(cells.size - 2)
            val ny = ys[i] + (ys[i + 1] - ys[i]) * (p - i)
            shape.strokeWidth = 2f * d
            shape.color = Look.withAlpha(colors.accent, 200)
            c.drawLine(lineX, ys.first(), lineX, ny, shape)
            if (nowPos < cells.size - 1) nowMark(c, lineX, ny)
        }

        for ((i, cell) in cells.withIndex()) {
            val cy = ys[i]
            marker(c, cell, lineX, cy)

            namePaint.typeface = if (cell.next) nameFaceNext else nameFace
            namePaint.textSize = sp(if (cell.quiet) nameSp - 1.5f else nameSp)
            namePaint.color = when {
                cell.next -> colors.accent
                cell.past || cell.quiet -> colors.muted
                else -> colors.name
            }
            namePaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            val base = cy + namePaint.textSize * 0.36f
            c.drawText(cell.name, textStart, base, namePaint)

            timePaint.typeface = if (cell.next) timeFaceNext else timeFace
            timePaint.textSize = sp(if (cell.next) timeSp + 1f else timeSp)
            timePaint.color = if (cell.past || cell.quiet) colors.muted else colors.time
            timePaint.textAlign = if (rtl) Paint.Align.LEFT else Paint.Align.RIGHT
            c.drawText(cell.time, textEnd, cy + timePaint.textSize * 0.36f, timePaint)

            if (cell.note.isNotEmpty()) {
                notePaint.textSize = sp(noteSp)
                notePaint.color = colors.accent
                notePaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                c.drawText(cell.note, textStart, cy + row / 2f + 8 * d, notePaint)
            }
        }
    }

    // ---------------- markers ----------------

    /** Now: a small light with a soft glow around it, so it never looks like a prayer. */
    private fun nowMark(c: Canvas, x: Float, y: Float) {
        shape.style = Paint.Style.FILL
        shape.color = Look.withAlpha(colors.accent, 50)
        c.drawCircle(x, y, 8f * d, shape)
        shape.color = colors.bg
        c.drawCircle(x, y, 4.6f * d, shape)
        shape.color = colors.accent
        c.drawCircle(x, y, 3.4f * d, shape)
    }

    /** A dot on the line: filled once passed, open while still to come; the next prayer a small doorway. */
    private fun marker(c: Canvas, cell: PrayerCell, x: Float, y: Float) {
        if (cell.next) {
            val w = 13f * d
            val h = 17f * d
            archPath(door, oval, x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f, 2f * d)
            shape.style = Paint.Style.FILL
            shape.color = colors.doorFill
            c.drawPath(door, shape)
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1.4f * d
            shape.color = colors.doorStroke
            c.drawPath(door, shape)
            if (colors.studs != 0) {
                shape.style = Paint.Style.FILL
                shape.color = colors.studs
                for (k in -1..1) c.drawCircle(x + k * 2.6f * d, y + 1.5f * d, 0.9f * d, shape)
            }
            return
        }
        val r = if (cell.quiet) 2.6f * d else 3.6f * d
        shape.style = Paint.Style.FILL
        if (cell.past) {
            // Passed: a small solid bead on the passed line.
            shape.color = Look.withAlpha(colors.accent, 200)
            c.drawCircle(x, y, if (cell.quiet) 2.2f * d else 3f * d, shape)
            return
        }
        // Still to come: an open ring; the wall shows through, so the line stops at it.
        shape.color = colors.bg
        c.drawCircle(x, y, r, shape)
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = 1.3f * d
        shape.color = if (cell.quiet) colors.muted else colors.name
        c.drawCircle(x, y, r, shape)
    }
}
