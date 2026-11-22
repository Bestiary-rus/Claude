package dev.bestiary.shashkiai.engine

import java.util.Random

class SearchResult(
    val move: Move?,
    /** Backed-up value from the root mover's point of view, in (-1, 1). */
    val value: Float,
    /** Principal variation (root move first). */
    val pv: List<Move>,
    val depth: Int,
    val nodes: Long,
)

/**
 * Iterative-deepening negamax alpha-beta with forced-capture quiescence.
 * Captures are mandatory in draughts, so "quiet" leaves are simply nodes without captures.
 */
class Searcher(private val evaluator: Evaluator, private val rnd: Random = Random()) {
    private var nodes = 0L
    private var deadline = Long.MAX_VALUE
    private var aborted = false
    private val maxPly = 64
    private val pvTable = Array(maxPly + 1) { arrayOfNulls<Move>(maxPly + 1) }
    private val pvLen = IntArray(maxPly + 1)
    private val history = Array(2) { IntArray(64 * 64) }

    /** Root moves are shuffled first, so equal-valued moves are chosen at random. */
    fun search(root: Position, maxDepth: Int, timeLimitMs: Long = Long.MAX_VALUE): SearchResult {
        val rootMoves = root.legalMoves()
        if (rootMoves.isEmpty()) return SearchResult(null, -1f, emptyList(), 0, 0)
        nodes = 0
        aborted = false
        deadline = if (timeLimitMs == Long.MAX_VALUE) Long.MAX_VALUE else System.nanoTime() + timeLimitMs * 1_000_000
        for (h in history) h.fill(0)

        var bestMove = rootMoves[0]
        var bestValue = -2f
        var bestPv: List<Move> = listOf(bestMove)
        var done = 0
        if (rootMoves.size == 1) {
            val v = value(root, rootMoves[0], 1)
            return SearchResult(rootMoves[0], v, listOf(rootMoves[0]), 1, nodes)
        }
        var ordered = rootMoves.toMutableList().also { it.shuffle(rnd) }

        for (depth in 1..maxDepth) {
            var alpha = -2f
            val beta = 2f
            var iterBest: Move? = null
            var iterValue = -2f
            var iterPv: List<Move> = emptyList()
            for (m in ordered) {
                val child = root.apply(m)
                val score = -negamax(child, depth - 1, -beta, -alpha, 1, 0)
                if (aborted) break
                if (score > iterValue) {
                    iterValue = score
                    iterBest = m
                    iterPv = ArrayList<Move>().also {
                        it.add(m)
                        for (i in 0 until pvLen[1]) it.add(pvTable[1][i]!!)
                    }
                    if (score > alpha) alpha = score
                }
            }
            if (aborted) {
                if (done == 0 && iterBest != null) { bestMove = iterBest; bestValue = iterValue; bestPv = iterPv }
                break
            }
            bestMove = iterBest!!
            bestValue = iterValue
            bestPv = iterPv
            done = depth
            // PV move first next iteration
            ordered.remove(bestMove)
            ordered.add(0, bestMove)
            if (bestValue > 0.995f || bestValue < -0.995f) break
        }
        return SearchResult(bestMove, bestValue.coerceIn(-1f, 1f), bestPv, done, nodes)
    }

    private fun value(root: Position, m: Move, depth: Int): Float {
        val child = root.apply(m)
        return -negamax(child, depth - 1, -2f, 2f, 1, 0)
    }

    private fun negamax(pos: Position, depth: Int, alphaIn: Float, beta: Float, ply: Int, qply: Int): Float {
        pvLen[ply] = 0
        if ((++nodes and 1023L) == 0L && System.nanoTime() > deadline) aborted = true
        if (aborted) return 0f
        val moves = pos.legalMoves()
        if (moves.isEmpty()) return -1f + ply * 0.001f
        if (pos.quietKingPlies >= GameState.QUIET_KING_PLIES_LIMIT) return 0f
        if (ply >= maxPly - 1) return evaluator.evaluate(pos)
        val forced = moves[0].isCapture
        if (depth <= 0 && (!forced || qply >= 10)) return evaluator.evaluate(pos)
        val nextDepth = if (depth <= 0) 0 else depth - 1
        val nextQ = if (depth <= 0) qply + 1 else qply

        var alpha = alphaIn
        var best = -2f
        val ordered = if (moves.size > 1) order(moves, pos.whiteToMove) else moves
        for (m in ordered) {
            val s = -negamax(pos.apply(m), nextDepth, -beta, -alpha, ply + 1, nextQ)
            if (aborted) return 0f
            if (s > best) {
                best = s
                if (s > alpha) {
                    alpha = s
                    pvTable[ply][0] = m
                    val child = pvLen[ply + 1]
                    for (i in 0 until child) pvTable[ply][i + 1] = pvTable[ply + 1][i]
                    pvLen[ply] = child + 1
                }
            }
            if (alpha >= beta) {
                if (!m.isCapture) history[if (pos.whiteToMove) 0 else 1][m.from * 64 + m.to] += depth * depth + 1
                break
            }
        }
        return best
    }

    private fun order(moves: List<Move>, white: Boolean): List<Move> {
        if (moves[0].isCapture) return moves.sortedByDescending { it.captured.size }
        val h = history[if (white) 0 else 1]
        return moves.sortedByDescending { h[it.from * 64 + it.to] }
    }
}
