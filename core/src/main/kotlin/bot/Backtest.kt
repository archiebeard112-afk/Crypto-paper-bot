package bot

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class Trade(val entryTime: Long, val exitTime: Long, val entryPrice: Double, val exitPrice: Double, val reason: String) {
    val returnPct get() = exitPrice / entryPrice - 1
}

data class BacktestResult(val times: LongArray, val equity: DoubleArray, val trades: List<Trade>, val timeInMarket: Double)

data class Metrics(
    val totalReturn: Double,
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val trades: Int,
    val stopOuts: Int,
    val winRate: Double,
    val timeInMarket: Double,
)

/**
 * Bar-by-bar backtest. Timing rules, chosen so the strategy never sees the future:
 *  - The signal is computed from a bar's close.
 *  - Entries and signal exits fill at the NEXT bar's open.
 *  - The stop is checked against each bar's low; a gap below the stop fills at the open.
 *  - Every fill pays [Settings.feePct].
 *  - After a stop-out the strategy waits for a fresh cross up before buying again.
 */
object Backtest {
    fun run(bars: List<Bar>, s: Settings, initialCash: Double = 100_000.0): BacktestResult {
        val signal = Strategy.signal(bars.map { it.close }.toDoubleArray(), s.fast, s.slow)
        val start = s.slow - 1
        require(bars.size > start + 1) { "need more than ${s.slow} bars, got ${bars.size}" }

        var cash = initialCash
        var qty = 0.0
        var entryPrice = 0.0
        var entryTime = 0L
        var pending: String? = null
        var stopped = false
        var held = 0
        val trades = mutableListOf<Trade>()
        val equity = DoubleArray(bars.size - start)

        fun sell(bar: Bar, price: Double, reason: String) {
            val fill = price * (1 - s.feePct)
            cash += qty * fill
            trades += Trade(entryTime, bar.time, entryPrice, fill, reason)
            qty = 0.0
        }

        for (i in start until bars.size) {
            val bar = bars[i]
            if (pending == "buy" && qty == 0.0) {
                entryPrice = bar.open * (1 + s.feePct)
                entryTime = bar.time
                qty = cash * s.maxPositionPct / entryPrice
                cash -= qty * entryPrice
            } else if (pending == "sell" && qty > 0) {
                sell(bar, bar.open, "signal")
            }
            pending = null

            if (qty > 0) {
                val stop = entryPrice * (1 - s.stopLossPct)
                if (bar.low <= stop) {
                    sell(bar, min(stop, bar.open), "stop")
                    stopped = true
                }
            }
            if (qty > 0) held++
            equity[i - start] = cash + qty * bar.close

            if (signal[i] == 0) {
                stopped = false
                if (qty > 0) pending = "sell"
            } else if (qty == 0.0 && !stopped) {
                pending = "buy"
            }
        }
        val times = LongArray(equity.size) { bars[start + it].time }
        return BacktestResult(times, equity, trades, held.toDouble() / equity.size)
    }

    /** Buy with all cash on the strategy's first possible fill bar and hold to the end. */
    fun buyAndHold(bars: List<Bar>, s: Settings, initialCash: Double = 100_000.0): BacktestResult {
        val start = s.slow - 1
        val qty = initialCash / (bars[start + 1].open * (1 + s.feePct))
        val equity = DoubleArray(bars.size - start) { if (it == 0) initialCash else qty * bars[start + it].close }
        val times = LongArray(equity.size) { bars[start + it].time }
        return BacktestResult(times, equity, emptyList(), 1.0)
    }

    fun metrics(r: BacktestResult, periodsPerYear: Double): Metrics {
        val eq = r.equity
        val years = (eq.size - 1) / periodsPerYear
        val rets = DoubleArray(eq.size - 1) { eq[it + 1] / eq[it] - 1 }
        val mean = rets.average()
        val sd = sqrt(rets.sumOf { (it - mean).pow(2) } / (rets.size - 1))
        var peak = eq[0]
        var maxDd = 0.0
        for (v in eq) {
            peak = maxOf(peak, v)
            maxDd = min(maxDd, v / peak - 1)
        }
        val growth = eq.last() / eq.first()
        return Metrics(
            totalReturn = growth - 1,
            cagr = if (years > 0) growth.pow(1 / years) - 1 else 0.0,
            maxDrawdown = maxDd,
            sharpe = if (sd > 0) mean / sd * sqrt(periodsPerYear) else 0.0,
            trades = r.trades.size,
            stopOuts = r.trades.count { it.reason == "stop" },
            winRate = if (r.trades.isEmpty()) Double.NaN else r.trades.count { it.returnPct > 0 }.toDouble() / r.trades.size,
            timeInMarket = r.timeInMarket,
        )
    }
}
