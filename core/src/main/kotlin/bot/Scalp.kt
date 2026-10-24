package bot

import kotlin.math.min

/**
 * Dip-buying scalp on 15-minute candles: buy when RSI shows the price has dropped sharply,
 * optionally only while the longer trend is up, then get out fast.
 */
data class ScalpSettings(
    /** Buy when RSI(14) of the last finished candle is below this. Lower = deeper dips, fewer trades. */
    val rsiBuy: Double = 30.0,
    /** Sell at this gain over the entry price (a limit order rests at Alpaca). */
    val takeProfitPct: Double = 0.015,
    /** Sell at this loss below the entry price (checked by the bot every 15 minutes). */
    val stopPct: Double = 0.015,
    /** Sell anyway after this many 15-minute candles (24 = 6 hours). */
    val maxHoldBars: Int = 24,
    /** Only buy dips while the price is above its 200-candle average (about 2 days). */
    val trendFilter: Boolean = true,
    /**
     * "dip": buy as soon as RSI is below [rsiBuy].
     * "reversal": wait until after a dip (RSI below [rsiBuy] within the last [dipLookback] candles)
     * the 9-candle average crosses above the 21-candle average, i.e. the price has started turning up.
     */
    val entry: String = "dip",
    val dipLookback: Int = 8,
) {
    init {
        require(rsiBuy > 0 && rsiBuy < 100) { "RSI level must be between 0 and 100" }
        require(takeProfitPct > 0 && stopPct > 0 && stopPct < 1) { "take profit and stop must be above 0%" }
        require(maxHoldBars >= 1) { "max hold must be at least one candle" }
    }
}

object Scalp {
    const val BAR_SECONDS = 15 * 60L
    const val RSI_PERIOD = 14
    const val TREND_BARS = 200
    const val WARMUP = TREND_BARS

