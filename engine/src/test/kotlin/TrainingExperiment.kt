// Manual experiment, not a unit test. Run: java -cp <classes+kotlin-stdlib> TrainingExperimentKt <prior|pure> <alpha> <games> [depth]
import dev.bestiary.shashkiai.engine.*

fun main(args: Array<String>) {
    val prior = args[0] == "prior"
    val alpha = args[1].toFloat()
    val games = args[2].toInt()
    val depth = args.getOrElse(3) { "3" }.toInt()
    val savePath = args.getOrNull(4)
    val net = Network.create(seed = 5, materialPrior = prior)
    val learner = TdLearner(net, TdConfig(alpha = alpha, depth = depth), seed = 99)
    val t0 = System.currentTimeMillis()
    fun bench(tag: String) {
        val me = SearchPlayer(net.copy(), 4)
        val vsMat = Arena.match(me, SearchPlayer(MaterialEvaluator, 4, 3), 60, seed = 5)
        val vsRnd = Arena.match(me, RandomPlayer(), 10, seed = 6)
        println("$tag games=${learner.gamesLearned} t=${(System.currentTimeMillis()-t0)/1000}s  vsMaterial(d4)=$vsMat  vsRandom=$vsRnd")
    }
    bench("start")
    var dsum = 0f; var n = 0
    for (g in 1..games) {
        val s = learner.selfPlayGame()
        dsum += s.avgAbsDelta; n++
        if (g % 100 == 0) { println("  g=$g avg|delta|=${"%.3f".format(dsum/n)} lastPlies=${s.plies} ${s.status}"); dsum=0f; n=0 }
        if (g % (games/4) == 0) bench("after")
    }
    savePath?.let { java.io.File(it).outputStream().use { o -> net.write(o) } }
}
