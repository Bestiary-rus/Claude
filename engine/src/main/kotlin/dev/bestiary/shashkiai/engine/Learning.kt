package dev.bestiary.shashkiai.engine

import java.util.Random
import kotlin.math.abs

/**
 * Online temporal-difference learning (TD-leaf(λ)) — one network, updated move by move,
 * no population, no generations.
 *
 *  - Each move is chosen by a shallow alpha-beta search over the network's own evaluation.
 *  - The principal-variation leaf of that search is the "state" whose value is learned.
 *  - After every move the weights are nudged so the previous position's value moves toward the
 *    new one (eligibility traces spread credit backwards); at game end the real result
 *    (+1 / 0 / −1) is the final target.
 */
class TdConfig(
    val alpha: Float = 0.01f,
    val lambda: Float = 0.7f,
    val depth: Int = 3,
    /** Probability of an exploratory random move (traces are cut after it, Watkins-style). */
    val epsilon: Float = 0.08f,
    val randomOpeningPlies: Int = 2,
    val maxPlies: Int = 160,
    /** Learning rate is divided by (1 + gamesLearned / decayGames), floored at [alphaMinFactor]·alpha. */
    val decayGames: Int = 4000,
    val alphaMinFactor: Float = 0.2f,
)

class GameSummary(
    /** Result from White's point of view: +1 win, 0 draw, −1 loss (adjudicated games are fractional). */
    val outcome: Float,
    val status: GameStatus,
    val plies: Int,
    val avgAbsDelta: Float,
)

class TdLearner(val net: Network, var config: TdConfig = TdConfig(), seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)
    private val searcher = Searcher(net, rnd)
    private val trace = FloatArray(Network.SIZE)
    private val grad = FloatArray(Network.SIZE)

    var gamesLearned = 0L

    @Volatile var stopRequested = false

    private fun alpha(): Float {
        val f = 1f / (1f + gamesLearned.toFloat() / config.decayGames)
        return config.alpha * maxOf(f, config.alphaMinFactor)
    }

    private class Step(var prevV: Float = Float.NaN)

    /** Plays one self-play game, learning after every move. */
    fun selfPlayGame(): GameSummary {
        val game = GameState()
        val s = Step()
        trace.fill(0f)
        var absDelta = 0f
        var nDelta = 0
        while (!stopRequested) {
            val status = game.status()
            if (status != GameStatus.ONGOING) break
            if (game.plies >= config.maxPlies) break
            val pos = game.position
            val moves = pos.legalMoves()
            val random = game.plies < config.randomOpeningPlies || rnd.nextFloat() < config.epsilon
            if (random) {
                trace.fill(0f)
                s.prevV = Float.NaN
                game.play(moves[rnd.nextInt(moves.size)])
                continue
            }
            val res = searcher.search(pos, config.depth)
            val d = observe(pos, res, s)
            if (!d.isNaN()) { absDelta += abs(d); nDelta++ }
            game.play(res.move!!)
        }
        val status = game.status()
        val outcome = adjudicate(game, status)
        if (!stopRequested) {
            val d = finish(outcome, s)
            if (!d.isNaN()) { absDelta += abs(d); nDelta++ }
            gamesLearned++
        }
        return GameSummary(outcome, status, game.plies, if (nDelta > 0) absDelta / nDelta else 0f)
    }

    /**
     * Learns from a finished game played by someone else (e.g. the human against the app):
     * every position is searched, so the network sees real tactical consequences of both sides' moves.
     */
    fun learnFromGame(start: Position, moves: List<Move>, status: GameStatus) {
        val s = Step()
        trace.fill(0f)
        var pos = start
        for (m in moves) {
            if (pos.legalMoves().size > 1) {
                val res = searcher.search(pos, config.depth)
                observe(pos, res, s)
            }
            pos = pos.apply(m)
        }
        finish(
            when (status) {
                GameStatus.WHITE_WINS -> 1f
                GameStatus.BLACK_WINS -> -1f
                else -> 0f
            }, s,
        )
        gamesLearned++
    }

    private fun adjudicate(game: GameState, status: GameStatus): Float = when (status) {
        GameStatus.WHITE_WINS -> 1f
        GameStatus.BLACK_WINS -> -1f
        GameStatus.DRAW -> 0f
        GameStatus.ONGOING -> (game.position.materialBalance() * 0.12f).coerceIn(-0.6f, 0.6f)
    }

    /** One TD step: bootstrap from the search value of [pos]. Returns the TD error or NaN. */
    private fun observe(pos: Position, res: SearchResult, s: Step): Float {
        val rootSign = if (pos.whiteToMove) 1f else -1f
        val v = (res.value * rootSign).coerceIn(-1f, 1f)
        var leaf = pos
        for (m in res.pv) leaf = leaf.apply(m)
        grad.fill(0f)
        val evaluated = leaf.legalMoves().isNotEmpty() && leaf.quietKingPlies < GameState.QUIET_KING_PLIES_LIMIT
        if (evaluated) net.evaluateWithGradient(leaf, if (leaf.whiteToMove) 1f else -1f, grad)

        var delta = Float.NaN
        if (!s.prevV.isNaN()) {
            delta = v - s.prevV
            applyDelta(delta)
        }
        val lam = config.lambda
        for (i in trace.indices) trace[i] = lam * trace[i] + grad[i]
        s.prevV = v
        return delta
    }

    private fun finish(outcome: Float, s: Step): Float {
        if (s.prevV.isNaN()) return Float.NaN
        val delta = outcome - s.prevV
        applyDelta(delta)
        return delta
    }

    private fun applyDelta(delta: Float) {
        val a = alpha() * delta
        val w = net.p
        for (i in w.indices) w[i] += a * trace[i]
    }
}