    /** Wilder's RSI; NaN until enough data. */
    fun rsi(closes: DoubleArray, period: Int = RSI_PERIOD): DoubleArray {
        val out = DoubleArray(closes.size) { Double.NaN }
        if (closes.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period
        loss /= period
        out[period] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    const val TURN_FAST = 9
    const val TURN_SLOW = 21

    /** Exponential moving average, seeded with the first value. */
    fun ema(values: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(values.size)
        val k = 2.0 / (window + 1)
        for (i in values.indices) out[i] = if (i == 0) values[0] else values[i] * k + out[i - 1] * (1 - k)
        return out
    }

    /** Per candle: was RSI below the buy level recently, and did the short average just cross above the longer one? */
    class Parts(val dip: BooleanArray, val recentDip: BooleanArray, val turnUp: BooleanArray, val trendOk: BooleanArray)

    fun parts(closes: DoubleArray, s: ScalpSettings): Parts {
        val r = rsi(closes)
        val trend = Strategy.sma(closes, TREND_BARS)
        val fast = ema(closes, TURN_FAST)
        val slow = ema(closes, TURN_SLOW)
        val dip = BooleanArray(closes.size) { !r[it].isNaN() && r[it] < s.rsiBuy }
        var lastDip = -1_000_000
        val recentDip = BooleanArray(closes.size) { i ->
            if (dip[i]) lastDip = i
            i - lastDip <= s.dipLookback
        }
        val turnUp = BooleanArray(closes.size) { i -> i > 0 && fast[i] > slow[i] && fast[i - 1] <= slow[i - 1] }
        val trendOk = BooleanArray(closes.size) { i -> !s.trendFilter || (!trend[i].isNaN() && closes[i] > trend[i]) }
        return Parts(dip, recentDip, turnUp, trendOk)
    }

    /** true on candles whose close is a buy signal. */
    fun signal(closes: DoubleArray, s: ScalpSettings): BooleanArray {
        val p = parts(closes, s)
        return BooleanArray(closes.size) { i ->
            i >= WARMUP - 1 && p.trendOk[i] && if (s.entry == "reversal") p.recentDip[i] && p.turnUp[i] else p.dip[i]
        }
    }

    /**
     * Backtest that mirrors how the live bot can act:
     *  - entry at the next candle's open after a signal;
     *  - take profit fills inside any candle whose high reaches it (it rests as a limit order);
     *  - stop and time exits only at a candle's close, because the bot checks every 15 minutes;
     *  - every fill pays [Settings.feePct].
     */
    fun backtest(bars: List<Bar>, s: Settings, initialCash: Double = 100_000.0): BacktestResult {
        val p = s.scalp
        require(bars.size > WARMUP + 1) { "need more than $WARMUP candles, got ${bars.size}" }
        val sig = signal(bars.map { it.close }.toDoubleArray(), p)
        var cash = initialCash
        var qty = 0.0
        var entryRaw = 0.0
        var entryPaid = 0.0
        var entryIdx = 0
        var pendingBuy = false
        var held = 0
        val trades = mutableListOf<Trade>()
        val equity = DoubleArray(bars.size - WARMUP)

        for (i in WARMUP until bars.size) {
            val bar = bars[i]
            if (pendingBuy && qty == 0.0) {
                entryRaw = bar.open
                entryPaid = bar.open * (1 + s.feePct)
                qty = cash * s.maxPositionPct / entryPaid
                cash -= qty * entryPaid
                entryIdx = i
            }
            pendingBuy = false

            if (qty > 0) {
                val tp = entryRaw * (1 + p.takeProfitPct)
                val exit: Pair<Double, String>? = when {
                    bar.high >= tp -> maxOf(tp, bar.open) to "take profit"
                    bar.close <= entryRaw * (1 - p.stopPct) -> bar.close to "stop"
                    i - entryIdx + 1 >= p.maxHoldBars -> bar.close to "time"
                    else -> null
                }
                held++
                if (exit != null) {
                    val fill = exit.first * (1 - s.feePct)
                    cash += qty * fill
                    trades += Trade(bars[entryIdx].time, bar.time, entryPaid, fill, exit.second)
                    qty = 0.0
                }
            }
            equity[i - WARMUP] = cash + qty * bar.close
            if (qty == 0.0 && sig[i]) pendingBuy = true
        }
        val times = LongArray(equity.size) { bars[WARMUP + it].time }
        return BacktestResult(times, equity, trades, held.toDouble() / equity.size)
    }

    /** Buy and hold over the same candles as [backtest]. */
    fun buyAndHold(bars: List<Bar>, s: Settings, initialCash: Double = 100_000.0): BacktestResult {
        val qty = initialCash / (bars[WARMUP + 1].open * (1 + s.feePct))
        val equity = DoubleArray(bars.size - WARMUP) { if (it == 0) initialCash else qty * bars[WARMUP + it].close }
        return BacktestResult(LongArray(equity.size) { bars[WARMUP + it].time }, equity, emptyList(), 1.0)
    }

    /**
     * What the live bot should do now in scalp mode.
     * @param entryTime when the open position was bought (epoch seconds), or null if unknown
     */
    fun decide(
        s: Settings,
        completedBars: List<Bar>,
        lastPrice: Double,
        position: Position?,
        entryTime: Long?,
        now: Long,
        equity: Double,
        cash: Double,
    ): Action {
        val p = s.scalp
        if (position != null) {
            val tp = position.entryPrice * (1 + p.takeProfitPct)
            val stop = position.entryPrice * (1 - p.stopPct)
            if (lastPrice >= tp) return Action.Sell("Take profit: ${Decision.money(lastPrice)} reached ${Decision.money(tp)}")
            if (lastPrice <= stop) return Action.Sell("Stop: ${Decision.money(lastPrice)} is at or below ${Decision.money(stop)}")
            if (entryTime != null && now - entryTime >= p.maxHoldBars * BAR_SECONDS) {
                return Action.Sell("Time limit: held for ${p.maxHoldBars * 15} minutes")
            }
            return Action.Hold("Holding; take profit ${Decision.money(tp)}, stop ${Decision.money(stop)}")
        }
        if (completedBars.size <= WARMUP) return Action.Hold("Waiting for data: ${completedBars.size} of ${WARMUP + 1} candles")
        val closes = completedBars.map { it.close }.toDoubleArray()
        if (!signal(closes, p).last()) {
            val r = rsi(closes).last()
            val avg = Strategy.sma(closes, TREND_BARS).last()
            val parts = parts(closes, p)
            val n = closes.size - 1
            val rsiText = "RSI ${"%.0f".format(r)}, buys below ${"%.0f".format(p.rsiBuy)}"
            return Action.Hold(when {
                p.entry == "reversal" && !parts.recentDip[n] -> "No recent dip ($rsiText)"
                p.entry == "reversal" && !parts.turnUp[n] -> "Dip seen in the last ${p.dipLookback * 15} min; waiting for the price to turn up"
                p.entry != "reversal" && !parts.dip[n] -> "No dip: $rsiText"
                else -> "Buy setup found, but the price is below its 2-day average (${Decision.money(avg)}), " +
                    "so the trend filter is waiting"
            })
        }
        val notional = kotlin.math.floor(min(equity * s.maxPositionPct, cash) * 100) / 100
        if (notional < 10) return Action.Hold("Not enough cash to buy (${Decision.money(notional)})")
        return Action.Buy(notional, lastPrice * (1 - p.stopPct),
            if (p.entry == "reversal") "Dip then turn up: short average crossed above the longer one" else "Dip: RSI below ${"%.0f".format(p.rsiBuy)}")
    }
}
