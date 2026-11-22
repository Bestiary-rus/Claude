package dev.bestiary.shashkiai.engine

/**
 * Russian draughts (шашки) 8×8.
 *
 * Cell index = row * 8 + col, row 0 is rank 8 (Black's back rank), row 7 is rank 1.
 * Dark squares are those with (row + col) odd; a1 is cell 56. White moves towards row 0.
 */
object Piece {
    const val EMPTY: Byte = 0
    const val WHITE_MAN: Byte = 1
    const val WHITE_KING: Byte = 2
    const val BLACK_MAN: Byte = 3
    const val BLACK_KING: Byte = 4

    fun isWhite(p: Byte) = p == WHITE_MAN || p == WHITE_KING
    fun isBlack(p: Byte) = p == BLACK_MAN || p == BLACK_KING
    fun isKing(p: Byte) = p == WHITE_KING || p == BLACK_KING
    fun isMine(p: Byte, white: Boolean) = if (white) isWhite(p) else isBlack(p)
}

/** A complete move: a quiet step or a full capture sequence with all landing squares. */
class Move(val from: Int, val path: IntArray, val captured: IntArray) {
    val to: Int get() = path[path.size - 1]
    val isCapture: Boolean get() = captured.isNotEmpty()

    fun sameAs(o: Move) = from == o.from && path.contentEquals(o.path)

    override fun equals(other: Any?) = other is Move && sameAs(other)
    override fun hashCode() = from * 31 + path.contentHashCode()

    /** a3-b4 / c3xe5xg7 */
    fun notation(): String {
        val sb = StringBuilder(Board.squareName(from))
        for (p in path) sb.append(if (isCapture) 'x' else '-').append(Board.squareName(p))
        return sb.toString()
    }

    override fun toString() = notation()
}

object Board {
    /** Dark squares in ascending cell order → 0..31. -1 for light squares. */
    val darkIndex = IntArray(64) { -1 }.also {
        var n = 0
        for (c in 0 until 64) if ((c / 8 + c % 8) % 2 == 1) it[c] = n++
    }
    val darkCells = IntArray(32).also { for (c in 0 until 64) if (darkIndex[c] >= 0) it[darkIndex[c]] = c }

    fun isDark(cell: Int) = (cell / 8 + cell % 8) % 2 == 1
    fun squareName(cell: Int): String = "" + ('a' + cell % 8) + ('8' - cell / 8)
    fun parseSquare(s: String): Int {
        require(s.length == 2)
        val col = s[0] - 'a'
        val row = '8' - s[1]
        require(col in 0..7 && row in 0..7)
        return row * 8 + col
    }

    fun initialCells(): ByteArray {
        val b = ByteArray(64)
        for (c in 0 until 64) {
            if (!isDark(c)) continue
            when (c / 8) {
                0, 1, 2 -> b[c] = Piece.BLACK_MAN
                5, 6, 7 -> b[c] = Piece.WHITE_MAN
            }
        }
        return b
    }
}

