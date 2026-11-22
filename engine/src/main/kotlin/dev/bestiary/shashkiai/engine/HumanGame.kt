package dev.bestiary.shashkiai.engine

/**
 * Turns taps on squares into complete legal moves, including multi-jump captures
 * (the player taps the piece, then each landing square in turn).
 */
class MoveInput(private val legal: List<Move>) {
    var from = -1
        private set
    private var path = IntArray(0)

    val chosenPath: IntArray get() = path.copyOf()
    val hasPartialCapture: Boolean get() = path.isNotEmpty()

    /** Pieces that may be selected (when captures exist, only capturing pieces). */
    fun movableCells(): Set<Int> = legal.mapTo(HashSet()) { it.from }

    /** Squares the selected piece can go to next. */
    fun targets(): Set<Int> {
        if (from < 0) return emptySet()
        return candidates().mapTo(HashSet()) { it.path[path.size] }
    }

    private fun candidates(): List<Move> = legal.filter { m ->
        m.from == from && m.path.size > path.size && (0 until path.size).all { m.path[it] == path[it] }
    }

    /** Returns the completed move, or null if the input is still in progress / ignored. */
    fun tap(cell: Int): Move? {
        if (from >= 0 && cell in targets()) {
            path += cell
            val done = legal.firstOrNull { it.from == from && it.path.contentEquals(path) }
            if (done != null) { reset(); return done }
            return null
        }
        if (path.isEmpty()) from = if (cell in movableCells() && cell != from) cell else -1
        return null
    }

    fun reset() { from = -1; path = IntArray(0) }
}

/** Playing level: search depth and a thinking-time cap. */
enum class Level(val title: String, val depth: Int, val timeMs: Long) {
    BEGINNER("Новичок", 2, 300),
    AMATEUR("Любитель", 4, 1000),
    STRONG("Сильный", 6, 2500),
    MAX("Максимум", 10, 5000),
}

/** A game of a human against the engine; board logic only (no UI, no threads). */
class HumanGame(val humanWhite: Boolean, val level: Level) {
    var state = GameState()
        private set
    val startPosition: Position = state.position

    val humanToMove: Boolean get() = state.position.whiteToMove == humanWhite
    val status: GameStatus get() = state.status()
    val moves: List<Move> get() = state.history
    var resigned = false
        private set

    fun play(m: Move) {
        require(state.position.legalMoves().any { it.sameAs(m) })
        state.play(m)
    }

    fun resign() { resigned = true }

    /** Takes back the human's last move and the engine's reply (or just the human's move). */
    fun undo(): Boolean {
        val h = ArrayList(state.history)
        if (h.isEmpty()) return false
        var drop = 0
        // remove until it's the human's turn again with at least one human move removed
        var s = GameState(startPosition)
        val keep = ArrayList<Move>()
        var pos = startPosition
        val humanIdx = ArrayList<Int>()
        for ((i, m) in h.withIndex()) {
            if (pos.whiteToMove == humanWhite) humanIdx.add(i)
            pos = pos.apply(m)
        }
        if (humanIdx.isEmpty()) return false
        val cut = humanIdx.last()
        for (i in 0 until cut) keep.add(h[i])
        for (m in keep) s.play(m)
        state = s
        return true
    }

    fun result(): Float? = when {
        resigned -> if (humanWhite) -1f else 1f
        else -> when (status) {
            GameStatus.WHITE_WINS -> 1f
            GameStatus.BLACK_WINS -> -1f
            GameStatus.DRAW -> 0f
            GameStatus.ONGOING -> null
        }
    }

    val finished: Boolean get() = resigned || status != GameStatus.ONGOING

    fun finalStatus(): GameStatus = if (resigned) (if (humanWhite) GameStatus.BLACK_WINS else GameStatus.WHITE_WINS) else status
}
