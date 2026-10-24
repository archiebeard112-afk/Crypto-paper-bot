package com.archie.cryptobot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import bot.Action
import bot.Decision
import bot.Portfolio
import bot.Position
import bot.ProfitLoss
import bot.ProfitLossCalc
import bot.Scalp
import bot.Settings
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

object Bot {
    private const val DAY = 86_400L

    data class Snapshot(
        val equity: Double,
        val cash: Double,
        val price: Double,
        val position: Position?,
        val action: Action,
    )

    /**
     * One pass of the bot: read the paper account and prices, decide, and (if [trade]) place paper orders.
     * Also makes sure a resting exit order (stop-loss or take-profit) exists while a position is open.
     */
    fun runOnce(context: Context, trade: Boolean): Snapshot {
        val keys = KeyVault.load(context) ?: error("Add your Alpaca paper API keys in Settings first")
        val s = BotStore.settings(context)
        val api = AlpacaClient(keys)

        val account = api.account() // throws unless this is a paper account
        val equity = account.getDouble("equity")
        val cash = account.optDouble("non_marginable_buying_power", account.getDouble("cash"))
        if (s.mode == "portfolio") return runPortfolio(context, api, equity, cash, trade)
        val now = Instant.now().epochSecond
        val scalp = s.mode == "scalp"
        val bars = if (scalp) {
            api.bars(s.symbol, "15Min", Instant.ofEpochSecond(now - 4 * DAY))
                .filter { it.time + Scalp.BAR_SECONDS <= now } // only finished candles
        } else {
            api.dailyBars(s.symbol, Instant.ofEpochSecond(now - (s.slow * 3L / 2 + 10) * DAY))
                .filter { it.time + DAY <= now }
        }
        val price = api.latestPrice(s.symbol)
        val pos = api.position(s.symbol)
        val position = pos?.let { Position(it.getDouble("qty"), it.getDouble("avg_entry_price")) }

        // With a starting amount set, the bot only trades with that money plus its own profit or loss.
        val pl = profitLoss(context, api)
        val botEquity = pl?.let { minOf(it.currentValue, equity) } ?: equity
        val botCash = pl?.let { minOf(it.cash, cash).coerceAtLeast(0.0) } ?: cash

        val action = if (scalp) {
            val entryTime = if (pos == null) null else api.orders(s.symbol, "closed")
                .firstOrNull { it.optString("side") == "buy" && !it.isNull("filled_at") }
                ?.let { Instant.parse(it.getString("filled_at")).epochSecond }
            Scalp.decide(s, bars, price, position, entryTime, now, botEquity, botCash)
        } else {
            Decision.decide(s, bars, price, position, botEquity, botCash) { since ->
                api.orders(s.symbol, "closed", Instant.ofEpochSecond(since)).any {
                    it.optString("side") == "sell" && it.optDouble("filled_qty", 0.0) > 0
                }
            }
        }
        if (!trade) return Snapshot(equity, cash, price, position, action)

        when (action) {
            is Action.Buy -> {
                api.buyNotional(s.symbol, action.notional)
                record(context, "BUY ${Decision.money(action.notional)} of ${s.symbol} at about ${Decision.money(price)}. ${action.reason}", notify = true)
                if (scalp) {
                    // Rest the take-profit as soon as the buy fills, so a quick bounce isn't missed between checks.
                    repeat(10) {
                        Thread.sleep(1_000)
                        val filled = api.position(s.symbol) ?: return@repeat
                        ensureExitOrder(api, s, filled)
                        return Snapshot(equity, cash, price, position, action)
                    }
                }
            }
            is Action.Sell -> {
                api.orders(s.symbol, "open").forEach { api.cancelOrder(it.getString("id")) }
                api.closePosition(s.symbol)
                record(context, "SELL all ${s.symbol} at about ${Decision.money(price)}. ${action.reason}", notify = true)
            }
            is Action.Hold -> {
                if (pos != null) ensureExitOrder(api, s, pos)
                record(context, "${s.symbol} ${Decision.money(price)}: ${action.reason}", notify = false)
            }
        }
        return Snapshot(equity, cash, price, position, action)
    }