/** Immutable game position. Moves are applied copy-on-write. */
class Position(
    val cells: ByteArray,
    val whiteToMove: Boolean,
    /** Consecutive plies made by kings without captures (Russian 15-move draw rule counts 30 plies). */
    val quietKingPlies: Int = 0,
) {
    private var cachedMoves: List<Move>? = null
    private var cachedKey = 0L
    private var keyDone = false

    fun legalMoves(): List<Move> = cachedMoves ?: MoveGen.generate(this).also { cachedMoves = it }

    fun apply(m: Move): Position {
        val b = cells.copyOf()
        val piece = b[m.from]
        b[m.from] = Piece.EMPTY
        for (v in m.captured) b[v] = Piece.EMPTY
        var out = piece
        if (piece == Piece.WHITE_MAN) {
            if (m.from / 8 == 0 || m.path.any { it / 8 == 0 }) out = Piece.WHITE_KING
        } else if (piece == Piece.BLACK_MAN) {
            if (m.path.any { it / 8 == 7 }) out = Piece.BLACK_KING
        }
        b[m.to] = out
        val quiet = if (Piece.isKing(piece) && !m.isCapture) quietKingPlies + 1 else 0
        return Position(b, !whiteToMove, quiet)
    }

    /** Zobrist-style hash of placement + side to move (for repetition detection). */
    fun key(): Long {
        if (keyDone) return cachedKey
        var h = if (whiteToMove) 0x9E3779B97F4A7C15uL.toLong() else 0L
        for (c in 0 until 64) {
            val p = cells[c].toInt()
            if (p != 0) h = h xor Zobrist.table[c * 5 + p]
        }
        cachedKey = h
        keyDone = true
        return h
    }

    fun count(white: Boolean, kings: Boolean): Int {
        var n = 0
        for (p in cells) if (Piece.isMine(p, white) && Piece.isKing(p) == kings) n++
        return n
    }

    /** Material balance from White's point of view in man-equivalents (king = 3). */
    fun materialBalance(): Int {
        var s = 0
        for (p in cells) when (p) {
            Piece.WHITE_MAN -> s += 1
            Piece.WHITE_KING -> s += 3
            Piece.BLACK_MAN -> s -= 1
            Piece.BLACK_KING -> s -= 3
        }
        return s
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            sb.append('8' - r).append(' ')
            for (c in 0 until 8) sb.append(
                when (cells[r * 8 + c]) {
                    Piece.WHITE_MAN -> 'w'; Piece.WHITE_KING -> 'W'
                    Piece.BLACK_MAN -> 'b'; Piece.BLACK_KING -> 'B'
                    else -> if (Board.isDark(r * 8 + c)) '.' else ' '
                }
            )
            sb.append('\n')
        }
        sb.append("  abcdefgh  ").append(if (whiteToMove) "white" else "black").append(" to move")
        return sb.toString()
    }

    companion object {
        fun initial() = Position(Board.initialCells(), true)

        /** Parse 8 rows of 8 chars (w/W/b/B/.) top row = rank 8. Spaces and light squares ignored. */
        fun parse(rows: List<String>, whiteToMove: Boolean): Position {
            val b = ByteArray(64)
            for ((r, line) in rows.withIndex()) {
                val s = line.filter { !it.isWhitespace() }
                require(s.length == 8) { "row $r must have 8 cells: '$line'" }
                for (c in 0 until 8) b[r * 8 + c] = when (s[c]) {
                    'w' -> Piece.WHITE_MAN; 'W' -> Piece.WHITE_KING
                    'b' -> Piece.BLACK_MAN; 'B' -> Piece.BLACK_KING
                    else -> Piece.EMPTY
                }
            }
            return Position(b, whiteToMove)
        }
    }
}

internal object Zobrist {
    val table = LongArray(64 * 5).also {
        var x = 0x2545F4914F6CDD1DL
        for (i in it.indices) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            it[i] = x * 0x2545F4914F6CDD1DL
        }
    }
}

object MoveGen {
    class TooComplex : RuntimeException()

    private val DR = intArrayOf(-1, -1, 1, 1)
    private val DC = intArrayOf(-1, 1, -1, 1)
    private const val MAX_MOVES = 4096

    fun generate(p: Position): List<Move> {
        val white = p.whiteToMove
        val cells = p.cells
        val out = ArrayList<Move>(16)
        val steps = IntArray(24)
        val victims = IntArray(24)
        for (from in 0 until 64) {
            val piece = cells[from]
            if (!Piece.isMine(piece, white)) continue
            val blocked = cells.copyOf()
            blocked[from] = Piece.EMPTY
            capture(blocked, from, from, piece, white, 0L, steps, victims, 0, out)
        }
        if (out.isNotEmpty()) return out
        for (from in 0 until 64) {
            val piece = cells[from]
            if (!Piece.isMine(piece, white)) continue
            val king = Piece.isKing(piece)
            for (d in 0 until 4) {
                if (!king && DR[d] != (if (white) -1 else 1)) continue
                var r = from / 8 + DR[d]
                var c = from % 8 + DC[d]
                while (r in 0..7 && c in 0..7) {
                    val to = r * 8 + c
                    if (cells[to] != Piece.EMPTY) break
                    out.add(Move(from, intArrayOf(to), EMPTY_INTS))
                    if (!king) break
                    r += DR[d]; c += DC[d]
                }
            }
        }
        return out
    }

