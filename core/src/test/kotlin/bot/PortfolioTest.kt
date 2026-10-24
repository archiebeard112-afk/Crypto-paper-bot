package bot

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioTest {
    @Test fun noExposureBelowTheAverage() {
        val falling = DoubleArray(200) { 300.0 - it }
        assertEquals(0.0, Portfolio.targetExposure(falling))
    }

    @Test fun calmUptrendIsFullyInvestedAndWildOneIsScaledDown() {
        val calm = DoubleArray(200) { 100.0 * Math.pow(1.002, it.toDouble()) }
        assertEquals(1.0, Portfolio.targetExposure(calm))
        val wild = DoubleArray(200) { 100.0 + it + if (it % 2 == 0) 20.0 else -20.0 }
        val e = Portfolio.targetExposure(wild)
        assertTrue(e > 0 && e < 0.5, "exposure $e")
    }

    @Test fun tradesOnlyOutsideTheBand() {
        assertEquals(0.0, Portfolio.tradeAmount(1000.0, 950.0, 1.0)) // 5% off: leave it
        assertEquals(200.0, Portfolio.tradeAmount(1000.0, 800.0, 1.0), 1e-9)
        assertEquals(-500.0, Portfolio.tradeAmount(1000.0, 500.0, 0.0), 1e-9) // trend gone: sell all
    }

    /** Real-data report; reads Binance daily kline folders from PORTFOLIO_DATA_DIR/<SYMBOL>/1d. Skipped if unset. */
    @Test fun printPortfolioReport() {
        val root = System.getenv("PORTFOLIO_DATA_DIR")?.let(::File) ?: return
        val data = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT").associateWith { sym ->
            File(root, "$sym/1d").listFiles()!!.sorted().flatMap { f ->
                f.readLines().filter { it.isNotBlank() && it[0].isDigit() }.map {
                    val c = it.split(",")
                    val t = c[0].toLong().let { v -> if (v > 100_000_000_000_000L) v / 1_000_000 else v / 1000 }
                    Bar(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble())
                }
            }
        }
        val oosStart = Instant.parse("2024-01-01T00:00:00Z").epochSecond
        fun slice(r: BacktestResult, from: Long, to: Long): BacktestResult {
            val idx = r.times.indices.filter { r.times[it] in from until to }
            return BacktestResult(LongArray(idx.size) { r.times[idx[it]] }, DoubleArray(idx.size) { r.equity[idx[it]] }, emptyList(), 0.0)
        }
        for (fee in listOf(0.0025, 0.0015)) {
            val strat = Portfolio.backtest(data, fee)
            val bh = Portfolio.buyAndHold(data, fee)
            println("\nFee ${fee * 100}%: ${strat.trades.size} rebalances, ${"%.0f".format(strat.timeInMarket * 100)}% invested on average")
            for ((label, from, to) in listOf(Triple("In-sample (to 2023)", 0L, oosStart), Triple("Out-of-sample (2024 on)", oosStart, Long.MAX_VALUE))) {
                for ((name, r) in listOf("Trend portfolio" to strat, "Buy and hold" to bh)) {
                    val m = Backtest.metrics(slice(r, from, to), 365.0)
                    println("%-24s %-16s total %7.0f%%  per year %6.1f%%  worst drop %6.1f%%  Sharpe %.2f".format(
                        label, name, m.totalReturn * 100, m.cagr * 100, m.maxDrawdown * 100, m.sharpe))
                }
            }
        }
    }
}
