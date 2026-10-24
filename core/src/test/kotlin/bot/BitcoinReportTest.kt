package bot

import java.time.Instant
import kotlin.test.Test

/** Prints the strategy vs buy-and-hold on monthly Bitcoin closes (2012 onward). Informational, not a pass/fail check. */
class BitcoinReportTest {
    @Test fun printBitcoinReport() {
        val btc = loadCsv("btc_monthly.csv")
        // Monthly bars, so the windows are in months: roughly the daily 20/100-ish idea at a coarser scale.
        val s = Settings(fast = 2, slow = 10, maxPositionPct = 0.5, stopLossPct = 0.15)
        val rows = listOf(
            "Buy and hold" to Backtest.buyAndHold(btc, s),
            "Crossover, all-in, no stop" to Backtest.run(btc, s.copy(maxPositionPct = 1.0, stopLossPct = 1.0)),
            "Crossover, all-in, 15% stop" to Backtest.run(btc, s.copy(maxPositionPct = 1.0)),
            "Crossover, 50% cap + 15% stop" to Backtest.run(btc, s),
        )
        println("BTC monthly ${Instant.ofEpochSecond(rows[0].second.times.first())} to ${Instant.ofEpochSecond(btc.last().time)}")
        println("%-32s %10s %8s %8s %7s %7s %6s %7s".format("", "total", "CAGR", "max DD", "Sharpe", "trades", "stops", "in mkt"))
        for ((name, r) in rows) {
            val m = Backtest.metrics(r, 12.0)
            println("%-32s %9.0f%% %7.1f%% %7.1f%% %7.2f %7d %6d %6.0f%%".format(
                name, m.totalReturn * 100, m.cagr * 100, m.maxDrawdown * 100, m.sharpe, m.trades, m.stopOuts, m.timeInMarket * 100))
        }
    }
}
