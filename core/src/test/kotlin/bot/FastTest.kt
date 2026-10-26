package bot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FastTest {
    private val s = Settings(mode = "fast", maxPositionPct = 1.0, feePct = 0.0)
    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Bar(i * 60L, o, h, l, c)

    /** Choppy candles, then a breakout to a new 20-minute high. */
    private fun breakout(after: (Int, Double) -> Bar): List<Bar> {
        val bars = (0 until 80).map { val c = 100.0 + if (it % 2 == 0) 1.5 else -1.5; bar(it, c, c + 0.2, c - 0.2, c) }.toMutableList()
        bars += bar(80, 100.0, 104.0, 100.0, 104.0)
        for (k in 1..70) bars += after(80 + k, 104.0)
        return bars
    }

    @Test fun breakoutTriggersOnlyWhenVolatileEnough() {
        val bars = breakout { i, p -> bar(i, p, p + 0.1, p - 0.1, p) }
        assertTrue(Fast.signal(bars, FastSettings(minHourlyMove = 0.01))[80])
        assertTrue(!Fast.signal(bars, FastSettings(minHourlyMove = 0.5))[80])
    }

    @Test fun stopCountsBeforeTakeProfitInTheSameCandle() {
        val bars = breakout { i, p -> bar(i, p, p * 1.05, p * 0.95, p) }
        val r = Fast.backtest(bars, s, FastSettings(minHourlyMove = 0.0))
        assertEquals("stop", r.trades.first().reason)
    }

    @Test fun takeProfitAndTimeExit() {
        val tp = Fast.backtest(breakout { i, p -> bar(i, p, p * 1.02, p * 0.999, p) }, s, FastSettings(minHourlyMove = 0.0))
        assertEquals("take profit", tp.trades.first().reason)
        val flat = Fast.backtest(breakout { i, p -> bar(i, p, p + 0.01, p - 0.01, p) }, s, FastSettings(minHourlyMove = 0.0, maxHoldMinutes = 10))
        assertEquals("time", flat.trades.first().reason)
    }

    @Test fun liveDecisionExits() {
        val bars = breakout { i, p -> bar(i, p, p + 0.1, p - 0.1, p) }.take(81)
        val p = FastSettings(minHourlyMove = 0.0, takeProfitPct = 0.01, stopPct = 0.005)
        assertIs<Action.Buy>(Fast.decide(s, p, bars, 104.0, null, null, 0, 1000.0, 1000.0))
        val pos = Position(1.0, 100.0)
        assertIs<Action.Sell>(Fast.decide(s, p, bars, 101.1, pos, 0, 10, 1000.0, 0.0))
        assertIs<Action.Sell>(Fast.decide(s, p, bars, 99.4, pos, 0, 10, 1000.0, 0.0))
        assertIs<Action.Sell>(Fast.decide(s, p, bars, 100.2, pos, 0, 3600, 1000.0, 0.0))
        assertIs<Action.Hold>(Fast.decide(s, p, bars, 100.2, pos, 0, 10, 1000.0, 0.0))
    }

    @Test fun pickCoinSwitchesOnlyForAClearlyBusierCoin() {
        val moves = mapOf("BTC/USD" to 0.010, "ETH/USD" to 0.011, "SOL/USD" to Double.NaN)
        assertEquals("BTC/USD", Fast.pickCoin(moves, "BTC/USD"))
        assertEquals("ETH/USD", Fast.pickCoin(moves + ("ETH/USD" to 0.013), "BTC/USD"))
        assertEquals("ETH/USD", Fast.pickCoin(moves, "SOL/USD"))
    }

    @Test fun autoCoinWithOneCoinMatchesPlainBacktest() {
        val bars = breakout { i, c -> bar(i, c, c + 3.0, c - 0.1, c + 0.5) }
        val plain = Fast.backtest(bars, s, FastSettings(minHourlyMove = 0.0))
        val auto = Fast.backtestAuto(mapOf("BTC/USD" to bars), s, FastSettings(minHourlyMove = 0.0))
        assertEquals(plain.trades, auto.trades)
        assertEquals(plain.equity.last(), auto.equity.last(), 1e-9)
    }

    @Test fun autoCoinTradesTheBusyCoin() {
        val busy = breakout { i, c -> bar(i, c, c + 3.0, c - 0.1, c + 0.5) }
        val calm = busy.map { Bar(it.time, 50.0, 50.0, 50.0, 50.0) }
        val r = Fast.backtestAuto(mapOf("BTC/USD" to calm, "ETH/USD" to busy), s, FastSettings(minHourlyMove = 0.0))
        assertTrue(r.trades.isNotEmpty())
        assertTrue(r.trades.all { it.entryPrice > 100 })
    }

    /** Real-data report from Binance 1-minute folders in FAST_DATA_DIR/<SYMBOL>/1m. Skipped if unset. */
    @Test fun printFastReport() {
        val root = System.getenv("FAST_DATA_DIR")?.let(::File) ?: return
        for (sym in listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")) {
            val bars = load(File(root, "$sym/1m"))
            for ((label, p) in listOf(
                "default (breakout, +2%/-1%, vol>1%)" to FastSettings(),
                "many trades (breakout, +0.3%/-0.3%, no filter)" to FastSettings(takeProfitPct = 0.003, stopPct = 0.003, minHourlyMove = 0.0),
            )) for (fee in listOf(0.0025, 0.0)) {
                val r = Fast.backtest(bars, s.copy(feePct = fee), p)
                val m = Backtest.metrics(r, 365.0 * 1440)
                println("%s %-48s fee %.2f%%: return %7.1f%%  trades %5d  per day %5.1f  win %3.0f%%".format(sym, label, fee * 100,
                    m.totalReturn * 100, m.trades, m.trades / (bars.size / 1440.0), m.winRate * 100))
            }
        }
        val all = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT").associate { sym -> sym to load(File(root, "$sym/1m")) }
        for ((label, p) in listOf(
            "default (breakout, +2%/-1%, vol>1%)" to FastSettings(),
            "no vol filter (breakout, +2%/-1%)" to FastSettings(minHourlyMove = 0.0),
            "many trades (breakout, +0.3%/-0.3%, no filter)" to FastSettings(takeProfitPct = 0.003, stopPct = 0.003, minHourlyMove = 0.0),
        )) for (fee in listOf(0.0025, 0.001, 0.0)) {
            val r = Fast.backtestAuto(all, s.copy(symbol = "BTCUSDT", feePct = fee), p)
            val m = Backtest.metrics(r, 365.0 * 1440)
            println("AUTO %-48s fee %.2f%%: return %7.1f%%  trades %5d  per day %5.1f  win %3.0f%%  worst drop %6.1f%%".format(label, fee * 100,
                m.totalReturn * 100, m.trades, m.trades / (r.times.size / 1440.0), m.winRate * 100, m.maxDrawdown * 100))
        }
    }

    private fun load(dir: File) = dir.listFiles()!!.sorted().flatMap { f ->
        f.readLines().filter { it.isNotBlank() && it[0].isDigit() }.map {
            val c = it.split(",")
            val t = c[0].toLong().let { v -> if (v > 100_000_000_000_000L) v / 1_000_000 else v / 1000 }
            Bar(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble())
        }
    }
}