    /**
     * Trend portfolio: one third of the bot's money per coin, each sized by [Portfolio.targetExposure]
     * on finished daily candles and rebalanced only when it drifts more than the band.
     */
    private fun runPortfolio(context: Context, api: AlpacaClient, equity: Double, cash: Double, trade: Boolean): Snapshot {
        val now = Instant.now().epochSecond
        val pl = profitLoss(context, api)
        val botEquity = pl?.let { minOf(it.currentValue, equity) } ?: equity
        var botCash = pl?.let { minOf(it.cash, cash).coerceAtLeast(0.0) } ?: cash
        val prices = api.latestPrices(Portfolio.COINS)
        val slice = botEquity / Portfolio.COINS.size
        val lines = mutableListOf<String>()
        var traded = false
        for (coin in Portfolio.COINS) {
            val closes = api.dailyBars(coin, Instant.ofEpochSecond(now - (Portfolio.TREND_DAYS + 40) * Portfolio.DAY))
                .filter { it.time + Portfolio.DAY <= now }.map { it.close }.toDoubleArray()
            val price = prices[coin] ?: continue
            val pos = api.position(coin)
            val held = pos?.getDouble("qty") ?: 0.0
            val target = Portfolio.targetExposure(closes)
            val amount = Portfolio.tradeAmount(slice, held * price, target)
            val name = coin.substringBefore('/')
            val status = "$name: holding ${pct(held * price / slice)} of its share, target ${pct(target)}"
            if (amount == 0.0 || !trade) {
                lines += status + when {
                    amount > 0 -> " → buy ${Decision.money(amount)}"
                    amount < 0 -> " → sell ${Decision.money(-amount)}"
                    else -> ""
                }
                continue
            }
            if (amount > 0) {
                val spend = kotlin.math.floor(minOf(amount, botCash) * 100) / 100
                if (spend < 10) { lines += "$status (not enough cash to buy)"; continue }
                api.orders(coin, "open").filter { it.optString("side") == "sell" }.forEach { api.cancelOrder(it.getString("id")) }
                api.buyNotional(coin, spend)
                botCash -= spend
                lines += "$status → BOUGHT ${Decision.money(spend)}"
            } else {
                api.orders(coin, "open").forEach { api.cancelOrder(it.getString("id")) }
                if (target == 0.0) {
                    api.closePosition(coin)
                    lines += "$status → SOLD all (price fell below its 150-day average)"
                } else {
                    val qty = kotlin.math.floor(minOf(held, -amount / price) * 1e6) / 1e6
                    if (qty > 0) api.marketSell(coin, "%.6f".format(java.util.Locale.US, qty))
                    lines += "$status → SOLD ${Decision.money(-amount)}"
                }
            }
            traded = true
        }
        val summary = lines.joinToString("\n")
        if (trade) record(context, "Trend portfolio\n$summary", notify = traded)
        return Snapshot(equity, cash, prices["BTC/USD"] ?: 0.0, null, Action.Hold(summary))
    }

    private fun pct(v: Double) = "%.0f%%".format(v * 100)

    /** Profit and loss since the starting amount was set, or null if none is set. */
    fun profitLoss(context: Context, api: AlpacaClient): ProfitLoss? {
        val start = BotStore.startingAmount(context)
        if (start <= 0) return null
        val fills = api.filledOrdersSince(Instant.ofEpochMilli(BotStore.startTime(context)))
        val net = fills.groupBy { it.symbol }.filterValues { fs -> fs.sumOf { if (it.side == "buy") it.qty else -it.qty } > 1e-9 }
        val prices = if (net.isEmpty()) emptyMap() else api.latestPrices(net.keys.toList())
        return ProfitLossCalc.compute(start, fills, prices)
    }

    /**
     * Keeps one resting exit order at Alpaca while a position is open: a take-profit limit in scalp mode,
     * a stop-limit in trend mode. Any other open sell order for the coin is cancelled first, since it
     * would hold the same coins.
     */
    private fun ensureExitOrder(api: AlpacaClient, s: Settings, pos: JSONObject) {
        val scalp = s.mode == "scalp"
        val wanted = if (scalp) "limit" else "stop_limit"
        val sells = api.orders(s.symbol, "open").filter { it.optString("side") == "sell" }
        if (sells.any { it.optString("type") == wanted }) return
        sells.forEach { api.cancelOrder(it.getString("id")) }
        if (sells.isNotEmpty()) Thread.sleep(1_000) // let the cancels release the coins
        val qty = (if (sells.isEmpty()) pos.optString("qty_available", pos.getString("qty")) else pos.getString("qty"))
        if ((qty.toDoubleOrNull() ?: 0.0) <= 0) return
        val entry = pos.getDouble("avg_entry_price")
        if (scalp) {
            api.limitSell(s.symbol, qty, entry * (1 + s.scalp.takeProfitPct))
        } else {
            // The limit sits 2% under the stop so the order still fills in a fast drop. The bot also
            // checks the stop itself every run, in case the price gaps past the limit.
            val stop = entry * (1 - s.stopLossPct)
            api.stopLimitSell(s.symbol, qty, stop, stop * 0.98)
        }
    }

    fun record(context: Context, text: String, notify: Boolean) {
        BotStore.log(context, text)
        if (notify) notify(context, text)
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("trades", "Paper trades", NotificationManager.IMPORTANCE_DEFAULT))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val n = NotificationCompat.Builder(context, "trades")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Paper trade")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100_000).toInt(), n)
    }

    fun schedule(context: Context, on: Boolean) {
        BotStore.setEnabled(context, on)
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork("bot")
            return
        }
        val req = PeriodicWorkRequestBuilder<BotWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("bot", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class BotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BotStore.isEnabled(applicationContext)) return Result.success()
        return try {
            withContext(Dispatchers.IO) { Bot.runOnce(applicationContext, trade = true) }
            Result.success()
        } catch (e: NotPaperException) {
            Bot.schedule(applicationContext, false)
            Bot.record(applicationContext, "Stopped: ${e.message}", notify = true)
            Result.failure()
        } catch (e: Exception) {
            BotStore.log(applicationContext, "Error: ${e.message}")
            Result.retry()
        }
    }
}
