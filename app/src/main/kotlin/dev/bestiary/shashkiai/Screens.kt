package dev.bestiary.shashkiai

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bestiary.shashkiai.engine.GameStatus
import dev.bestiary.shashkiai.engine.HumanGame
import dev.bestiary.shashkiai.engine.Level
import dev.bestiary.shashkiai.engine.Move
import dev.bestiary.shashkiai.engine.MoveInput
import dev.bestiary.shashkiai.engine.Searcher
import java.util.Random
import java.util.concurrent.Executors

private fun scrolling(act: MainActivity, build: (LinearLayout) -> Unit): View {
    val col = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(24))
    }
    build(col)
    return ScrollView(act).apply { isFillViewport = true; addView(col) }
}

// ---------------------------------------------------------------------------------------------

class HomeScreen(private val act: MainActivity) : Screen {
    private lateinit var stats: TextView
    private val listener = Brain.Listener { refresh() }

    override val view: View = scrolling(act) { col ->
        col.addView(Ui.text(act, "Шашки ИИ", 34f, Colors.ACCENT, true))
        col.addView(Ui.text(act, "Русские шашки · ИИ учится прямо на телефоне", 15f, Colors.MUTED), Ui.lp(top = 4, ctx = act))
        val card = Ui.card(act)
        stats = Ui.text(act, "", 15f)
        card.addView(stats)
        col.addView(card, Ui.lp(top = 24, ctx = act))
        col.addView(Ui.button(act, "Играть против ИИ", primary = true) { act.show(NewGameScreen(act)) }, Ui.lp(top = 20, ctx = act))
        col.addView(Ui.button(act, "Обучение ИИ") { act.show(TrainingScreen(act)) }, Ui.lp(top = 12, ctx = act))
        col.addView(
            Ui.text(act,
                "Нет интернета и облака: сеть оценки позиций хранится в памяти приложения и обучается обучением с " +
                    "подкреплением (TD-leaf(λ)) — после каждого хода, без поколений. ИИ учится на своих партиях " +
                    "с самим собой и на каждой партии с тобой.", 13f, Colors.MUTED),
            Ui.lp(top = 28, ctx = act))
        refresh()
    }

    private fun refresh() {
        val score = Brain.lastScore
        val strength = if (score == null) "ещё не измерена" else "${(score * 100).toInt()}% против подсчёта шашек"
        stats.text = "Партий обучения: ${Brain.totalGames}\nСила: $strength" +
            if (Brain.training) "\n● обучение идёт" else ""
    }

    override fun onShow() { Brain.addListener(listener); refresh() }
    override fun onHide() { Brain.removeListener(listener) }
}

// ---------------------------------------------------------------------------------------------

class NewGameScreen(private val act: MainActivity) : Screen {
    private var color = 0
    private var level = 1
    private var learn = true

    override val view: View = scrolling(act) { col ->
        col.addView(Ui.button(act, "←  Назад") { act.home() }, Ui.lp(w = act.dp(140)))
        col.addView(Ui.text(act, "Новая партия", 26f, Colors.TEXT, true), Ui.lp(top = 18, ctx = act))
        col.addView(Ui.text(act, "Твой цвет", 14f, Colors.MUTED), Ui.lp(top = 20, ctx = act))
        col.addView(Ui.chips(act, listOf("Белые", "Чёрные", "Случайно"), 0) { color = it }, Ui.lp(top = 8, ctx = act))
        col.addView(Ui.text(act, "Уровень ИИ", 14f, Colors.MUTED), Ui.lp(top = 20, ctx = act))
        val levels = Level.values()
        col.addView(Ui.chips(act, levels.map { it.title }, level) { level = it }, Ui.lp(top = 8, ctx = act))
        col.addView(Ui.text(act, "ИИ учится на этой партии", 14f, Colors.MUTED), Ui.lp(top = 20, ctx = act))
        col.addView(Ui.chips(act, listOf("Да", "Нет"), 0) { learn = it == 0 }, Ui.lp(top = 8, ctx = act))
        col.addView(Ui.button(act, "Начать", primary = true) {
            val white = when (color) { 0 -> true; 1 -> false; else -> Random().nextBoolean() }
            act.show(GameScreen(act, white, levels[level], learn))
        }, Ui.lp(top = 28, ctx = act))
    }

    override fun onBack(): Boolean { act.home(); return true }
}

// ---------------------------------------------------------------------------------------------

