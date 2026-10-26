package bot

/** One price bar. [time] is the bar's start in epoch seconds. */
data class Bar(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)

/** Strategy and risk settings, shared by the backtest and the live paper bot. */
data class Settings(
    val symbol: String = "BTC/USD",
    /** Moving-average crossover: hold when SMA(fast) > SMA(slow), stay in cash otherwise. */
    val fast: Int = 20,
    val slow: Int = 100,
    /** Never put more than this share of account equity into the position. */
    val maxPositionPct: Double = 0.50,
    /** Sell if the price falls this far below the entry price. */
    val stopLossPct: Double = 0.15,
    /** Backtest cost per fill (exchange fee + slippage). Alpaca's crypto taker fee is up to 0.25%. */
    val feePct: Double = 0.0025,
    /** "trend" (daily crossover above), "scalp" ([Scalp]), "portfolio" ([Portfolio]) or "fast" ([Fast]). */
    val mode: String = "trend",
    val scalp: ScalpSettings = ScalpSettings(),
    val quick: FastSettings = FastSettings(),
    /** Fast mode only: trade whichever coin is moving most, switching only while nothing is held ([Fast.pickCoin]). */
    val autoCoin: Boolean = false,
) {
    init {
        require(fast in 1 until slow) { "fast window must be shorter than slow window" }
        require(maxPositionPct > 0 && maxPositionPct <= 1) { "position cap must be between 0 and 100%" }
        require(stopLossPct > 0 && stopLossPct <= 1) { "stop loss must be between 0 and 100%" }
    }
}

object Strategy {
    /** Simple moving average; NaN until [window] values are available. */
    fun sma(values: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= window) sum -= values[i - window]
            if (i >= window - 1) out[i] = sum / window
        }
        return out
    }

    /** 1 where SMA(fast) > SMA(slow) on that bar's close, else 0 (also 0 while the slow average warms up). */
    fun signal(closes: DoubleArray, fast: Int, slow: Int): IntArray {
        val f = sma(closes, fast)
        val s = sma(closes, slow)
        return IntArray(closes.size) { i -> if (!s[i].isNaN() && f[i] > s[i]) 1 else 0 }
    }

    /** Index of the most recent bar where the signal turned from 0 to 1, or -1. */
    fun lastCrossUp(signal: IntArray): Int {
        for (i in signal.indices.reversed()) {
            if (signal[i] == 1 && (i == 0 || signal[i - 1] == 0)) return i
        }
        return -1
    }
}
