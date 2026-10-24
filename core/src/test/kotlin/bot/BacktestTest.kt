package bot

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

fun loadCsv(name: String): List<Bar> =
    BacktestTest::class.java.getResource("/$name")!!.readText().lines().drop(1).filter { it.isNotBlank() }.map {
        val c = it.split(",")
        val t = LocalDate.parse(c[0]).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        Bar(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble())
    }

class BacktestTest {
    private val goog = loadCsv("goog_daily_2004_2013.csv")
    private val base = Settings(fast = 50, slow = 200, maxPositionPct = 0.5, stopLossPct = 0.10, feePct = 0.0005)

    private fun total(r: BacktestResult) = r.equity.last() / r.equity.first() - 1

    // Expected numbers come from the independent Python implementation of the same rules.
    @Test fun matchesReferenceImplementation() {
        assertClose(1.853, total(Backtest.buyAndHold(goog, base)))
        assertClose(0.655, total(Backtest.run(goog, base.copy(maxPositionPct = 1.0, stopLossPct = 1.0))))
        assertClose(0.718, total(Backtest.run(goog, base.copy(maxPositionPct = 1.0))))
        assertClose(0.339, total(Backtest.run(goog, base)))
    }

    @Test fun signalUsesOnlyPastBars() {
        val closes = DoubleArray(300) { 100.0 + it }
        val full = Strategy.signal(closes, 5, 20)
        val truncated = Strategy.signal(closes.copyOf(150), 5, 20)
        for (i in truncated.indices) assertEquals(full[i], truncated[i])
        assertEquals(0, full[18]) // slow average not ready yet
    }

    @Test fun stopFillsAtOpenOnGapDown() {
        val bars = mutableListOf<Bar>()
        for (i in 0 until 30) bars += Bar(i * 86400L, 100.0 + i, 101.0 + i, 99.0 + i, 100.0 + i)
        bars += Bar(30 * 86400L, 50.0, 51.0, 49.0, 50.0) // crash through the stop
        val s = Settings(fast = 3, slow = 10, maxPositionPct = 1.0, stopLossPct = 0.1, feePct = 0.0)
        val r = Backtest.run(bars, s)
        val stop = r.trades.single { it.reason == "stop" }
        assertEquals(50.0, stop.exitPrice)
    }

    @Test fun decisionRespectsCapStopAndReentry() {
        val bars = (0 until 120).map { Bar(it * 86400L, 100.0 + it, 101.0 + it, 99.0 + it, 100.0 + it) }
        val s = Settings(fast = 5, slow = 20, maxPositionPct = 0.25, stopLossPct = 0.1)
        val buy = Decision.decide(s, bars, 219.0, null, 10_000.0, 10_000.0) { false }
        assertIs<Action.Buy>(buy)
        assertEquals(2500.0, buy.notional)
        assertIs<Action.Hold>(Decision.decide(s, bars, 219.0, null, 10_000.0, 10_000.0) { true })
        assertIs<Action.Sell>(Decision.decide(s, bars, 179.0, Position(10.0, 200.0), 10_000.0, 0.0) { false })
        assertIs<Action.Hold>(Decision.decide(s, bars, 219.0, Position(10.0, 200.0), 10_000.0, 0.0) { false })
        val falling = bars.reversed().mapIndexed { i, b -> b.copy(time = i * 86400L) }
        assertIs<Action.Sell>(Decision.decide(s, falling, 100.0, Position(1.0, 100.0), 10_000.0, 0.0) { false })
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 0.001, "expected $expected, got $actual")
}
