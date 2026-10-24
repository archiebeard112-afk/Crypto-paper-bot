package bot

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Trend portfolio": the model that held up best in out-of-sample testing (tuned on 2020-2023,
 * judged on 2024-2026). The bot's money is split equally between BTC, ETH and SOL. For each coin,
 * on daily candles:
 *  - hold it only while its close is above its 150-day average;
 *  - size the holding so it swings about 50% a year: exposure = min(1, 50% / recent 30-day volatility);
 *  - trade only when the holding is more than 10% of its share away from the target, to save fees.
 */
object Portfolio {
    val COINS = listOf("BTC/USD", "ETH/USD", "SOL/USD")
    const val TREND_DAYS = 150
    const val VOL_DAYS = 30
    const val VOL_TARGET = 0.50
    const val BAND = 0.10
    const val DAY = 86_400L

    /** Target share of a coin's slice to hold, from finished daily closes (oldest first). */
    fun targetExposure(closes: DoubleArray): Double {
        val n = closes.size
        if (n < TREND_DAYS + 1) return 0.0
        val avg = closes.copyOfRange(n - TREND_DAYS, n).average()
        if (closes[n - 1] <= avg) return 0.0
        val rets = DoubleArray(VOL_DAYS) { closes[n - VOL_DAYS + it] / closes[n - VOL_DAYS + it - 1] - 1 }
        val mean = rets.average()
        val vol = sqrt(rets.sumOf { (it - mean) * (it - mean) } / (VOL_DAYS - 1)) * sqrt(365.0)
        return if (vol > 0) min(1.0, VOL_TARGET / vol) else 1.0
    }

    /**
     * Dollars to trade for one coin: positive = buy, negative = sell, 0 = leave it.
     * Exits fully when the target is zero; otherwise trades only outside the band.
     */
    fun tradeAmount(sliceValue: Double, holdingValue: Double, target: Double): Double {
        if (target == 0.0) return if (holdingValue > 1.0) -holdingValue else 0.0
        val diff = sliceValue * target - holdingValue
        return if (abs(diff) > BAND * sliceValue) diff else 0.0
    }

    /**
     * Backtest on daily candles. Trades happen at the close of the candle the decision is made on
     * (the live bot acts right after each daily candle finishes), paying [feePct] on every trade.
     * Uses only the dates all coins have data for.
     */
    fun backtest(barsByCoin: Map<String, List<Bar>>, feePct: Double, initialCash: Double = 100_000.0): BacktestResult {
        val byTime = barsByCoin.mapValues { (_, bars) -> bars.associateBy { it.time } }
        val times = byTime.values.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        val coins = barsByCoin.keys.toList()
        val closes = coins.associateWith { c -> DoubleArray(times.size) { byTime[c]!![times[it]]!!.close } }
        val start = TREND_DAYS
        require(times.size > start + 1) { "need more than $start days of data for every coin" }

        var cash = initialCash
        val qty = coins.associateWith { 0.0 }.toMutableMap()
        val equity = DoubleArray(times.size - start)
        var trades = 0
        var invested = 0.0
        for (i in start until times.size) {
            val price = coins.associateWith { closes[it]!![i] }
            val total = cash + coins.sumOf { qty[it]!! * price[it]!! }
            val slice = total / coins.size
            for (c in coins) {
                val target = targetExposure(closes[c]!!.copyOfRange(0, i + 1))
                val amount = tradeAmount(slice, qty[c]!! * price[c]!!, target)
                if (amount == 0.0) continue
                trades++
                if (amount > 0) {
                    val spend = min(amount, cash)
                    qty[c] = qty[c]!! + spend * (1 - feePct) / price[c]!!
                    cash -= spend
                } else {
                    val sellQty = min(qty[c]!!, -amount / price[c]!!)
                    qty[c] = qty[c]!! - sellQty
                    cash += sellQty * price[c]!! * (1 - feePct)
                }
            }
            val value = cash + coins.sumOf { qty[it]!! * price[it]!! }
            invested += 1 - cash / value
            equity[i - start] = value
        }
        val trimmed = LongArray(equity.size) { times[start + it] }
        // Individual rebalances aren't round trips, so they are reported as a count with no per-trade returns.
        val pseudoTrades = List(trades) { Trade(0, 0, 1.0, 1.0, "rebalance") }
        return BacktestResult(trimmed, equity, pseudoTrades, invested / equity.size)
    }

    /** Equal-weight buy and hold of the same coins over the same dates. */
    fun buyAndHold(barsByCoin: Map<String, List<Bar>>, feePct: Double, initialCash: Double = 100_000.0): BacktestResult {
        val byTime = barsByCoin.mapValues { (_, bars) -> bars.associateBy { it.time } }
        val times = byTime.values.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        val coins = barsByCoin.keys.toList()
        val start = TREND_DAYS
        val qty = coins.associateWith { initialCash / coins.size * (1 - feePct) / byTime[it]!![times[start]]!!.close }
        val equity = DoubleArray(times.size - start) { k -> coins.sumOf { qty[it]!! * byTime[it]!![times[start + k]]!!.close } }
        return BacktestResult(LongArray(equity.size) { times[start + it] }, equity, emptyList(), 1.0)
    }
}
