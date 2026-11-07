package app.etv.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// ---------- small helpers ----------

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun TextView.sp(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, size)

private val soft = DecelerateInterpolator(1.6f)

/** The one animation in the launcher: a short, soft grow on focus. */
fun View.softScale(to: Float) {
    animate().scaleX(to).scaleY(to).setDuration(170).setInterpolator(soft).start()
}

fun focusColors(focused: Int, normal: Int) = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
    intArrayOf(focused, normal),
)

/** Rounded shape: transparent with a thin outline, filled with [focusFill] when selected. */
fun pillBackground(context: Context, radiusDp: Float, stroke: Int, focusFill: Int, focusStroke: Int = focusFill): StateListDrawable {
    val r = context.dp(radiusDp)
    val normal = GradientDrawable().apply {
        cornerRadius = r
        setColor(Color.TRANSPARENT)
        setStroke(context.dpi(1f), stroke)
    }
    val focused = GradientDrawable().apply {
        cornerRadius = r
        setColor(focusFill)
        setStroke(context.dpi(1.5f), focusStroke)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }
}

/** A simple list dialog that works well with a remote. */
fun Activity.choose(title: String, items: Array<String>, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setItems(items) { _, which -> onPick(which) }
        .show()
}

// ---------- the arch ----------

/** Builds a Sidi Bou Said doorway: semicircular top, softly rounded bottom corners. */
fun archPath(path: Path, oval: RectF, l: Float, t: Float, r: Float, b: Float, bottomRadius: Float) {
    path.reset()
    val w = r - l
    val rad = w / 2f
    oval.set(l, t, r, t + w)
    path.moveTo(l, t + rad)
    path.arcTo(oval, 180f, 180f, false)
    path.lineTo(r, b - bottomRadius)
    path.quadTo(r, b, r - bottomRadius, b)
    path.lineTo(l + bottomRadius, b)
    path.quadTo(l, b, l, b - bottomRadius)
    path.close()
}

/** A cartouche: a tall capsule, both ends fully round. */
fun cartouchePath(path: Path, oval: RectF, l: Float, t: Float, r: Float, b: Float) {
    path.reset()
    val rad = (r - l) / 2f
    oval.set(l, t, r, b)
    path.addRoundRect(oval, rad, rad, Path.Direction.CW)
}

class ArchDrawable(private val density: Float) : Drawable() {
    var fill = 0
    var stroke = 0
    var strokeWidth = density
    var inner = 0
    var shape = TileShape.ARCH

    /** A light frame between the outline and the door (Riad); 0 for none. */
    var ring = 0
    var ringWidth = 3f * density

