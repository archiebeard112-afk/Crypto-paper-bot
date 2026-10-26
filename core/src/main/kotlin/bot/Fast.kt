package bot

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** Fast mode: many quick trades on 1-minute candles with a fixed take-profit and stop-loss. */
data class FastSettings(
    /** Sell when the price is this far above the entry (a limit order rests at Alpaca). */
    val takeProfitPct: Double = 0.02,
    /** Sell when the price is this far below the entry. */
    val stopPct: Double = 0.01,
    /** Sell anyway after this many minutes. */
    val maxHoldMinutes: Int = 60,
    /** Only trade when the typical 1-hour move is at least this big; 0 turns the filter off. */
    val minHourlyMove: Double = 0.01,
    /** "breakout": new 20-minute high in a short uptrend. "dip_turn_up": RSI dipped below 30, then the 9/21 averages crossed up. */
    val entry: String = "breakout",
) {
    init {
        require(takeProfitPct > 0 && stopPct > 0 && stopPct < 1) { "take profit and stop loss must be above 0%" }
        require(maxHoldMinutes >= 1) { "max hold must be at least 1 minute" }
    }
}

object Fast {
    const val BAR_SECONDS = 60L
    const val WARMUP = 61

    /** Typical move over [window] candles: std of 1-candle log returns × sqrt(window). NaN until enough data. */
    fun typicalMove(closes: DoubleArray, window: Int, end: Int = closes.size): Double {
        if (end < window + 1) return Double.NaN
        val r = DoubleArray(window) { ln(closes[end - window + it] / closes[end - window + it - 1]) }
        val m = r.average()
        return sqrt(r.sumOf { (it - m) * (it - m) } / (window - 1)) * sqrt(window.toDouble())
    }

    fun signal(bars: List<Bar>, p: FastSettings): BooleanArray {
        val closes = bars.map { it.close }.toDoubleArray()
        val e9 = Scalp.ema(closes, 9)
        val e21 = Scalp.ema(closes, 21)
        val rsi = Scalp.rsi(closes)
        return BooleanArray(bars.size) { i ->
            if (i < WARMUP - 1) return@BooleanArray false
            val volOk = p.minHourlyMove <= 0 || typicalMove(closes, 60, i + 1) > p.minHourlyMove
            val setup = when (p.entry) {
                "dip_turn_up" -> e9[i] > e21[i] && e9[i - 1] <= e21[i - 1] &&
                    (i - 9..i).any { k -> !rsi[k].isNaN() && rsi[k] < 30 }
                else -> e9[i] > e21[i] && closes[i] > (i - 20 until i).maxOf { bars[it].high }
            }
            volOk && setup
        }
    }

    /**
     * Backtest matching the research script: enter at the next candle's open; within a candle the stop is
     * assumed to hit before the take-profit (the cautious assumption); time exit at the close.
     */
    fun backtest(bars: List<Bar>, s: Settings, p: FastSettings, initialCash: Double = 100_000.0): BacktestResult {
        require(bars.size > WARMUP + 2) { "need more than $WARMUP one-minute candles" }
        val sig = signal(bars, p)
        var cash = initialCash
        var qty = 0.0
        var entryPx = 0.0
        var entryPaid = 0.0
        var entryIdx = 0
        var pending = false
        var held = 0
        val trades = mutableListOf<Trade>()
        val equity = DoubleArray(bars.size - WARMUP)
        for (i in WARMUP until bars.size) {
            val b = bars[i]
            if (pending && qty == 0.0) {
                entryPx = b.open
                entryPaid = b.open * (1 + s.feePct)
                qty = cash * s.maxPositionPct / entryPaid
                cash -= qty * entryPaid
                entryIdx = i
            }
            pending = false
            if (qty > 0) {
                held++
                val stop = entryPx * (1 - p.stopPct)
                val take = entryPx * (1 + p.takeProfitPct)
                val exit = when {
                    b.low <= stop -> min(stop, b.open) to "stop"
                    b.high >= take -> maxOf(take, b.open) to "take profit"
                    i - entryIdx + 1 >= p.maxHoldMinutes -> b.close to "time"
                    else -> null
                }
                if (exit != null) {
                    val fill = exit.first * (1 - s.feePct)
                    cash += qty * fill
                    trades += Trade(bars[entryIdx].time, b.time, entryPaid, fill, exit.second)
                    qty = 0.0
                }
            }
            equity[i - WARMUP] = cash + qty * b.close
            if (qty == 0.0 && sig[i]) pending = true
        }
        return BacktestResult(LongArray(equity.size) { bars[WARMUP + it].time }, equity, trades, held.toDouble() / equity.size)
    }

    /** Buy and hold over the same candles as [backtest]. */
    fun buyAndHold(bars: List<Bar>, s: Settings, initialCash: Double = 100_000.0): BacktestResult {
        val qty = initialCash / (bars[WARMUP + 1].open * (1 + s.feePct))
        val equity = DoubleArray(bars.size - WARMUP) { if (it == 0) initialCash else qty * bars[WARMUP + it].close }
        return BacktestResult(LongArray(equity.size) { bars[WARMUP + it].time }, equity, emptyList(), 1.0)
    }

    /**
     * Auto coin: the coin with the biggest typical 1-hour move right now. Only called while nothing is held,
     * so the bot never switches out of an open trade. A challenger must move [margin] times more than the
     * current coin, so the bot doesn't flip between coins that are about equally busy.
     */
    fun pickCoin(moves: Map<String, Double>, current: String, margin: Double = 1.2): String {
        val best = moves.filterValues { !it.isNaN() }.maxByOrNull { it.value } ?: return current
        val now = moves[current]
        return if (now == null || now.isNaN() || best.value > now * margin) best.key else current
    }

