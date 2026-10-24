package bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScalpTest {
    private val noFilter = Settings(mode = "scalp", maxPositionPct = 1.0, feePct = 0.0, scalp = ScalpSettings(trendFilter = false))

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Bar(i * Scalp.BAR_SECONDS, o, h, l, c)

    /** 230 flat candles, then a sharp drop that triggers RSI < 30, then a rebound. */
    private fun dipThenRebound(reboundHigh: Double): List<Bar> {
        val bars = (0 until 230).map { bar(it, 100.0, 100.5, 99.5, if (it % 2 == 0) 100.2 else 99.8) }.toMutableList()
        val p = 93.8
        bars += bar(bars.size, 99.8, 99.8, p, p) // one sharp drop: RSI falls below 30
        bars += bar(bars.size, p, reboundHigh, p - 0.1, p + 0.1) // entry at open = p
        repeat(30) { bars += bar(bars.size, p, p + 0.1, p - 0.1, p) }
        return bars
    }

    @Test fun rsiMatchesKnownValues() {
        val up = DoubleArray(30) { 100.0 + it }
        assertEquals(100.0, Scalp.rsi(up).last())
        val down = DoubleArray(30) { 100.0 - it }
        assertEquals(0.0, Scalp.rsi(down).last(), 1e-9)
    }

    @Test fun takesProfitInsideTheCandle() {
        val r = Scalp.backtest(dipThenRebound(reboundHigh = 200.0), noFilter)
        val t = r.trades.first()
        assertEquals("take profit", t.reason)
        assertEquals(93.8 * 1.015, t.exitPrice, 1e-9)
    }

    @Test fun timeExitWhenNothingHappens() {
        val r = Scalp.backtest(dipThenRebound(reboundHigh = 93.9), noFilter)
        assertEquals("time", r.trades.first().reason)
    }

    @Test fun feesMakeAFlatTradeLose() {
        val r = Scalp.backtest(dipThenRebound(reboundHigh = 93.9), noFilter.copy(feePct = 0.0025))
        assertTrue(r.trades.first().returnPct < -0.004)
    }

    @Test fun trendFilterBlocksDipsInDowntrends() {
        val r = Scalp.backtest(dipThenRebound(reboundHigh = 200.0), noFilter.copy(scalp = ScalpSettings(trendFilter = true)))
        assertEquals(0, r.trades.size)
    }

    @Test fun liveDecision() {
        val bars = dipThenRebound(200.0).take(231)
        assertIs<Action.Buy>(Scalp.decide(noFilter, bars, 93.8, null, null, 0, 1000.0, 1000.0))
        val pos = Position(1.0, 100.0)
        assertIs<Action.Sell>(Scalp.decide(noFilter, bars, 101.6, pos, 0, 60, 1000.0, 0.0))
        assertIs<Action.Sell>(Scalp.decide(noFilter, bars, 98.4, pos, 0, 60, 1000.0, 0.0))
        assertIs<Action.Sell>(Scalp.decide(noFilter, bars, 100.0, pos, 0, 24 * Scalp.BAR_SECONDS, 1000.0, 0.0))
        assertIs<Action.Hold>(Scalp.decide(noFilter, bars, 100.0, pos, 0, 60, 1000.0, 0.0))
    }
}