/** Anything that can pick a move; used for benchmarking. */
interface Player {
    fun choose(pos: Position): Move
}

class RandomPlayer(seed: Long = 7) : Player {
    private val rnd = Random(seed)
    override fun choose(pos: Position): Move = pos.legalMoves().let { it[rnd.nextInt(it.size)] }
}

class SearchPlayer(evaluator: Evaluator, private val depth: Int, seed: Long = 11) : Player {
    private val searcher = Searcher(evaluator, Random(seed))
    override fun choose(pos: Position): Move = searcher.search(pos, depth).move!!
}

class MatchResult(val wins: Int, val draws: Int, val losses: Int) {
    val games get() = wins + draws + losses
    val score get() = if (games == 0) 0.0 else (wins + 0.5 * draws) / games
    override fun toString() = "+$wins =$draws -$losses (score ${"%.0f".format(score * 100)}%)"
}

object Arena {
    /** [games] games of A vs B, colours alternate, [openingPlies] random plies for variety. */
    fun match(a: Player, b: Player, games: Int, openingPlies: Int = 2, seed: Long = 123, maxPlies: Int = 200): MatchResult {
        val rnd = Random(seed)
        var w = 0; var d = 0; var l = 0
        for (g in 0 until games) {
            val aWhite = g % 2 == 0
            val game = GameState()
            // identical opening for each colour pair
            if (g % 2 == 0) openingSeed = rnd.nextLong()
            val ornd = Random(openingSeed)
            for (i in 0 until openingPlies) {
                val mv = game.position.legalMoves()
                if (mv.isEmpty()) break
                game.play(mv[ornd.nextInt(mv.size)])
            }
            while (game.status() == GameStatus.ONGOING && game.plies < maxPlies) {
                val pos = game.position
                val mover = if (pos.whiteToMove == aWhite) a else b
                game.play(mover.choose(pos))
            }
            val st = game.status()
            val aWon = (st == GameStatus.WHITE_WINS && aWhite) || (st == GameStatus.BLACK_WINS && !aWhite)
            val bWon = (st == GameStatus.WHITE_WINS && !aWhite) || (st == GameStatus.BLACK_WINS && aWhite)
            when {
                aWon -> w++
                bWon -> l++
                else -> d++
            }
        }
        return MatchResult(w, d, l)
    }

    private var openingSeed = 0L
}
