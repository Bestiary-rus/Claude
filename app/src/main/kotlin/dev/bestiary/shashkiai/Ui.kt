package dev.bestiary.shashkiai

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    const val BG = 0xFF12171A.toInt()
    const val SURFACE = 0xFF1D2428.toInt()
    const val SURFACE_HI = 0xFF283238.toInt()
    const val ACCENT = 0xFF8FD16A.toInt()
    const val ON_ACCENT = 0xFF10200A.toInt()
    const val TEXT = 0xFFEAEFEA.toInt()
    const val MUTED = 0xFF9AA79F.toInt()
    const val DANGER = 0xFFE5746A.toInt()
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

object Ui {
    fun rounded(color: Int, radiusDp: Int, ctx: Context) = GradientDrawable().apply {
        setColor(color); cornerRadius = ctx.dp(radiusDp).toFloat()
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 15f, color: Int = Colors.TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.1f)
        }

    fun button(ctx: Context, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label; isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (primary) Colors.ON_ACCENT else if (danger) Colors.DANGER else Colors.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(if (primary) Colors.ACCENT else Colors.SURFACE_HI, 14, ctx)
            stateListAnimator = null
            minHeight = ctx.dp(52); minimumHeight = ctx.dp(52)
            setOnClickListener { onClick() }
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Colors.SURFACE, 18, ctx)
        setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(16))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           top: Int = 0, ctx: Context? = null, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { if (ctx != null) topMargin = ctx.dp(top) }

    /** Single-choice row of pill buttons. */
    fun chips(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.background = rounded(if (i == sel) Colors.ACCENT else Colors.SURFACE_HI, 12, ctx)
            v.setTextColor(if (i == sel) Colors.ON_ACCENT else Colors.TEXT)
        }
        options.forEachIndexed { i, o ->
            val v = text(ctx, o, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(12))
                setOnClickListener { paint(i); onSelect(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = ctx.dp(8)
            })
        }
        paint(selected)
        return row
    }
}

/** Learning curve: strength against the material-counting baseline over training games. */
class ChartView(context: Context) : View(context) {
    var points: List<Brain.Point> = emptyList()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = context.dp(34).toFloat(); val r = context.dp(8).toFloat()
        val t = context.dp(8).toFloat(); val b = context.dp(22).toFloat()
        val cw = w - l - r; val ch = h - t - b
        p.textSize = context.dp(10).toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        for (g in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = t + ch * (1 - g)
            p.color = if (g == 0.5f) 0x66FFFFFF else 0x22FFFFFF
            p.pathEffect = if (g == 0.5f) DashPathEffect(floatArrayOf(10f, 8f), 0f) else null
            canvas.drawLine(l, y, w - r, y, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL; p.color = Colors.MUTED
            canvas.drawText("${(g * 100).toInt()}%", 0f, y + p.textSize / 3, p)
            p.style = Paint.Style.STROKE
        }
        if (points.isEmpty()) {
            p.style = Paint.Style.FILL; p.color = Colors.MUTED
            canvas.drawText("Данных пока нет — запусти обучение", l + 10f, t + ch / 2, p)
            return
        }
        val maxG = maxOf(points.last().games, 1L).toFloat()
        val xs = points.map { l + cw * (it.games / maxG) }
        val ys = points.map { t + ch * (1 - it.score) }
        val path = Path()
        for (i in points.indices) if (i == 0) path.moveTo(xs[i], ys[i]) else path.lineTo(xs[i], ys[i])
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(2).toFloat(); p.color = Colors.ACCENT
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (i in points.indices) canvas.drawCircle(xs[i], ys[i], context.dp(3).toFloat(), p)
        p.color = Colors.MUTED
        canvas.drawText("партий: $maxG".replace(".0", ""), l, h - 4f, p)
    }
}
