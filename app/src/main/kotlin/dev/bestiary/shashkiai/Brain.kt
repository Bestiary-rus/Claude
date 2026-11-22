package dev.bestiary.shashkiai

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.bestiary.shashkiai.engine.Arena
import dev.bestiary.shashkiai.engine.GameStatus
import dev.bestiary.shashkiai.engine.MaterialEvaluator
import dev.bestiary.shashkiai.engine.Move
import dev.bestiary.shashkiai.engine.Network
import dev.bestiary.shashkiai.engine.Position
import dev.bestiary.shashkiai.engine.SearchPlayer
import dev.bestiary.shashkiai.engine.TdConfig
import dev.bestiary.shashkiai.engine.TdLearner
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The AI's persistent "brain": one value network that keeps learning on the device —
 * from its own self-play (background training) and from every game against a human.
 */
object Brain {
    class Point(val games: Long, val score: Float)

    fun interface Listener { fun onBrainChanged() }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private lateinit var dir: File
    private var learner: TdLearner? = null

    @Volatile var totalGames = 0L; private set
    @Volatile var training = false; private set
    @Volatile var gamesThisSession = 0L; private set
    @Volatile var avgDelta = 0f; private set
    @Volatile var gamesPerMinute = 0f; private set
    @Volatile var benchmarking = false; private set
    val history = ArrayList<Point>()
    private var sessionStart = 0L

    /** 0.2 .. 1.0 — fraction of time spent computing; the rest is idle (saves battery/heat). */
    @Volatile var duty = 0.6f

    fun init(ctx: Context) {
        if (learner != null) return
        synchronized(lock) {
            if (learner != null) return
            dir = ctx.applicationContext.filesDir
            val netFile = File(dir, "brain.bin")
            val net = runCatching { if (netFile.exists()) netFile.inputStream().use { Network.read(it) } else null }
                .getOrNull() ?: Network.create(seed = System.nanoTime(), materialPrior = true)
            val l = TdLearner(net, TdConfig(alpha = 0.005f, depth = 3, epsilon = 0.08f))
            runCatching {
                File(dir, "brain_stats.txt").readLines().forEach { line ->
                    when {
                        line.startsWith("games=") -> totalGames = line.substringAfter('=').toLong()
                        line.startsWith("hist=") -> line.substringAfter('=').split(',').let {
                            history.add(Point(it[0].toLong(), it[1].toFloat()))
                        }
                    }
                }
            }
            l.gamesLearned = totalGames
            learner = l
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun notifyChanged() { main.post { listeners.forEach { it.onBrainChanged() } } }

    /** An independent copy of the current network, safe to search with on another thread. */
    fun snapshot(): Network = synchronized(lock) { learner!!.net.copy() }

    val lastScore: Float? get() = synchronized(history) { history.lastOrNull()?.score }

    // ---- learning from games against a human --------------------------------------------

    fun learnFromHumanGame(start: Position, moves: List<Move>, status: GameStatus) {
        Thread({
            synchronized(lock) {
                learner!!.learnFromGame(start, moves, status)
                totalGames = learner!!.gamesLearned
                save()
            }
            notifyChanged()
        }, "brain-learn-human").start()
    }

    // ---- self-play training ---------------------------------------------------------------

    fun startTraining() {
        if (training) return
        training = true
        gamesThisSession = 0
        sessionStart = System.currentTimeMillis()
        val l = learner!!
        l.stopRequested = false
        Thread({
            var sinceBench = 0
            var deltaSum = 0f
            var deltaN = 0
            while (training) {
                val t0 = System.nanoTime()
                val summary = synchronized(lock) { l.selfPlayGame() }
                if (!training) break
                totalGames = l.gamesLearned
                gamesThisSession++
                deltaSum += summary.avgAbsDelta; deltaN++
                sinceBench++
                if (deltaN >= 20) { avgDelta = deltaSum / deltaN; deltaSum = 0f; deltaN = 0 }
                val minutes = (System.currentTimeMillis() - sessionStart) / 60000f
                if (minutes > 0.05f) gamesPerMinute = gamesThisSession / minutes
                if (sinceBench >= 25) synchronized(lock) { save() }
                if (sinceBench >= BENCH_EVERY) { sinceBench = 0; runBenchmark() }
                notifyChanged()
                val busyNs = System.nanoTime() - t0
                val d = duty.coerceIn(0.1f, 1f)
                if (d < 0.99f) {
                    val sleepMs = (busyNs / 1_000_000L * (1f / d - 1f)).toLong().coerceAtMost(2000)
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { }
                }
            }
            synchronized(lock) { save() }
            notifyChanged()
        }, "brain-train").start()
        notifyChanged()
    }

    fun stopTraining() {
        if (!training) return
        training = false
        learner?.stopRequested = true
        notifyChanged()
    }

    // ---- strength measurement -------------------------------------------------------------

    /**
     * Plays the current network (search depth 3) against plain material counting at the same
     * depth. 50% means "no better than counting pieces"; higher means it learned real strategy.
     */
    fun runBenchmark() {
        if (benchmarking) return
        benchmarking = true
        notifyChanged()
        try {
            val snap = snapshot()
            val r = Arena.match(SearchPlayer(snap, 3), SearchPlayer(MaterialEvaluator, 3, 3), BENCH_GAMES, seed = 77)
            synchronized(history) { history.add(Point(totalGames, r.score.toFloat())) }
            synchronized(lock) { save() }
        } finally {
            benchmarking = false
            notifyChanged()
        }
    }

    fun benchmarkAsync() { Thread({ runBenchmark() }, "brain-bench").start() }

    fun reset() {
        stopTraining()
        synchronized(lock) {
            val l = TdLearner(Network.create(seed = System.nanoTime(), materialPrior = true), TdConfig(alpha = 0.005f, depth = 3))
            learner = l
            totalGames = 0
            synchronized(history) { history.clear() }
            save()
        }
        notifyChanged()
    }

    private fun save() {
        val l = learner ?: return
        runCatching {
            val tmp = File(dir, "brain.bin.tmp")
            tmp.outputStream().use { l.net.write(it) }
            tmp.renameTo(File(dir, "brain.bin"))
            val sb = StringBuilder("games=$totalGames\n")
            synchronized(history) { history.forEach { sb.append("hist=${it.games},${it.score}\n") } }
            File(dir, "brain_stats.txt").writeText(sb.toString())
        }
    }

    private const val BENCH_EVERY = 500
    private const val BENCH_GAMES = 24
}
