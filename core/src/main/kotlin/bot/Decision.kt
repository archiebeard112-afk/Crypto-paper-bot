package bot

import kotlin.math.floor

data class Position(val qty: Double, val entryPrice: Double)

sealed class Action {
    abstract val reason: String

    /** Buy [notional] dollars at market; [stopPrice] is the stop loss for the new position. */
    data class Buy(val notional: Double, val stopPrice: Double, override val reason: String) : Action()
    data class Sell(override val reason: String) : Action()
    data class Hold(override val reason: String) : Action()
}

/**
 * What the live bot should do right now. Mirrors the backtest rules:
 * signal from completed bars only, position cap, stop loss, and no re-entry
 * after an exit until the next fresh cross up.
 *
 * @param completedBars finished bars, oldest first (the still-forming bar must be excluded)
 * @param lastPrice latest traded price, used for the stop check between bars
 * @param exitedSince returns true if the account sold this symbol at or after the given epoch second
 */
object Decision {
    fun decide(
        s: Settings,
        completedBars: List<Bar>,
        lastPrice: Double,
        position: Position?,
        equity: Double,
        cash: Double,
        exitedSince: (Long) -> Boolean,
    ): Action {
        if (completedBars.size < s.slow) return Action.Hold("Waiting for data: ${completedBars.size} of ${s.slow} bars")
        val signal = Strategy.signal(completedBars.map { it.close }.toDoubleArray(), s.fast, s.slow)
        val on = signal.last() == 1

        if (position != null) {
            val stop = position.entryPrice * (1 - s.stopLossPct)
            if (lastPrice <= stop) return Action.Sell("Stop loss: price ${money(lastPrice)} is at or below ${money(stop)}")
            if (!on) return Action.Sell("Fast average crossed below slow average")
            return Action.Hold("Holding; stop at ${money(stop)}")
        }

        if (!on) return Action.Hold("No buy signal (fast average is below slow average)")
        val crossTime = completedBars[Strategy.lastCrossUp(signal)].time
        if (exitedSince(crossTime)) return Action.Hold("Already exited since the last cross up; waiting for a new one")

        val notional = floor(minOf(equity * s.maxPositionPct, cash) * 100) / 100
        if (notional < 10) return Action.Hold("Not enough cash to buy (${money(notional)})")
        return Action.Buy(notional, lastPrice * (1 - s.stopLossPct), "Fast average is above slow average")
    }

    fun money(v: Double) = "$" + String.format(java.util.Locale.US, "%,.2f", v)
}