    /** Three brass studs near the top (Riad); 0 for none. */
    var studs = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    private fun shapePath(l: Float, t: Float, r: Float, b: Float, br: Float) {
        if (shape == TileShape.CARTOUCHE) cartouchePath(path, oval, l, t, r, b)
        else archPath(path, oval, l, t, r, b, br)
    }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val half = strokeWidth / 2f
        val br = 8f * density
        var inset = half
        if (ring != 0) {
            // The whitewashed frame, then the door inside it.
            shapePath(b.left + half, b.top + half, b.right - half, b.bottom - half, br)
            paint.style = Paint.Style.FILL
            paint.color = ring
            canvas.drawPath(path, paint)
            inset = half + ringWidth
        }
        shapePath(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, br * 0.8f)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)

        if (inner != 0) {
            val i = inset + 4.5f * density
            shapePath(b.left + i, b.top + i, b.right - i, b.bottom - i, br * 0.6f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f * density
            paint.color = inner
            canvas.drawPath(path, paint)
        }

        if (studs != 0) {
            paint.style = Paint.Style.FILL
            paint.color = studs
            val cy = b.top + inset + (b.width() * 0.22f)
            val cx = b.exactCenterX()
            val gap = 7f * density
            for (k in -1..1) canvas.drawCircle(cx + k * gap, cy, 1.6f * density, paint)
        }

        if (strokeWidth > 0f && stroke != 0) {
            shapePath(b.left + half, b.top + half, b.right - half, b.bottom - half, br)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = stroke
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * One app, shaped like an arched door (or a cartouche). Fills with the accent colour when
 * selected, or, when [fillOnFocus] is off (Riad), keeps its colour and gains an accent ring.
 * [grow] is how much it grows when selected; 0 leaves its size to the caller (Lantern).
 */
@SuppressLint("ViewConstructor")
class ArchTile(
    context: Context,
    val app: AppEntry,
    private val pal: Palette,
    private val accent: Int,
    scale: Float,
    iconStyle: Int,
    private val nameMode: Int,
    private val shape: TileShape = TileShape.ARCH,
    private val fillOnFocus: Boolean = true,
    private val grow: Float = 1.12f,
) : FrameLayout(context) {

    val tileW: Int = context.dpi(96f * scale)
    val tileH: Int = context.dpi(126f * scale)

    private val arch = ArchDrawable(resources.displayMetrics.density)
    private val icon = ImageView(context)
    private val letter = TextView(context)
    private val label = TextView(context)
    private val useLetter = iconStyle == 1

    var moving = false
        set(v) {
            field = v
            applyState()
        }

    init {
        arch.shape = shape
        background = arch
        isFocusable = true
        isClickable = true
        isLongClickable = true
        contentDescription = app.label
        clipChildren = false

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dpi(8f), context.dpi(14f * scale), context.dpi(8f), 0)
        }

        if (useLetter) {
            letter.text = app.monogram
            letter.typeface = Fonts.display(context, 600)
            letter.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.dp(32f * scale))
            letter.includeFontPadding = false
            letter.gravity = Gravity.CENTER
            column.addView(letter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            icon.setImageDrawable(AppRepo.icon(context, app))
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            val s = context.dpi(44f * scale)
            column.addView(icon, LinearLayout.LayoutParams(s, s))
        }

        label.text = app.label
        label.typeface = Fonts.body(context, 400)
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.dp(11f * scale))
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        column.addView(
            label,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dpi(8f * scale)
            },
        )

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Grow upwards from the doorstep, like the mock-up.
        pivotX = w / 2f
        pivotY = h.toFloat()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyState()
        if (grow > 0f) softScale(if (gainFocus) grow else 1f)
    }

    private fun applyState() {
        val focused = isFocused
        val d = resources.displayMetrics.density
        arch.ring = pal.ring
        arch.studs = pal.studs
        when {
            moving -> {
                arch.fill = pal.tile
                arch.stroke = accent
                arch.strokeWidth = 2.5f * d
                arch.inner = Look.withAlpha(accent, 110)
                letter.setTextColor(if (pal.ring != 0) pal.tileText else accent)
                label.setTextColor(if (pal.ring != 0) pal.tileLabel else accent)
            }
            focused && fillOnFocus -> {
                arch.fill = accent
                arch.stroke = accent
                arch.strokeWidth = d
                arch.inner = Look.withAlpha(pal.onAccent, 80)
                arch.ring = 0
                arch.studs = 0
                letter.setTextColor(pal.onAccent)
                label.setTextColor(pal.onAccent)
            }
            focused -> {
                // The door keeps its colour; a ring in the highlight colour marks it.
                arch.fill = pal.tile
                arch.stroke = accent
                arch.strokeWidth = 3f * d
                arch.inner = pal.tileInner
                letter.setTextColor(pal.tileText)
                label.setTextColor(pal.tileText)
            }
            else -> {
                arch.fill = pal.tile
                arch.stroke = pal.tileStroke
                arch.strokeWidth = d
                arch.inner = pal.tileInner
                letter.setTextColor(pal.tileText)
                label.setTextColor(pal.tileLabel)
            }
        }
        label.typeface = Fonts.body(context, if (focused || moving) 500 else 400)
        label.visibility = when (nameMode) {
            0 -> View.VISIBLE
            1 -> if (focused || moving) View.VISIBLE else View.INVISIBLE
            else -> View.GONE
        }
        arch.invalidateSelf()
    }
}

/** The two large, faint arches framing the whole screen. */
class ArchBackgroundView(context: Context) : View(context) {
    var outer = 0
    var innerColor = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        drawArch(canvas, 70f * d, 24f * d, outer, 1f * d)
        drawArch(canvas, 86f * d, 40f * d, innerColor, 0.6f * d)
    }

    private fun drawArch(canvas: Canvas, inset: Float, top: Float, color: Int, sw: Float) {
        val l = inset
        val r = width - inset
        if (r <= l) return
        val rad = (r - l) / 2f
        val bottom = height + 10f
        path.reset()
        path.moveTo(l, bottom)
        path.lineTo(l, top + rad)
        oval.set(l, top, r, top + 2f * rad)
        path.arcTo(oval, 180f, 180f, false)
        path.lineTo(r, bottom)
        paint.color = color
        paint.strokeWidth = sw
        canvas.drawPath(path, paint)
    }
}

/**
 * A row of door studs, the middle one in the accent colour. While something plays in the
 * background they show how far along it is instead: studs light up one by one in the accent
 * colour, the current one glowing in as it fills.
 */
class DotsView(context: Context) : View(context) {
    var dot = 0
    var accent = 0

