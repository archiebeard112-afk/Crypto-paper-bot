package bot

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfitLossTest {
    private fun close(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    @Test fun realizedAndUnrealized() {
        val fills = listOf(
            Fill("BTC/USD", "buy", 0.01, 50_000.0, 1),
            Fill("BTC/USD", "buy", 0.01, 70_000.0, 2), // average cost now 60,000
            Fill("BTC/USD", "sell", 0.01, 66_000.0, 3), // +60 realized
        )
        val pl = ProfitLossCalc.compute(1_000.0, fills, mapOf("BTC/USD" to 55_000.0))
        close(60.0, pl.realized)
        close(-50.0, pl.unrealized) // 0.01 * (55,000 - 60,000)
        close(10.0, pl.total)
        close(1_010.0, pl.currentValue)
        close(0.01, pl.totalPct)
        close(1_010.0 - 550.0, pl.cash)
    }

    @Test fun ignoresSellingCoinsHeldBeforeTheStart() {
        val fills = listOf(Fill("ETH/USD", "sell", 2.0, 3_000.0, 1))
        val pl = ProfitLossCalc.compute(500.0, fills, emptyMap())
        close(0.0, pl.total)
        assertEquals(emptyList(), pl.holdings)
    }

    @Test fun fullRoundTrip() {
        val fills = listOf(Fill("SOL/USD", "sell", 5.0, 120.0, 2), Fill("SOL/USD", "buy", 5.0, 100.0, 1))
        val pl = ProfitLossCalc.compute(1_000.0, fills, emptyMap())
        close(100.0, pl.realized)
        close(1_100.0, pl.cash)
    }
}