    private val EMPTY_INTS = IntArray(0)

    private fun crown(piece: Byte, at: Int): Byte = when {
        piece == Piece.WHITE_MAN && at / 8 == 0 -> Piece.WHITE_KING
        piece == Piece.BLACK_MAN && at / 8 == 7 -> Piece.BLACK_KING
        else -> piece
    }

    /**
     * Depth-first capture search. Captured pieces stay on the board until the move ends
     * (Turkish-strike rule) and may not be jumped twice. A man reaching the far rank
     * becomes a king and keeps capturing as a king. A flying king must pick a landing
     * square that allows continuing the capture whenever one exists.
     * Returns whether at least one capture was found from [at].
     */
    private fun capture(
        blocked: ByteArray, from: Int, at: Int, piece: Byte, white: Boolean, used: Long,
        steps: IntArray, victims: IntArray, size: Int, out: MutableList<Move>,
    ): Boolean {
        var extended = false
        val king = Piece.isKing(piece)
        for (d in 0 until 4) {
            val dr = DR[d]
            val dc = DC[d]
            var r = at / 8 + dr
            var c = at % 8 + dc
            if (king) while (r in 0..7 && c in 0..7 && blocked[r * 8 + c] == Piece.EMPTY) { r += dr; c += dc }
            if (r !in 0..7 || c !in 0..7) continue
            val enemy = r * 8 + c
            if (!Piece.isMine(blocked[enemy], !white) || (used and (1L shl enemy)) != 0L) continue
            var rayContinues = false
            var terminal: ArrayList<Move>? = null
            r += dr; c += dc
            while (r in 0..7 && c in 0..7) {
                val to = r * 8 + c
                if (blocked[to] != Piece.EMPTY) break
                if (size >= 23) throw TooComplex()
                extended = true
                steps[size] = to
                victims[size] = enemy
                val landing = crown(piece, to)
                if (capture(blocked, from, to, landing, white, used or (1L shl enemy), steps, victims, size + 1, out)) {
                    rayContinues = true
                } else {
                    if (terminal == null) terminal = ArrayList(4)
                    terminal.add(Move(from, steps.copyOf(size + 1), victims.copyOf(size + 1)))
                }
                if (!king) break
                r += dr; c += dc
            }
            if (!rayContinues && terminal != null) out.addAll(terminal)
            if (out.size >= MAX_MOVES) throw TooComplex()
        }
        return extended
    }
}

enum class GameStatus { ONGOING, WHITE_WINS, BLACK_WINS, DRAW }

/** Tracks repetition / draw rules on top of positions. */
class GameState(start: Position = Position.initial()) {
    var position: Position = start
        private set
    var plies = 0
        private set
    val history = ArrayList<Move>()
    private val seen = HashMap<Long, Int>()

    init { seen[start.key()] = 1 }

    fun play(m: Move) {
        position = position.apply(m)
        history.add(m)
        plies++
        seen.merge(position.key(), 1, Int::plus)
    }

    fun status(): GameStatus {
        if (position.legalMoves().isEmpty()) return if (position.whiteToMove) GameStatus.BLACK_WINS else GameStatus.WHITE_WINS
        if (position.quietKingPlies >= QUIET_KING_PLIES_LIMIT) return GameStatus.DRAW
        if ((seen[position.key()] ?: 0) >= 3) return GameStatus.DRAW
        return GameStatus.ONGOING
    }

    companion object {
        /** 15 moves by each side with only king moves and no captures. */
        const val QUIET_KING_PLIES_LIMIT = 30
    }
}