    /** 0–1 while something plays, -1 for the plain studs. */
    var progress = -1f
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dpi(130f), context.dpi(8f))
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cy = height / 2f
        val n = 9
        for (i in 0 until n) {
            val cx = 5f * d + i * 15f * d
            if (progress < 0f) {
                val center = i == 4
                paint.color = if (center) accent else dot
                canvas.drawCircle(cx, cy, if (center) 2.3f * d else 1.5f * d, paint)
            } else {
                // How lit this stud is: 0 = not yet, 1 = passed.
                val lit = (progress * n - i).coerceIn(0f, 1f)
                paint.color = dot
                canvas.drawCircle(cx, cy, 1.7f * d, paint)
                if (lit > 0f) {
                    paint.color = Look.withAlpha(accent, (60 + 195 * lit).toInt())
                    canvas.drawCircle(cx, cy, (1.7f + 0.6f * lit) * d, paint)
                }
            }
        }
    }
}

// ---------- settings-style rows (Customise and Home setup) ----------

/** A small spaced-out section heading. */
fun sectionLabel(context: Context, pal: Palette, text: String): TextView = TextView(context).apply {
    this.text = text.uppercase()
    typeface = Fonts.body(context, 500)
    sp(11f)
    letterSpacing = 0.18f
    setTextColor(pal.muted)
}

/** Quiet explanatory text. */
fun noteText(context: Context, pal: Palette, text: String): TextView = TextView(context).apply {
    this.text = text
    typeface = Fonts.body(context, 300)
    sp(12.5f)
    setTextColor(pal.muted)
    setLineSpacing(0f, 1.3f)
}

/** A focusable row: label on the left, current value on the right. Caller sets the click. */
fun settingRow(
    context: Context,
    pal: Palette,
    accent: Int,
    label: String,
    value: String,
    swatch: Int? = null,
): LinearLayout = optionRow(context, pal, accent, label, value, swatch = swatch)

/**
 * A row of Customise or of a [Sheet]: an optional doorway mark (filled when it's the chosen one),
 * the label with a quiet line under it, then the value, a colour, an on / off switch, or a "›" for
 * rows that open something. [setRowValue], [setRowDetail] and [setRowToggle] change it in place.
 */
fun optionRow(
    context: Context,
    pal: Palette,
    accent: Int,
    label: String,
    value: String = "",
    detail: String = "",
    swatch: Int? = null,
    toggle: Boolean? = null,
    opens: Boolean = false,
    checked: Boolean? = null,
): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(52f)
        setPadding(context.dpi(18f), context.dpi(9f), context.dpi(18f), context.dpi(9f))
        isFocusable = true
        isClickable = true
        background = pillBackground(context, 14f, Color.TRANSPARENT, pal.card, accent)
    }
    if (checked != null) {
        val mark = View(context).apply {
            tag = "check"
            background = doorMark(context, pal, accent, checked)
        }
        row.addView(mark, LinearLayout.LayoutParams(context.dpi(12f), context.dpi(16f)).apply {
            marginEnd = context.dpi(14f)
        })
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        tag = "label"
        text = label
        typeface = Fonts.body(context, if (checked == true) 500 else 400)
        sp(15f)
        setTextColor(pal.text)
    })
    texts.addView(TextView(context).apply {
        tag = "detail"
        text = detail
        typeface = Fonts.body(context, 300)
        sp(12.5f)
        setLineSpacing(0f, 1.15f)
        setTextColor(focusColors(pal.label, pal.muted))
        isDuplicateParentStateEnabled = true
        visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dpi(2f)
    })
    row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (swatch != null) {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatch)
                setStroke(context.dpi(1f), pal.tileStroke)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(context.dpi(14f), context.dpi(14f)).apply {
            marginStart = context.dpi(12f)
            marginEnd = context.dpi(10f)
        })
    }
    row.addView(TextView(context).apply {
        tag = "value"
        text = value
        typeface = Fonts.body(context, 400)
        sp(14f)
        setTextColor(focusColors(accent, pal.muted))
        isDuplicateParentStateEnabled = true
        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = context.dpi(12f)
    })
    if (toggle != null) {
        val sw = SwitchView(context).apply {
            tag = "switch"
            on = toggle
            onColor = accent
            offColor = pal.muted
            thumbOn = pal.onAccent
            thumbOff = pal.muted
        }
        row.addView(sw, LinearLayout.LayoutParams(context.dpi(34f), context.dpi(18f)).apply {
            marginStart = context.dpi(14f)
        })
    }
    if (opens) {
        row.addView(TextView(context).apply {
            text = "›"
            typeface = Fonts.body(context, 300)
            sp(20f)
            includeFontPadding = false
            setTextColor(focusColors(accent, pal.muted))
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpi(12f)
        })
    }
    return row
}

