package bot

import java.io.File
import java.time.Instant
import kotlin.test.Test

/**
 * Scalp backtest on real 15-minute candles. Reads Binance kline CSVs from the folder in
 * SCALP_DATA_DIR (one subfolder per symbol); the "Scalp research" workflow downloads them.
 * Skipped when the variable is not set.
 */
class ScalpReportTest {
    private fun load(dir: File): List<Bar> = dir.listFiles { f -> f.name.endsWith(".csv") }!!.sorted().flatMap { f ->
        f.readLines().filter { it.isNotBlank() && it[0].isDigit() }.map {
            val c = it.split(",")
            val t = c[0].toLong().let { v -> if (v > 100_000_000_000_000L) v / 1_000_000 else v / 1000 }
            Bar(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble())
        }
    }.distinctBy { it.time }.sortedBy { it.time }

    @Test fun printScalpReport() {
        val root = System.getenv("SCALP_DATA_DIR")?.let(::File) ?: return
        val base = Settings(mode = "scalp", maxPositionPct = 1.0)
        val variants = listOf(
            "Default (RSI<30, +1.5% / -1.5%, 6h, trend filter)" to base,
            "No trend filter" to base.copy(scalp = base.scalp.copy(trendFilter = false)),
            "Deeper dips (RSI<20)" to base.copy(scalp = base.scalp.copy(rsiBuy = 20.0)),
            "Wider (+3% / -3%, 12h)" to base.copy(scalp = ScalpSettings(takeProfitPct = 0.03, stopPct = 0.03, maxHoldBars = 48)),
            "Tighter (+0.8% / -0.8%)" to base.copy(scalp = ScalpSettings(takeProfitPct = 0.008, stopPct = 0.008)),
            "Default at 0.10% fee" to base.copy(feePct = 0.001),
            "Default with zero fees" to base.copy(feePct = 0.0),
        )
        for (dir in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val bars = load(dir)
            val bh = Scalp.buyAndHold(bars, base)
            println("\n${dir.name}: ${bars.size} candles, ${Instant.ofEpochSecond(bh.times.first())} to ${Instant.ofEpochSecond(bh.times.last())}")
            println("%-50s %9s %9s %7s %8s %7s".format("", "return", "worst", "trades", "win rate", "in mkt"))
            val ppy = 365.0 * 96
            fun row(name: String, r: BacktestResult) {
                val m = Backtest.metrics(r, ppy)
                println("%-50s %8.1f%% %8.1f%% %7d %7.0f%% %6.0f%%".format(name, m.totalReturn * 100, m.maxDrawdown * 100,
                    m.trades, if (m.winRate.isNaN()) 0.0 else m.winRate * 100, m.timeInMarket * 100))
            }
            row("Buy and hold", bh)
            for ((name, s) in variants) row(name, Scalp.backtest(bars, s))
        }
    }
}