    /**
     * [backtest] with auto coin: while flat, follow [pickCoin] each minute and enter on that coin's setup.
     * Candles are matched by time; minutes missing from any coin are skipped.
     */
    fun backtestAuto(barsByCoin: Map<String, List<Bar>>, s: Settings, p: FastSettings, initialCash: Double = 100_000.0): BacktestResult {
        val times = barsByCoin.values.map { bars -> bars.map { it.time }.toSet() }.reduce { a, b -> a intersect b }.sorted()
        val coins = barsByCoin.keys.toList()
        val aligned = coins.associateWith { c -> val byTime = barsByCoin[c]!!.associateBy { it.time }; times.map { byTime[it]!! } }
        require(times.size > WARMUP + 2) { "need more than $WARMUP one-minute candles" }
        val sig = coins.associateWith { signal(aligned[it]!!, p) }
        val closes = coins.associateWith { c -> aligned[c]!!.map { it.close }.toDoubleArray() }
        var coin = s.symbol.takeIf { it in coins } ?: coins.first()
        var cash = initialCash
        var qty = 0.0
        var entryPx = 0.0
        var entryPaid = 0.0
        var entryIdx = 0
        var pending: String? = null
        var held = 0
        val trades = mutableListOf<Trade>()
        val equity = DoubleArray(times.size - WARMUP)
        for (i in WARMUP until times.size) {
            if (pending != null && qty == 0.0) {
                coin = pending
                val b = aligned[coin]!![i]
                entryPx = b.open
                entryPaid = b.open * (1 + s.feePct)
                qty = cash * s.maxPositionPct / entryPaid
                cash -= qty * entryPaid
                entryIdx = i
            }
            pending = null
            val b = aligned[coin]!![i]
            if (qty > 0) {
                held++
                val stop = entryPx * (1 - p.stopPct)
                val take = entryPx * (1 + p.takeProfitPct)
                val exit = when {
                    b.low <= stop -> min(stop, b.open) to "stop"
                    b.high >= take -> maxOf(take, b.open) to "take profit"
                    i - entryIdx + 1 >= p.maxHoldMinutes -> b.close to "time"
                    else -> null
                }
                if (exit != null) {
                    val fill = exit.first * (1 - s.feePct)
                    cash += qty * fill
                    trades += Trade(aligned[coin]!![entryIdx].time, b.time, entryPaid, fill, exit.second)
                    qty = 0.0
                }
            }
            equity[i - WARMUP] = cash + qty * b.close
            if (qty == 0.0) {
                val pick = pickCoin(coins.associateWith { typicalMove(closes[it]!!, 60, i + 1) }, coin)
                coin = pick
                if (sig[coin]!![i]) pending = coin
            }
        }
        return BacktestResult(LongArray(equity.size) { times[WARMUP + it] }, equity, trades, held.toDouble() / equity.size)
    }

    /** How busy a coin is: typical 1-hour move now, over the last 24 hours, and the 7-day normal (median hour). */
    data class Volatility(val lastHour: Double, val lastDay: Double, val normalHour: Double) {
        val ratio get() = lastHour / normalHour
        val label get() = when {
            ratio.isNaN() -> "n/a"
            ratio < 0.7 -> "Calm"
            ratio < 1.5 -> "Normal"
            else -> "Busy"
        }
    }

    fun volatility(closes: DoubleArray): Volatility {
        val hours = (61..closes.size step 60).map { typicalMove(closes, 60, it) }.filter { !it.isNaN() }.sorted()
        return Volatility(typicalMove(closes, 60), typicalMove(closes, 1440),
            if (hours.isEmpty()) Double.NaN else hours[hours.size / 2])
    }

    fun decide(
        s: Settings,
        p: FastSettings,
        completedBars: List<Bar>,
        lastPrice: Double,
        position: Position?,
        entryTime: Long?,
        now: Long,
        equity: Double,
        cash: Double,
    ): Action {
        if (position != null) {
            val take = position.entryPrice * (1 + p.takeProfitPct)
            val stop = position.entryPrice * (1 - p.stopPct)
            if (lastPrice <= stop) return Action.Sell("Stop loss at ${Decision.money(lastPrice)}")
            if (lastPrice >= take) return Action.Sell("Take profit at ${Decision.money(lastPrice)}")
            if (entryTime != null && now - entryTime >= p.maxHoldMinutes * 60L) return Action.Sell("Time limit (${p.maxHoldMinutes} min)")
            return Action.Hold("Holding; take profit ${Decision.money(take)}, stop ${Decision.money(stop)}")
        }
        if (completedBars.size <= WARMUP) return Action.Hold("Waiting for data")
        val closes = completedBars.map { it.close }.toDoubleArray()
        val move = typicalMove(closes, 60)
        if (p.minHourlyMove > 0 && !(move > p.minHourlyMove)) {
            return Action.Hold("Market too calm: typical 1-hour move ${pctText(move)}, needs ${pctText(p.minHourlyMove)}")
        }
        if (!signal(completedBars, p).last()) return Action.Hold("No setup this minute (1-hour move ${pctText(move)})")
        val notional = kotlin.math.floor(min(equity * s.maxPositionPct, cash) * 100) / 100
        if (notional < 10) return Action.Hold("Not enough cash to buy (${Decision.money(notional)})")
        return Action.Buy(notional, lastPrice * (1 - p.stopPct),
            if (p.entry == "dip_turn_up") "Dip then turn up on 1-minute candles" else "New 20-minute high in a short uptrend")
    }

    private fun pctText(v: Double) = if (v.isNaN()) "n/a" else "%.2f%%".format(java.util.Locale.US, v * 100)
}