fun setRowValue(row: View, text: String) {
    row.findViewWithTag<TextView>("value")?.apply {
        if (this.text?.toString() != text) this.text = text
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }
}

/** Changes the quiet line under a row's label; [color] (e.g. the highlight colour) or 0 for the usual. */
fun setRowDetail(row: View, text: String, pal: Palette, color: Int = 0) {
    row.findViewWithTag<TextView>("detail")?.apply {
        if (this.text?.toString() != text) this.text = text
        visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (color != 0) setTextColor(color) else setTextColor(focusColors(pal.label, pal.muted))
    }
}

fun setRowToggle(row: View, on: Boolean) {
    row.findViewWithTag<SwitchView>("switch")?.on = on
}

/** The small doorway that marks a choice: an outline, or filled with the highlight colour when chosen. */
fun doorMark(context: Context, pal: Palette, accent: Int, chosen: Boolean): GradientDrawable = GradientDrawable().apply {
    val top = context.dp(6f)
    val bottom = context.dp(1.5f)
    cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    if (chosen) setColor(accent) else {
        setColor(Color.TRANSPARENT)
        setStroke(context.dpi(1f), pal.tileStroke)
    }
}

/** A small on / off switch for options that are simply on or off. */
class SwitchView(context: Context) : View(context) {
    var on = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var onColor = 0
    var offColor = 0
    var thumbOn = 0
    var thumbOff = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 0.75f * d
        rect.set(inset, inset, w - inset, h - inset)
        val r = rect.height() / 2f
        if (on) {
            paint.style = Paint.Style.FILL
            paint.color = onColor
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = offColor
        }
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (on) thumbOn else thumbOff
        val thumb = h / 2f - 4f * d
        canvas.drawCircle(if (on) w - h / 2f else h / 2f, h / 2f, thumb, paint)
    }
}


/**
 * A horizontal row that keeps the selected item in the middle of the screen (Lantern).
 * HorizontalScrollView's own scrolling is switched off; [centreOn] does it all.
 */
class CentreScrollView(context: Context) : HorizontalScrollView(context) {
    private val tmp = Rect()

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0

    fun centreOn(child: View, smooth: Boolean, attempt: Int = 0) {
        val content = if (childCount > 0) getChildAt(0) else return
        if (width == 0 || child.width == 0 || isLayoutRequested || content.isLayoutRequested) {
            if (attempt < 8) post { centreOn(child, false, attempt + 1) }
            return
        }
        child.getDrawingRect(tmp)
        offsetDescendantRectToMyCoords(child, tmp)
        val maxScroll = (content.width - width).coerceAtLeast(0)
        val target = (tmp.centerX() - width / 2).coerceIn(0, maxScroll)
        if (target == scrollX) return
        if (smooth) smoothScrollTo(target, 0) else scrollTo(target, 0)
    }

    /** Padding for each end of the row so an item [itemWidth] wide can sit in the middle. */
    fun centrePadding(itemWidth: Int): Int {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        return ((w - itemWidth) / 2).coerceAtLeast(0)
    }
}

/** Riad: a thin band of blue-and-white star tiles along the floor. */
class TileBandView(context: Context) : View(context) {
    var tileColor = 0
    var ground = 0
    var centre = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawColor(ground)
        val s = h * 0.46f
        var x = h / 2f
        while (x < width + h) {
            val cy = h / 2f
            paint.color = tileColor
            // An eight-pointed star: two squares, one turned 45 degrees.
            canvas.drawRect(x - s / 2, cy - s / 2, x + s / 2, cy + s / 2, paint)
            path.reset()
            val r = s / 2f * 1.414f
            path.moveTo(x, cy - r)
            path.lineTo(x + r, cy)
            path.lineTo(x, cy + r)
            path.lineTo(x - r, cy)
            path.close()
            canvas.drawPath(path, paint)
            paint.color = centre
            canvas.drawCircle(x, cy, h * 0.1f, paint)
            x += h
        }
    }
}

/** Cartouche: a quiet frieze of papyrus stems along the floor. */
class PapyrusView(context: Context) : View(context) {
    var color = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val h = height.toFloat()
        val step = 18f * d
        var x = step / 2f
        paint.color = color
        while (x < width) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f * d
            canvas.drawLine(x, h, x, h * 0.4f, paint)
            paint.style = Paint.Style.FILL
            path.reset()
            path.moveTo(x, h * 0.4f)
            path.lineTo(x - 3.5f * d, 0f)
            path.lineTo(x + 3.5f * d, 0f)
            path.close()
            canvas.drawPath(path, paint)
            x += step
        }
    }
}
