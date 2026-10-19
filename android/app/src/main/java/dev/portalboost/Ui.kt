package dev.portalboost

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

/** iOS-style system colours with PlayStation blue as the tint; one set for dark, one for light. */
class Palette(val dark: Boolean) {
    private fun c(d: Long, l: Long) = (if (dark) d else l).toInt()
    val bg = c(0xFF000000, 0xFFF2F2F7)
    val grouped = c(0xFF1C1C1E, 0xFFFFFFFF)
    val sep = c(0xFF38383A, 0xFFD8D8DC)
    val label = c(0xFFFFFFFF, 0xFF000000)
    val label2 = c(0xFF9D9DA3, 0xFF6D6D72)
    val label3 = c(0xFF5A5A5F, 0xFFA5A5AA)
    val tint = c(0xFF2E8EFF, 0xFF0070D1)
    val fill = c(0xFF2C2C2F, 0xFFE3E3E8)
    val ringTrack = c(0xFF232326, 0xFFE3E3E8)
    val thumb = c(0xFF636366, 0xFFFFFFFF)
    val green = c(0xFF30D158, 0xFF248A3D)
    val orange = c(0xFFFF9F0A, 0xFFC75300)
    val bar = c(0xFF121214, 0xFFF9F9FB)
    val log = c(0xFF0B0B0D, 0xFFF2F2F7)
    val pressed = c(0xFF3A3A3C, 0xFFD1D1D6)

    companion object {
        // Tiles take the four colours of the PlayStation logo.
        val TEAL = 0xFF00A39A.toInt()
        val BLUE = 0xFF0070D1.toInt()
        val YELLOW = 0xFFE5A800.toInt()
        val RED = 0xFFE4002B.toInt()
    }
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun face(weight: Int, mono: Boolean = false): Typeface {
    val base = if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
    else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
}

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.tinted(res: Int, color: Int): Drawable = getDrawable(res)!!.mutate().apply { setTint(color) }

/** Touch feedback for a tappable row on a grouped list. */
fun ripple(color: Int): RippleDrawable = RippleDrawable(ColorStateList.valueOf(color), null, ColorDrawable(Color.WHITE))

/** The on/off control: a slim ring around a disc; the ring fills with the live bitrate. */
class PowerRing(ctx: Context, private val pal: Palette) : View(ctx) {
    enum class Mode { IDLE, BUSY, LIVE }

    private var mode = Mode.IDLE
    private var target = 0f
    private var shown = 0f
    private var spin = 0f
    private var big = ""
    private var unit = ""
    private var cta = "Start"
    private val stroke = ctx.dpf(8.5f)
    private val oval = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = pal.ringTrack }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = pal.tint
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.grouped }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.spf(60f); color = pal.label; typeface = face(600); textAlign = Paint.Align.CENTER; letterSpacing = -0.03f
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.spf(15f); color = pal.label2; typeface = face(500); textAlign = Paint.Align.CENTER
    }
    private val ctaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.spf(17f); color = pal.label; typeface = face(600); textAlign = Paint.Align.CENTER
    }
    private val power = ctx.tinted(R.drawable.ic_power, pal.tint)

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }
    private var fillAnim: ValueAnimator? = null

    /** [fraction] is the ring fill (0..1) shown in LIVE mode. */
    fun set(mode: Mode, fraction: Float, big: String, unit: String, cta: String) {
        if (mode != this.mode) {
            if (mode == Mode.BUSY) spinner.start() else spinner.cancel()
            this.mode = mode
        }
        this.big = big; this.unit = unit; this.cta = cta
        val t = if (mode == Mode.LIVE) fraction.coerceIn(0f, 1f) else 0f
        if (t != target) {
            target = t
            fillAnim?.cancel()
            fillAnim = ValueAnimator.ofFloat(shown, t).apply {
                duration = 700; interpolator = DecelerateInterpolator(2f)
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        contentDescription = when (mode) {
            Mode.IDLE -> "Start boost"
            Mode.BUSY -> "$cta. Tap to stop"
            Mode.LIVE -> "Boosting at $big megabits per second. Tap to stop"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(228)
        setMeasuredDimension(s, s)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val k = if (isPressed) 0.97f else 1f
        animate().scaleX(k).scaleY(k).setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - stroke / 2f - context.dpf(1f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, trackPaint)
        when {
            mode == Mode.BUSY -> canvas.drawArc(oval, spin - 90f, 80f, false, arcPaint)
            shown > 0.001f -> canvas.drawArc(oval, -90f, 360f * shown, false, arcPaint)
        }
        canvas.drawCircle(cx, cy, context.dpf(95f), discPaint)
        when (mode) {
            Mode.LIVE -> {
                canvas.drawText(big, cx, cy + context.dpf(14f), bigPaint)
                canvas.drawText(unit, cx, cy + context.dpf(40f), unitPaint)
            }
            Mode.BUSY -> {
                canvas.drawText(cta, cx, cy, ctaPaint)
                canvas.drawText(unit, cx, cy + context.dpf(22f), unitPaint)
            }
            Mode.IDLE -> {
                val h = context.dp(40)
                power.setBounds((cx - h / 2).toInt(), (cy - context.dpf(38f)).toInt(), (cx + h / 2).toInt(), (cy - context.dpf(38f)).toInt() + h)
                power.draw(canvas)
                canvas.drawText(cta, cx, cy + context.dpf(28f), ctaPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        spinner.cancel(); fillAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** iOS-style segmented control with a sliding thumb. */
class Segmented(
    ctx: Context,
    private val pal: Palette,
    private val items: List<String>,
    private val onPick: (Int) -> Unit,
) : View(ctx) {
    private var selected = -1
    private var pos = 0f
    private var anim: ValueAnimator? = null
    var locked = false
        set(v) { field = v; alpha = if (v) 0.45f else 1f }

    private val pad = ctx.dpf(2f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.fill }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.thumb
        if (!pal.dark) setShadowLayer(ctx.dpf(4f), 0f, ctx.dpf(1.5f), 0x26000000)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.spf(15f); typeface = face(600); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        if (!pal.dark) setLayerType(LAYER_TYPE_SOFTWARE, null) // the thumb shadow needs it
        isClickable = true
    }

    fun select(i: Int) {
        if (i == selected) return
        val from = if (selected < 0) i.toFloat() else pos
        selected = i
        contentDescription = "Quality profile${if (i >= 0) ", ${items[i]} selected" else ""}"
        anim?.cancel()
        if (i < 0) { invalidate(); return }
        anim = ValueAnimator.ofFloat(from, i.toFloat()).apply {
            duration = 280; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(40))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, context.dpf(10f), context.dpf(10f), trackPaint)
        val seg = (w - pad * 2) / items.size
        if (selected >= 0) {
            rect.set(pad + pos * seg, pad, pad + pos * seg + seg, h - pad)
            canvas.drawRoundRect(rect, context.dpf(8f), context.dpf(8f), thumbPaint)
        }
        val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        items.forEachIndexed { i, s ->
            textPaint.color = if (i == selected) pal.label else pal.label2
            canvas.drawText(s, pad + seg * i + seg / 2, baseline, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && !locked) {
            val i = ((e.x - pad) / ((width - pad * 2) / items.size)).toInt().coerceIn(0, items.size - 1)
            performClick()
            if (i != selected) onPick(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