class GameScreen(
    private val act: MainActivity,
    humanWhite: Boolean,
    private val level: Level,
    private val learn: Boolean,
) : Screen {
    private val game = HumanGame(humanWhite, level)
    private val board = BoardView(act)
    private val status = Ui.text(act, "", 17f, Colors.TEXT, true)
    private val moveList = Ui.text(act, "", 13f, Colors.MUTED)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val rnd = Random()
    private var input = MoveInput(game.state.position.legalMoves())
    private var thinking = false
    private var animating = false
    private var gone = false
    private var generation = 0

    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(14))
        val top = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.button(act, "←") { leave() }, Ui.lp(w = act.dp(64)))
        top.addView(Ui.text(act, "  ${level.title} · ${if (humanWhite) "ты белыми" else "ты чёрными"}", 15f, Colors.MUTED),
            Ui.lp(weight = 1f))
        addView(top)
        addView(status, Ui.lp(top = 10, ctx = act))
        addView(board, Ui.lp(top = 10, ctx = act))
        val row = LinearLayout(act)
        row.addView(Ui.button(act, "Отмена хода") { undo() }, Ui.lp(w = 0, weight = 1f))
        row.addView(Ui.button(act, "Подсказка") { hint() }, Ui.lp(w = 0, weight = 1f).apply { marginStart = act.dp(8) })
        row.addView(Ui.button(act, "Сдаться", danger = true) { resign() }, Ui.lp(w = 0, weight = 1f).apply { marginStart = act.dp(8) })
        addView(row, Ui.lp(top = 12, ctx = act))
        addView(ScrollView(act).apply { addView(moveList) }, Ui.lp(h = 0, top = 10, ctx = act, weight = 1f))
    }

    init {
        board.flipped = !humanWhite
        board.onCellTap = { tap(it) }
        sync()
        if (!game.humanToMove) startAi()
    }

    override fun onHide() { gone = true; generation++; worker.shutdownNow() }

    override fun onBack(): Boolean { leave(); return true }

    private fun leave() {
        if (game.finished || game.moves.size < 2) { act.home(); return }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Выйти из партии?").setMessage("Незавершённая партия не будет учтена ИИ.")
            .setPositiveButton("Выйти") { _, _ -> act.home() }.setNegativeButton("Остаться", null).show()
    }

    private fun sync() {
        board.position = game.state.position
        board.lastMove = game.moves.lastOrNull()
        board.hint = null
        board.selected = input.from
        board.targets = input.targets()
        board.movable = if (game.humanToMove && !thinking && !animating) input.movableCells() else emptySet()
        status.text = when {
            game.finished -> resultText()
            thinking -> "ИИ думает…"
            game.humanToMove -> if (game.state.position.legalMoves().firstOrNull()?.isCapture == true) "Твой ход — бить обязательно" else "Твой ход"
            else -> "Ход ИИ"
        }
        val sb = StringBuilder()
        game.moves.forEachIndexed { i, m ->
            if (i % 2 == 0) sb.append(i / 2 + 1).append(". ")
            sb.append(m.notation()).append(if (i % 2 == 0) "   " else "\n")
        }
        moveList.text = sb.toString()
    }

    private fun resultText(): String {
        val r = game.result() ?: return ""
        return when {
            r == 0f -> "Ничья"
            (r > 0f) == game.humanWhite -> "Победа!"
            else -> "Поражение"
        }
    }

    private fun tap(cell: Int) {
        if (game.finished || thinking || animating || !game.humanToMove) return
        val m = input.tap(cell)
        if (m != null) playMove(m, byHuman = true) else sync()
    }

    private fun playMove(m: Move, byHuman: Boolean) {
        val before = game.state.position
        game.play(m)
        input = MoveInput(game.state.position.legalMoves())
        animating = true
        board.selected = -1; board.targets = emptySet(); board.movable = emptySet(); board.hint = null
        board.lastMove = m
        board.animateMove(before, m, game.state.position) {
            animating = false
            if (gone) return@animateMove
            sync()
            if (game.finished) finish() else if (!game.humanToMove) startAi()
        }
    }

    private fun startAi() {
        if (thinking || gone) return
        thinking = true
        sync()
        val pos = game.state.position
        val gen = ++generation
        val brainNet = Brain.snapshot()
        worker.execute {
            val t0 = System.currentTimeMillis()
            val moves = pos.legalMoves()
            val move = if (level == Level.BEGINNER && moves.size > 1 && rnd.nextInt(5) == 0) moves[rnd.nextInt(moves.size)]
            else Searcher(brainNet, Random()).search(pos, level.depth, level.timeMs).move!!
            val wait = 350 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
            main.post {
                if (gone || gen != generation) return@post
                thinking = false
                playMove(move, byHuman = false)
            }
        }
    }

    private fun hint() {
        if (game.finished || thinking || animating || !game.humanToMove) return
        val pos = game.state.position
        thinking = true; status.text = "Ищу подсказку…"
        val net = Brain.snapshot()
        val gen = generation
        worker.execute {
            val m = Searcher(net, Random()).search(pos, 6, 1500).move
            main.post {
                if (gone || gen != generation) return@post
                thinking = false
                sync()
                board.hint = m
            }
        }
    }

    private fun undo() {
        if (thinking || animating || game.finished) return
        if (game.undo()) { input = MoveInput(game.state.position.legalMoves()); sync() }
    }

    private fun resign() {
        if (game.finished || animating) return
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Сдаться?").setPositiveButton("Сдаться") { _, _ ->
                generation++; thinking = false
                game.resign(); sync(); finish()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun finish() {
        val txt = resultText()
        if (learn && game.moves.size >= 4) {
            Brain.learnFromHumanGame(game.startPosition, game.moves, game.finalStatus())
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(txt)
            .setMessage(if (learn) "ИИ разбирает партию и дообучается." else "Партия не учтена ИИ.")
            .setPositiveButton("Ещё партию") { _, _ -> act.show(NewGameScreen(act)) }
            .setNegativeButton("В меню") { _, _ -> act.home() }
            .setCancelable(false).show()
    }
}

// ---------------------------------------------------------------------------------------------

class TrainingScreen(private val act: MainActivity) : Screen {
    private val info = Ui.text(act, "", 15f)
    private val chart = ChartView(act)
    private lateinit var toggle: android.widget.Button
    private val listener = Brain.Listener { refresh() }

    override val view: View = scrolling(act) { col ->
        col.addView(Ui.button(act, "←  Назад") { act.home() }, Ui.lp(w = act.dp(140)))
        col.addView(Ui.text(act, "Обучение ИИ", 26f, Colors.TEXT, true), Ui.lp(top = 18, ctx = act))
        col.addView(Ui.text(act,
            "ИИ играет сам с собой. После каждого хода он поправляет веса сети так, чтобы оценка позиции " +
                "лучше предсказывала итог партии (TD(λ)). Можно свернуть приложение — обучение продолжится " +
                "с уведомлением.", 13f, Colors.MUTED), Ui.lp(top = 8, ctx = act))
        val card = Ui.card(act); card.addView(info)
        col.addView(card, Ui.lp(top = 16, ctx = act))
        toggle = Ui.button(act, "Начать обучение", primary = true) { toggleTraining() }
        col.addView(toggle, Ui.lp(top = 16, ctx = act))
        col.addView(Ui.text(act, "Нагрузка (нагрев и батарея)", 14f, Colors.MUTED), Ui.lp(top = 18, ctx = act))
        val duties = floatArrayOf(0.3f, 0.6f, 1f)
        val sel = duties.indexOfFirst { it >= Brain.duty - 0.01f }.coerceAtLeast(0)
        col.addView(Ui.chips(act, listOf("Эко", "Обычная", "Максимум"), sel) { Brain.duty = duties[it] }, Ui.lp(top = 8, ctx = act))
        col.addView(Ui.text(act, "Сила против подсчёта шашек", 14f, Colors.MUTED), Ui.lp(top = 22, ctx = act))
        col.addView(chart, Ui.lp(h = act.dp(180), top = 8, ctx = act))
        col.addView(Ui.button(act, "Проверить силу сейчас") { Brain.benchmarkAsync() }, Ui.lp(top = 12, ctx = act))
        col.addView(Ui.button(act, "Сбросить ИИ", danger = true) {
            AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Сбросить ИИ?").setMessage("Всё обучение будет потеряно, ИИ начнёт с нуля.")
                .setPositiveButton("Сбросить") { _, _ -> Thread({ Brain.reset() }).start() }
                .setNegativeButton("Отмена", null).show()
        }, Ui.lp(top = 12, ctx = act))
        refresh()
    }

    private fun toggleTraining() {
        if (Brain.training) TrainingService.stop(act)
        else { act.ensureNotificationPermission(); TrainingService.start(act) }
    }

    private fun refresh() {
        toggle.text = if (Brain.training) "Остановить обучение" else "Начать обучение"
        val score = Brain.lastScore
        info.text = buildString {
            append("Партий обучения: ${Brain.totalGames}\n")
            if (Brain.training) {
                append("За этот сеанс: ${Brain.gamesThisSession} (≈${Brain.gamesPerMinute.toInt()} в минуту)\n")
                append("Средняя ошибка предсказания: ${"%.3f".format(Brain.avgDelta)}\n")
            }
            append("Сила: ${if (score == null) "не измерена" else "${(score * 100).toInt()}% против подсчёта шашек"}")
            if (Brain.benchmarking) append("\nИзмеряю силу…")
        }
        chart.points = synchronized(Brain.history) { ArrayList(Brain.history) }
    }

    override fun onShow() { Brain.addListener(listener); refresh() }
    override fun onHide() { Brain.removeListener(listener) }
}
