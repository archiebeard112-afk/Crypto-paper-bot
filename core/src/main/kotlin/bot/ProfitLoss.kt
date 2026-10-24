package bot

import kotlin.math.min

/** One filled order. [qty] is always positive; [side] is "buy" or "sell". */
data class Fill(val symbol: String, val side: String, val qty: Double, val price: Double, val time: Long)

data class Holding(val symbol: String, val qty: Double, val avgCost: Double, val price: Double) {
    val value get() = qty * price
    val unrealized get() = qty * (price - avgCost)
}

data class ProfitLoss(val startingAmount: Double, val realized: Double, val holdings: List<Holding>) {
    val unrealized get() = holdings.sumOf { it.unrealized }
    val total get() = realized + unrealized
    val currentValue get() = startingAmount + total
    val totalPct get() = if (startingAmount > 0) total / startingAmount else 0.0
    val cash get() = currentValue - holdings.sumOf { it.value }
}

object ProfitLossCalc {
    /**
     * Profit and loss of the bot's own trades, using average cost per coin.
     * Only coins bought after the starting point count: selling coins that were already
     * in the account beforehand is ignored, so they can't show up as fake profit.
     *
     * @param fills filled orders since the starting amount was set, in any order
     * @param prices latest price per symbol, for open holdings
     */
    fun compute(startingAmount: Double, fills: List<Fill>, prices: Map<String, Double>): ProfitLoss {
        val qty = mutableMapOf<String, Double>()
        val cost = mutableMapOf<String, Double>() // total cost basis of the coins still held
        var realized = 0.0
        for (f in fills.sortedBy { it.time }) {
            val held = qty[f.symbol] ?: 0.0
            if (f.side == "buy") {
                qty[f.symbol] = held + f.qty
                cost[f.symbol] = (cost[f.symbol] ?: 0.0) + f.qty * f.price
            } else if (held > 0) {
                val sold = min(f.qty, held)
                val avg = cost[f.symbol]!! / held
                realized += sold * (f.price - avg)
                qty[f.symbol] = held - sold
                cost[f.symbol] = avg * (held - sold)
            }
        }
        val holdings = qty.filterValues { it > 1e-9 }.map { (sym, q) ->
            val avg = cost[sym]!! / q
            Holding(sym, q, avg, prices[sym] ?: avg)
        }
        return ProfitLoss(startingAmount, realized, holdings)
    }
}
