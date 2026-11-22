package dev.bestiary.shashkiai.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import kotlin.math.tanh

/** Position evaluator: returns a value in (-1, 1) from the point of view of the side to move. */
interface Evaluator {
    fun evaluate(p: Position): Float
}

/** Plain material counting; used as a reference opponent and as an optional prior. */
object MaterialEvaluator : Evaluator {
    override fun evaluate(p: Position): Float {
        var s = 0
        for (c in p.cells) when (c) {
            Piece.WHITE_MAN -> s += 2
            Piece.WHITE_KING -> s += 5
            Piece.BLACK_MAN -> s -= 2
            Piece.BLACK_KING -> s -= 5
        }
        val v = tanh(s * 0.08f)
        return if (p.whiteToMove) v else -v
    }
}

/**
 * Small value network: 128 sparse binary inputs (own/opponent men and kings on the 32 dark
 * squares, always seen from the side to move) → [H1] leaky-ReLU → [H2] leaky-ReLU → tanh,
 * plus a direct linear "skip" from inputs to the output. All parameters live in one flat
 * array so eligibility traces, serialization and copies are trivial.
 *
 * Not thread-safe: it owns scratch buffers. Use [copy] to hand a network to another thread.
 */
class Network private constructor(val p: FloatArray) : Evaluator {
    companion object {
        const val N_IN = 128
        const val H1 = 48
        const val H2 = 24
        private const val LEAK = 0.01f

        private const val O_W1 = 0
        private const val O_B1 = O_W1 + N_IN * H1
        private const val O_W2 = O_B1 + H1
        private const val O_B2 = O_W2 + H1 * H2
        private const val O_W3 = O_B2 + H2
        private const val O_B3 = O_W3 + H2
        private const val O_SKIP = O_B3 + 1
        const val SIZE = O_SKIP + N_IN

        private const val MAGIC = 0x53484B31 // "SHK1"

        /**
         * @param materialPrior start with a tiny hand-written preference for having more pieces
         * (the weights remain fully trainable). With false the network starts knowing nothing.
         */
        fun create(seed: Long = 1L, materialPrior: Boolean = true): Network {
            val rnd = Random(seed)
            val w = FloatArray(SIZE)
            fun init(from: Int, count: Int, fanIn: Int) {
                val s = Math.sqrt(2.0 / fanIn).toFloat() * 0.5f
                for (i in from until from + count) w[i] = (rnd.nextGaussian().toFloat()) * s
            }
            init(O_W1, N_IN * H1, 24) // ~24 active inputs
            init(O_W2, H1 * H2, H1)
            init(O_W3, H2, H2)
            for (i in O_W3 until O_W3 + H2) w[i] *= 0.1f // start close to the (optional) prior
            if (materialPrior) for (sq in 0 until 32) {
                w[O_SKIP + 0 * 32 + sq] = 0.04f
                w[O_SKIP + 1 * 32 + sq] = 0.10f
                w[O_SKIP + 2 * 32 + sq] = -0.04f
                w[O_SKIP + 3 * 32 + sq] = -0.10f
            }
            return Network(w)
        }

        fun read(input: InputStream): Network {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "bad network file" }
            require(d.readInt() == SIZE) { "network shape mismatch" }
            val w = FloatArray(SIZE)
            for (i in w.indices) w[i] = d.readFloat()
            require(w.all { it.isFinite() }) { "corrupt weights" }
            return Network(w)
        }
    }

    private val active = IntArray(64)
    private var nActive = 0
    private val a1 = FloatArray(H1)
    private val a2 = FloatArray(H2)
    private var out = 0f

    fun copy() = Network(p.copyOf())
    fun copyFrom(o: Network) { System.arraycopy(o.p, 0, p, 0, SIZE) }

    fun write(output: OutputStream) {
        val d = DataOutputStream(output.buffered())
        d.writeInt(MAGIC); d.writeInt(SIZE)
        for (v in p) d.writeFloat(v)
        d.flush()
    }

    /** Fills [active] with indices of set input features, from the side to move's view. */
    private fun encode(pos: Position) {
        var n = 0
        val white = pos.whiteToMove
        val cells = pos.cells
        for (cell in 0 until 64) {
            val piece = cells[cell]
            if (piece == Piece.EMPTY) continue
            var sq = Board.darkIndex[cell]
            if (!white) sq = 31 - sq
            val mine = Piece.isMine(piece, white)
            val king = Piece.isKing(piece)
            val type = (if (mine) 0 else 2) + (if (king) 1 else 0)
            active[n++] = type * 32 + sq
        }
        nActive = n
    }

    private fun forward(): Float {
        for (j in 0 until H1) a1[j] = p[O_B1 + j]
        var skip = p[O_B3]
        for (k in 0 until nActive) {
            val i = active[k]
            val base = O_W1 + i * H1
            for (j in 0 until H1) a1[j] += p[base + j]
            skip += p[O_SKIP + i]
        }
        for (j in 0 until H1) if (a1[j] < 0f) a1[j] *= LEAK
        for (k in 0 until H2) a2[k] = p[O_B2 + k]
        for (j in 0 until H1) {
            val v = a1[j]
            val base = O_W2 + j * H2
            for (k in 0 until H2) a2[k] += v * p[base + k]
        }
        var z = skip
        for (k in 0 until H2) {
            if (a2[k] < 0f) a2[k] *= LEAK
            z += a2[k] * p[O_W3 + k]
        }
        out = tanh(z)
        return out
    }

    override fun evaluate(p: Position): Float {
        encode(p)
        return forward()
    }

    /**
     * Evaluates [pos] and adds `scale * d(value)/d(params)` to [acc]
     * (same layout as the parameters). Returns the value from the mover's view.
     */
    fun evaluateWithGradient(pos: Position, scale: Float, acc: FloatArray): Float {
        encode(pos)
        val v = forward()
        val dz = scale * (1f - v * v)
        acc[O_B3] += dz
        val dz2 = FloatArray(H2)
        for (k in 0 until H2) {
            acc[O_W3 + k] += dz * a2[k]
            val act = if (a2[k] >= 0f) 1f else LEAK
            dz2[k] = dz * p[O_W3 + k] * act
            acc[O_B2 + k] += dz2[k]
        }
        val dz1 = FloatArray(H1)
        for (j in 0 until H1) {
            val base = O_W2 + j * H2
            var s = 0f
            for (k in 0 until H2) {
                acc[base + k] += a1[j] * dz2[k]
                s += p[base + k] * dz2[k]
            }
            val act = if (a1[j] >= 0f) 1f else LEAK
            dz1[j] = s * act
            acc[O_B1 + j] += dz1[j]
        }
        for (n in 0 until nActive) {
            val i = active[n]
            val base = O_W1 + i * H1
            for (j in 0 until H1) acc[base + j] += dz1[j]
            acc[O_SKIP + i] += dz
        }
        return v
    }
}
