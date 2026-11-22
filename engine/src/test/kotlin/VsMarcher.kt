// Manual benchmark: trained network vs the real Marcher-RU engine (run through Node).
// Usage: VsMarcherKt <net.bin> <assetsDir> <host.mjs> <marcherDepth> <marcherMs> <myMs> <games> [<seed>]
import dev.bestiary.shashkiai.engine.*
import java.io.File

fun Position.fen(): String {
    val w = ArrayList<String>(); val b = ArrayList<String>()
    for (c in 0 until 64) {
        val p = cells[c]
        if (p == Piece.EMPTY) continue
        val name = (if (Piece.isKing(p)) "K" else "") + Board.squareName(c)
        if (Piece.isWhite(p)) w.add(name) else b.add(name)
    }
    return (if (whiteToMove) "W" else "B") + ":W" + w.joinToString(",") + ":B" + b.joinToString(",")
}

class MarcherPlayer(assets: String, host: String, private val depth: Int, private val ms: Int) : Player, AutoCloseable {
    private val proc = ProcessBuilder("node", "--experimental-vm-modules", host, assets).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val out = proc.outputStream.bufferedWriter()
    private val inp = proc.inputStream.bufferedReader()
    private var id = 0
    var totalDepth = 0L; var calls = 0L
    init { check(inp.readLine() == "READY") }
    override fun choose(pos: Position): Move {
        val moves = pos.legalMoves()
        if (moves.size == 1) return moves[0]
        out.write("""{"id":${++id},"fen":"${pos.fen()}","depth":$depth,"budget":$ms}"""); out.newLine(); out.flush()
        val line = inp.readLine()
        val mv = Regex("\"move\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: error("marcher: $line for ${pos.fen()}")
        Regex("\"depth\":(\\d+)").find(line)?.let { totalDepth += it.groupValues[1].toLong(); calls++ }
        return moves.firstOrNull { it.notation() == mv } ?: error("unmatched move $mv in ${moves.map { it.notation() }}")
    }
    override fun close() { proc.destroy() }
}

class TimedPlayer(private val net: Network, private val ms: Long) : Player {
    private val s = Searcher(net, java.util.Random(5))
    var totalDepth = 0L; var calls = 0L
    override fun choose(pos: Position): Move {
        val r = s.search(pos, 40, ms)
        totalDepth += r.depth; calls++
        return r.move!!
    }
}

fun main(a: Array<String>) {
    val net = File(a[0]).inputStream().use { Network.read(it) }
    val mine = TimedPlayer(net, a[5].toLong())
    MarcherPlayer(a[1], a[2], a[3].toInt(), a[4].toInt()).use { m ->
        val t0 = System.currentTimeMillis()
        val r = Arena.match(mine, m, a[6].toInt(), openingPlies = 4, seed = a.getOrElse(7) { "11" }.toLong(), maxPlies = 200)
        println("net=${a[0]} marcher(depth<=${a[3]}, ${a[4]}ms) mine(${a[5]}ms): $r  | my avg depth ${"%.1f".format(mine.totalDepth.toDouble() / mine.calls)}, marcher avg depth ${"%.1f".format(m.totalDepth.toDouble() / maxOf(m.calls, 1))}  [${(System.currentTimeMillis() - t0) / 1000}s]")
    }
}
