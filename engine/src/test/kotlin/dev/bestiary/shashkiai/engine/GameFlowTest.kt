package dev.bestiary.shashkiai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameFlowTest {
    @Test fun inputBuildsQuietMove() {
        val p = Position.initial()
        val inp = MoveInput(p.legalMoves())
        assertNull(inp.tap(Board.parseSquare("c3")))
        assertEquals(setOf(Board.parseSquare("b4"), Board.parseSquare("d4")), inp.targets())
        val m = inp.tap(Board.parseSquare("d4"))
        assertNotNull(m)
        assertEquals("c3-d4", m.notation())
    }

    @Test fun inputBuildsMultiJump() {
        val p = Position.parse(listOf(
            "........", "..b.....", "........", "..b.....", "........", "..b.....", "........", "W......."), true)
        val inp = MoveInput(p.legalMoves())
        inp.tap(Board.parseSquare("a1"))
        var done: Move? = null
        var guard = 0
        while (done == null && guard++ < 6) {
            val t = inp.targets().first()
            done = inp.tap(t)
        }
        assertNotNull(done)
        assertTrue(done.captured.size >= 2)
    }

    @Test fun undoRestoresHumanTurn() {
        val g = HumanGame(true, Level.BEGINNER)
        g.play(g.state.position.legalMoves()[0])
        g.play(g.state.position.legalMoves()[0])
        g.play(g.state.position.legalMoves()[0])
        g.play(g.state.position.legalMoves()[0])
        assertTrue(g.undo())
        assertTrue(g.humanToMove)
        assertEquals(2, g.moves.size)
    }

    @Test fun networkSerializationRoundTrip() {
        val n = Network.create(3)
        val out = java.io.ByteArrayOutputStream()
        n.write(out)
        val m = Network.read(out.toByteArray().inputStream())
        val p = Position.initial()
        assertEquals(n.evaluate(p), m.evaluate(p))
    }

    @Test fun gradientMatchesNumeric() {
        val n = Network.create(9, materialPrior = false)
        val pos = Position.initial().let { it.apply(it.legalMoves()[2]) }
        val g = FloatArray(Network.SIZE)
        n.evaluateWithGradient(pos, 1f, g)
        val rnd = java.util.Random(1)
        var checked = 0
        repeat(400) {
            val i = rnd.nextInt(Network.SIZE)
            if (Math.abs(g[i]) < 1e-4f) return@repeat
            val old = n.p[i]
            val h = 1e-3f
            n.p[i] = old + h; val a = n.evaluate(pos)
            n.p[i] = old - h; val b = n.evaluate(pos)
            n.p[i] = old
            val num = (a - b) / (2 * h)
            assertTrue(Math.abs(num - g[i]) < 2e-2f * maxOf(1f, Math.abs(num)), "param $i analytic ${g[i]} numeric $num")
            checked++
        }
        assertTrue(checked > 20, "checked=$checked")
    }

    @Test fun searchFindsForcedWinOfMaterial() {
        // white to move can capture a man for free
        val p = Position.parse(listOf(
            "........", "........", "........", "........", "..b.....", "...w....", "........", "........"), true)
        val r = Searcher(MaterialEvaluator).search(p, 3)
        assertTrue(r.move!!.isCapture)
    }

    @Test fun selfPlayGamesTerminate() {
        val l = TdLearner(Network.create(1), TdConfig(depth = 2), seed = 1)
        repeat(5) { l.selfPlayGame() }
        assertEquals(5, l.gamesLearned.toInt())
        assertTrue(l.net.p.all { it.isFinite() })
    }
}

class LearningTest {
    /** A network that knows nothing must, after a short self-play session, clearly beat a random mover. */
    @Test fun learnsFromScratch() {
        val net = Network.create(seed = 4, materialPrior = false)
        val learner = TdLearner(net, TdConfig(alpha = 0.01f, depth = 2), seed = 2)
        val before = Arena.match(SearchPlayer(net.copy(), 3), RandomPlayer(), 20, seed = 1).score
        repeat(600) { learner.selfPlayGame() }
        val after = Arena.match(SearchPlayer(net.copy(), 3), RandomPlayer(), 20, seed = 1).score
        println("vs random: before=$before after=$after")
        assertTrue(after >= 0.85, "after=$after before=$before")
        assertTrue(after > before)
    }
}
