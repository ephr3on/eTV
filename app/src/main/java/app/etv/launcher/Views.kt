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

class ArchDrawable(private val density: Float) : Drawable() {
    var fill = 0
    var stroke = 0
    var strokeWidth = density
    var inner = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        val half = strokeWidth / 2f
        val br = 8f * density
        archPath(path, oval, b.left + half, b.top + half, b.right - half, b.bottom - half, br)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        if (strokeWidth > 0f && stroke != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = stroke
            canvas.drawPath(path, paint)
        }
        if (inner != 0) {
            val inset = 5f * density
            archPath(path, oval, b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, br * 0.6f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f * density
            paint.color = inner
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** One app, shaped like an arched door. Fills with the accent colour when selected. */
@SuppressLint("ViewConstructor")
class ArchTile(
    context: Context,
    val app: AppEntry,
    private val pal: Palette,
    private val accent: Int,
    scale: Float,
    iconStyle: Int,
    private val nameMode: Int,
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
        softScale(if (gainFocus) 1.12f else 1f)
    }

    private fun applyState() {
        val focused = isFocused
        val d = resources.displayMetrics.density
        when {
            moving -> {
                arch.fill = pal.tile
                arch.stroke = accent
                arch.strokeWidth = 2.5f * d
                arch.inner = Look.withAlpha(accent, 110)
                letter.setTextColor(accent)
                label.setTextColor(accent)
            }
            focused -> {
                arch.fill = accent
                arch.stroke = accent
                arch.strokeWidth = d
                arch.inner = Look.withAlpha(pal.bg, 80)
                letter.setTextColor(pal.bg)
                label.setTextColor(pal.bg)
            }
            else -> {
                arch.fill = pal.tile
                arch.stroke = pal.tileStroke
                arch.strokeWidth = d
                arch.inner = pal.tileInner
                letter.setTextColor(pal.text)
                label.setTextColor(pal.label)
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
): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpi(18f), 0, context.dpi(18f), 0)
        isFocusable = true
        isClickable = true
        background = pillBackground(context, 14f, Color.TRANSPARENT, pal.tile, accent)
    }
    val name = TextView(context).apply {
        text = label
        typeface = Fonts.body(context, 400)
        sp(15f)
        setTextColor(pal.text)
    }
    row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    if (swatch != null) {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatch)
                setStroke(context.dpi(1f), pal.tileStroke)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(context.dpi(14f), context.dpi(14f)).apply {
            marginEnd = context.dpi(10f)
        })
    }
    if (value.isNotEmpty()) {
        val v = TextView(context).apply {
            text = value
            typeface = Fonts.body(context, 400)
            sp(14f)
            setTextColor(focusColors(accent, pal.muted))
            isDuplicateParentStateEnabled = true
        }
        row.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    return row
}

