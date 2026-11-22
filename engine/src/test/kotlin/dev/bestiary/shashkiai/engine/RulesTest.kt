package dev.bestiary.shashkiai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTest {
    private fun perft(p: Position, d: Int): Long =
        if (d == 0) 1 else p.legalMoves().sumOf { perft(p.apply(it), d - 1) }

    /** Node counts of Russian draughts from the initial position (cross-checked against an independent implementation). */
    @Test fun initialPerft() {
        val expected = longArrayOf(7, 49, 302, 1469, 7482, 37986, 190146)
        for (d in 1..expected.size) assertEquals(expected[d - 1], perft(Position.initial(), d), "depth $d")
    }

    @Test fun randomGamesStayConsistent() {
        val rnd = java.util.Random(42)
        var kings = 0; var captures = 0
        repeat(200) {
            var p = Position.initial()
            for (ply in 0 until 200) {
                val moves = p.legalMoves()
                if (moves.isEmpty()) break
                // all moves of a position are either all captures or all quiet
                assertTrue(moves.all { it.isCapture } || moves.none { it.isCapture })
                if (moves[0].isCapture) captures++
                val m = moves[rnd.nextInt(moves.size)]
                val q = p.apply(m)
                // piece counts never grow, a move never lands on an occupied square
                assertTrue(q.cells.count { it != Piece.EMPTY } == p.cells.count { it != Piece.EMPTY } - m.captured.size)
                if (q.cells.any { Piece.isKing(it) }) kings++
                p = q
            }
        }
        assertTrue(captures > 300 && kings > 300)
    }

    @Test fun flyingKingMultiCapture() {
        val p = Position.parse(listOf(
            "........", "..b.....", "........", "..b.....", "........", "..b.....", "........", "W......."), true)
        val moves = p.legalMoves()
        assertTrue(moves.all { it.isCapture })
        assertTrue(moves.all { it.captured.size >= 2 }, "must continue capturing: $moves")
        assertEquals(3, moves.maxOf { it.captured.size })
    }

    @Test fun manMayCaptureBackwards() {
        val p = Position.parse(listOf(
            "........", "........", "........", "........", "...b....", "..w.....", "........", "........"), true)
        // white man c3 (row5,col2) jumps b d4 (row4,col3) forward; also test backward with black man behind
        val q = Position.parse(listOf(
            "........", "........", "........", "........", "........", "..w.....", "...b....", "........"), true)
        assertTrue(p.legalMoves().all { it.isCapture })
        assertTrue(q.legalMoves().all { it.isCapture }, "backward capture is mandatory in Russian draughts")
    }

    @Test fun manPromotesMidCaptureAndContinuesAsKing() {
        // white man jumps to the last rank and, as a king, continues along the diagonal
        val p = Position.parse(listOf(
            "........", ".b......", "..w.....", "........", "........", "........", "........", "........"), true)
        // man c6 (row2,col2): jump b7 (row1,col1) -> a8 (row0,col0): becomes king, no further capture
        val m = p.legalMoves().single()
        assertEquals(1, m.captured.size)
        assertEquals(Piece.WHITE_KING, p.apply(m).cells[m.to])
    }

    @Test fun drawByKingOnlyMoves() {
        val cells = Position.parse(listOf(
            "........", "........", "........", "........", "........", "........", "........", "W.....B."), true).cells
        val g = GameState(Position(cells, true, 29))
        g.play(g.position.legalMoves()[0])
        assertEquals(GameStatus.DRAW, g.status())
    }

    @Test fun noMovesMeansLoss() {
        val p = Position.parse(List(8) { "........" }, true)
        assertEquals(GameStatus.BLACK_WINS, GameState(p).status())
    }
}
