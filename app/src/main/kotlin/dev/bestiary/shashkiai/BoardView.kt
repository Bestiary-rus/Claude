package dev.bestiary.shashkiai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.bestiary.shashkiai.engine.Move
import dev.bestiary.shashkiai.engine.Piece
import dev.bestiary.shashkiai.engine.Position

/** Draws the board and pieces, reports taps as cell indices (row * 8 + col in White's view). */
class BoardView(context: Context) : View(context) {
    var position: Position = Position.initial()
        set(v) { field = v; invalidate() }
    var flipped = false
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate() }
    var targets: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    var movable: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    var lastMove: Move? = null
        set(v) { field = v; invalidate() }
    var hint: Move? = null
        set(v) { field = v; invalidate() }
    var onCellTap: ((Int) -> Unit)? = null

    private val light = 0xFFEEEED2.toInt()
    private val dark = 0xFF6E9A4F.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    // animation state
    private var animFraction = 1f
    private var animMove: Move? = null
    private var animBefore: Position? = null
    private var animator: ValueAnimator? = null

    private var size = 0f
    private var cell = 0f

    override fun onMeasure(w: Int, h: Int) {
        val s = minOf(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(h))
        setMeasuredDimension(s, s)
    }

    private fun center(c: Int): Pair<Float, Float> {
        var r = c / 8; var col = c % 8
        if (flipped) { r = 7 - r; col = 7 - col }
        return Pair((col + 0.5f) * cell, (r + 0.5f) * cell)
    }

    fun animateMove(before: Position, m: Move, after: Position, onEnd: () -> Unit) {
        animator?.cancel()
        animBefore = before
        animMove = m
        position = after
        val segs = m.path.size
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (140L * segs + 60).coerceAtMost(700)
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { animFraction = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animMove = null; animBefore = null; animFraction = 1f; invalidate(); onEnd()
                }
            })
        }
        animator = a
        a.start()
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        size = width.toFloat(); cell = size / 8f
        for (c in 0 until 64) {
            val (cx, cy) = center(c)
            val x = cx - cell / 2; val y = cy - cell / 2
            val isDark = (c / 8 + c % 8) % 2 == 1
            paint.style = Paint.Style.FILL
            paint.color = if (isDark) dark else light
            canvas.drawRect(x, y, x + cell, y + cell, paint)
            val lm = lastMove
            if (isDark && lm != null && (c == lm.from || c == lm.to)) {
                paint.color = 0x66F5E663; canvas.drawRect(x, y, x + cell, y + cell, paint)
            }
            if (c == selected) { paint.color = 0x8892D4FF.toInt(); canvas.drawRect(x, y, x + cell, y + cell, paint) }
            else if (c in movable && selected < 0) { paint.color = 0x22FFFFFF; canvas.drawRect(x, y, x + cell, y + cell, paint) }
        }
        // coordinates: files along the bottom edge, ranks along the left edge
        text.textSize = cell * 0.17f
        for (i in 0 until 8) {
            val fileRow = if (flipped) 0 else 7
            val fileCol = if (flipped) 7 - i else i
            text.color = if ((fileRow + fileCol) % 2 == 1) light else dark
            canvas.drawText(('a' + fileCol).toString(), (i + 1) * cell - cell * 0.17f, size - cell * 0.05f, text)
            val rankRow = if (flipped) 7 - i else i
            val rankCol = if (flipped) 7 else 0
            text.color = if ((rankRow + rankCol) % 2 == 1) light else dark
            canvas.drawText(('8' - rankRow).toString(), cell * 0.05f, i * cell + cell * 0.2f, text)
        }
        // hint
        hint?.let { h ->
            paint.style = Paint.Style.STROKE; paint.strokeWidth = cell * 0.07f; paint.color = 0xFF3AA0FF.toInt()
            for (c in listOf(h.from) + h.path.toList()) {
                val (cx, cy) = center(c); canvas.drawRect(cx - cell / 2 + 3, cy - cell / 2 + 3, cx + cell / 2 - 3, cy + cell / 2 - 3, paint)
            }
        }
        // pieces
        val base = animBefore ?: position
        val am = animMove
        for (c in 0 until 64) {
            val p = base.cells[c]
            if (p == Piece.EMPTY) continue
            if (am != null && c == am.from) continue
            drawPiece(canvas, p, center(c))
        }
        if (am != null) {
            val p = base.cells[am.from]
            val pts = ArrayList<Pair<Float, Float>>().apply { add(center(am.from)); am.path.forEach { add(center(it)) } }
            val t = animFraction * (pts.size - 1)
            val i = t.toInt().coerceAtMost(pts.size - 2)
            val f = t - i
            val x = pts[i].first + (pts[i + 1].first - pts[i].first) * f
            val y = pts[i].second + (pts[i + 1].second - pts[i].second) * f
            // promoted mid-animation? show king once the final segment is reached
            val shown = if (p == Piece.WHITE_MAN && position.cells[am.to] == Piece.WHITE_KING && animFraction > 0.85f) Piece.WHITE_KING
            else if (p == Piece.BLACK_MAN && position.cells[am.to] == Piece.BLACK_KING && animFraction > 0.85f) Piece.BLACK_KING else p
            drawPiece(canvas, shown, Pair(x, y), lift = true)
        }
        // targets
        paint.style = Paint.Style.FILL
        for (t in targets) {
            val (cx, cy) = center(t)
            paint.color = 0x99222222.toInt()
            canvas.drawCircle(cx, cy, cell * 0.15f, paint)
        }
    }

    private fun drawPiece(canvas: Canvas, p: Byte, at: Pair<Float, Float>, lift: Boolean = false) {
        val (cx, cy) = at
        val white = Piece.isWhite(p)
        val r = cell * 0.39f
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x55000000
        canvas.drawCircle(cx + cell * 0.02f, cy + cell * (if (lift) 0.07f else 0.04f), r, paint)
        val c1 = if (white) 0xFFFFFFF6.toInt() else 0xFF5A5A5A.toInt()
        val c2 = if (white) 0xFFD9D3BE.toInt() else 0xFF161616.toInt()
        paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = cell * 0.03f
        paint.color = if (white) 0xFFB9B29A.toInt() else 0xFF000000.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = if (white) 0xFFC9C3AB.toInt() else 0xFF3A3A3A.toInt()
        canvas.drawCircle(cx, cy, r * 0.68f, paint)
        if (Piece.isKing(p)) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFE0B13A.toInt()
            canvas.drawCircle(cx, cy, r * 0.36f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = cell * 0.03f
            paint.color = 0xFF8A6A12.toInt()
            canvas.drawCircle(cx, cy, r * 0.36f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && animMove == null) {
            val col0 = (e.x / cell).toInt().coerceIn(0, 7)
            val row0 = (e.y / cell).toInt().coerceIn(0, 7)
            val col = if (flipped) 7 - col0 else col0
            val row = if (flipped) 7 - row0 else row0
            onCellTap?.invoke(row * 8 + col)
            performClick()
        }
        return e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_UP
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
