package com.archie.cryptobot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bot.Action
import bot.Backtest
import bot.BacktestResult
import bot.Decision
import bot.Fast
import bot.FastSettings
import bot.Metrics
import bot.Portfolio
import bot.ProfitLoss
import bot.Scalp
import bot.ScalpSettings
import bot.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.math.ln

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            "PAPER MONEY ONLY: no real funds are ever traded",
            Modifier.fillMaxWidth().background(Color(0xFF1B5E20)).padding(8.dp),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Bot", "Backtest", "Settings").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (tab) {
                0 -> BotScreen()
                1 -> BacktestScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
fun BotScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(BotStore.isEnabled(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(BotStore.readLog(context)) }
    val s = BotStore.settings(context)
    val hasKeys = KeyVault.load(context) != null

    Text(if (s.mode == "portfolio") "Trend portfolio: BTC + ETH + SOL, daily, 150-day trend, volatility-sized"
        else if (s.mode == "fast") "${s.symbol}  ·  Fast (1-min): +${pct(s.quick.takeProfitPct, 2)} / -${pct(s.quick.stopPct, 2)}, " +
            "max ${s.quick.maxHoldMinutes} min  ·  cap ${pct(s.maxPositionPct)}"
        else if (s.mode == "scalp") "${s.symbol}  ·  Scalp${if (s.scalp.entry == "reversal") " (dip then turn up)" else ""}: RSI<${s.scalp.rsiBuy.toInt()}, +${pct(s.scalp.takeProfitPct, 1)} / -${pct(s.scalp.stopPct, 1)}, " +
            "max ${s.scalp.maxHoldBars * 15 / 60.0}h  ·  cap ${pct(s.maxPositionPct)}"
        else "${s.symbol}  ·  Trend: SMA ${s.fast}/${s.slow} days  ·  cap ${pct(s.maxPositionPct)}  ·  stop ${pct(s.stopLossPct)}",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    if (!hasKeys) {
        Card { Text("Add your Alpaca paper API keys in Settings to start.", Modifier.padding(16.dp)) }
        return
    }

    var refresh by remember { mutableIntStateOf(0) }
    ProfitLossCard(refresh)
    Spacer(Modifier.height(16.dp))
    VolatilityCard()
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bot running", fontWeight = FontWeight.Bold)
            Text(if (s.mode == "fast") "Fast mode: checks every 30 seconds and shows a notification while on (uses more battery)"
                else "Checks every 15 minutes, even when the app is closed", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            Bot.schedule(context, it)
            BotStore.log(context, if (it) "Bot started" else "Bot stopped")
            log = BotStore.readLog(context)
        })
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            status = try {
                val snap = withContext(Dispatchers.IO) { Bot.runOnce(context, trade = false) }
                if (s.mode == "portfolio") "Paper equity: ${Decision.money(snap.equity)}  (cash ${Decision.money(snap.cash)})\n" +
                    snap.action.reason
                else buildString {
                    appendLine("Paper equity: ${Decision.money(snap.equity)}  (cash ${Decision.money(snap.cash)})")
                    appendLine("${s.symbol}: ${Decision.money(snap.price)}")
                    snap.position?.let { appendLine("Holding ${"%.6f".format(it.qty)} @ ${Decision.money(it.entryPrice)}") }
                        ?: appendLine("No position")
                    val verb = when (snap.action) { is Action.Buy -> "BUY"; is Action.Sell -> "SELL"; is Action.Hold -> "HOLD" }
                    append("Next step: $verb. ${snap.action.reason}")
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            busy = false
        }
    }) { Text(if (busy) "Checking…" else "Check now (no trade)") }
    status?.let { Card(Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(it, Modifier.padding(16.dp)) } }
    if (s.mode == "fast") BotStore.status(context)?.let {
        val at = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(BotStore.statusTime(context)))
        Text("Last fast check ($at): $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { log = BotStore.readLog(context); refresh++ }) { Text("Refresh") }
    }
    if (log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    log.forEach {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(fmt.format(Date(it.time)), style = MaterialTheme.typography.labelSmall)
            Text(it.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun VolatilityCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<String, Fast.Volatility>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Market volatility", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            rows = withContext(Dispatchers.IO) {
                                val api = AlpacaClient(KeyVault.load(context))
                                val since = Instant.now().minusSeconds(7L * 86_400)
                                BotStore.symbols.map { sym -> sym to Fast.volatility(api.bars(sym, "1Min", since).map { it.close }.toDoubleArray()) }
                            }
                            error = null
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Loading…" else if (rows == null) "Check" else "Refresh") }
            }
            Text("Typical price move in an hour right now, versus a normal hour over the past week.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text("Couldn't load: $it", color = MaterialTheme.colorScheme.error) }
            rows?.let { list ->
                Spacer(Modifier.height(8.dp))
                TableRow(listOf("", "Last hour", "Normal", "Last 24h", "Now"), header = true)
                list.forEach { (sym, v) ->
                    TableRow(listOf(sym, pct(v.lastHour, 2), pct(v.normalHour, 2), pct(v.lastDay, 1), v.label),
                        color = when (v.label) { "Busy" -> Color(0xFFEF6C00); "Calm" -> Color(0xFF90A4AE); else -> Color(0xFF66BB6A) })
                }
            }
        }
    }
}

private data class Overview(val pl: ProfitLoss?, val accountEquity: Double, val lastEquity: Double, val history: List<Pair<Long, Double>>)

@Composable
fun ProfitLossCard(refresh: Int) {
    val context = LocalContext.current
    var overview by remember { mutableStateOf<Overview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        try {
            overview = withContext(Dispatchers.IO) {
                val api = AlpacaClient(KeyVault.load(context))
                val acct = api.account()
                Overview(Bot.profitLoss(context, api), acct.getDouble("equity"), acct.optDouble("last_equity", 0.0), api.portfolioHistory("1M"))
            }
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Profit and loss", style = MaterialTheme.typography.titleMedium)
            val o = overview
            when {
                error != null -> Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                o == null -> Text("Loading…")
                o.pl == null -> Text("Set a starting amount in Settings to track the bot's profit and loss.",
                    style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val pl = o.pl
                    val since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(BotStore.startTime(context)))
                    Text(signedMoney(pl.total) + "  (" + (if (pl.total >= 0) "+" else "") + pct(pl.totalPct, 1) + ")",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = gainColor(pl.total))
                    Text("Started with ${Decision.money(pl.startingAmount)} on $since, now ${Decision.money(pl.currentValue)}")
                    Text("Closed trades ${signedMoney(pl.realized)}  ·  Open position ${signedMoney(pl.unrealized)}",
                        style = MaterialTheme.typography.bodySmall)
                    pl.holdings.forEach {
                        Text("Holding ${"%.6f".format(it.qty)} ${it.symbol.substringBefore('/')} worth ${Decision.money(it.value)} " +
                            "(bought at ${Decision.money(it.avgCost)})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (o != null) {
                Spacer(Modifier.height(12.dp))
                val today = o.accountEquity - o.lastEquity
                Text("Whole paper account: ${Decision.money(o.accountEquity)}" +
                    (if (o.lastEquity > 0) ", today ${signedMoney(today)}" else ""), style = MaterialTheme.typography.bodySmall)
                if (o.history.size > 1) {
                    Text("Last month", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    LineChart(o.history.map { it.second }, gainColor(o.history.last().second - o.history.first().second))
                }
            }
        }
    }
}

@Composable
private fun LineChart(values: List<Double>, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val p = Offset(size.width * i / (values.size - 1), (size.height * (1 - (v - lo) / span)).toFloat())
            prev?.let { drawLine(color, it, p, strokeWidth = 3f) }
            prev = p
        }
    }
}

private fun gainColor(v: Double) = if (v >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)

private fun signedMoney(v: Double) = (if (v >= 0) "+" else "-") + Decision.money(kotlin.math.abs(v))

private data class BacktestRow(val name: String, val result: BacktestResult, val metrics: Metrics)

@Composable
fun BacktestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<BacktestRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val s = BotStore.settings(context)

    val scalp = s.mode == "scalp"
    Text(if (s.mode == "fast") "Replays fast mode on the last 7 days of 1-minute ${s.symbol} candles from Alpaca. If the stop and " +
            "take-profit are both touched in the same minute, it assumes the stop hit first. Every fill pays a ${pct(s.feePct, 2)} fee."
        else if (s.mode == "portfolio") "Replays the trend portfolio on daily BTC, ETH and SOL history from Alpaca and compares " +
            "it with buying and holding. Each trade pays a ${pct(s.feePct, 2)} fee."
        else if (scalp) "Replays scalp mode on the last 90 days of 15-minute ${s.symbol} candles from Alpaca and compares it with " +
            "buying and holding. Take-profits fill inside a candle; stops and time limits fill at the candle's close, " +
            "like the live bot. Every fill pays a ${pct(s.feePct, 2)} fee."
        else "Replays the strategy on daily ${s.symbol} history from Alpaca and compares it with simply buying and holding. " +
            "Fills happen at the next day's open and pay a ${pct(s.feePct, 2)} fee per trade.",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Button(enabled = !busy, onClick = {
        busy = true
        error = null
        scope.launch {
            try {
                rows = withContext(Dispatchers.IO) {
                    val api = AlpacaClient(KeyVault.load(context))
                    if (s.mode == "fast") {
                        val bars = api.bars(s.symbol, "1Min", Instant.now().minusSeconds(7L * 86_400))
                        return@withContext listOf(
                            "Buy and hold" to Fast.buyAndHold(bars, s),
                            "Your settings, if trading were free" to Fast.backtest(bars, s.copy(feePct = 0.0), s.quick),
                            (if (s.quick.minHourlyMove > 0) "Same, without the volatility filter" else "Same, only when 1-hour move > 1%") to
                                Fast.backtest(bars, s, s.quick.copy(minHourlyMove = if (s.quick.minHourlyMove > 0) 0.0 else 0.01)),
                            "Your settings" to Fast.backtest(bars, s, s.quick),
                        ).map { (n, r) -> BacktestRow(n, r, Backtest.metrics(r, 365.0 * 1440)) }
                    }
                    if (s.mode == "portfolio") {
                        val data = Portfolio.COINS.associateWith { api.dailyBars(it, Instant.parse("2015-01-01T00:00:00Z")) }
                        return@withContext listOf(
                            "Buy and hold, equal thirds" to Portfolio.buyAndHold(data, s.feePct),
                            "Buy and hold BTC only" to Portfolio.buyAndHold(mapOf("BTC/USD" to data["BTC/USD"]!!), s.feePct),
                            "Trend portfolio, if trading were free" to Portfolio.backtest(data, 0.0),
                            "Trend portfolio" to Portfolio.backtest(data, s.feePct),
                        ).map { (n, r) -> BacktestRow(n, r, Backtest.metrics(r, 365.0)) }
                    }
                    if (scalp) {
                        val bars = api.bars(s.symbol, "15Min", Instant.now().minusSeconds(90L * 86_400))
                        return@withContext listOf(
                            "Buy and hold" to Scalp.buyAndHold(bars, s),
                            "Your settings, if trading were free" to Scalp.backtest(bars, s.copy(feePct = 0.0)),
                            (if (s.scalp.entry == "reversal") "Same, but buy the dip itself" else "Same, but wait for the turn up") to
                                Scalp.backtest(bars, s.copy(scalp = s.scalp.copy(entry = if (s.scalp.entry == "reversal") "dip" else "reversal"))),
                            "Your settings" to Scalp.backtest(bars, s),
                        ).map { (n, r) -> BacktestRow(n, r, Backtest.metrics(r, 365.0 * 96)) }
                    }
                    val bars = api.dailyBars(s.symbol, Instant.parse("2015-01-01T00:00:00Z"))
                    listOf(
                        "Buy and hold" to Backtest.buyAndHold(bars, s),
                        "Strategy, all-in, no stop" to Backtest.run(bars, s.copy(maxPositionPct = 1.0, stopLossPct = 1.0)),
                        "Strategy, all-in + stop" to Backtest.run(bars, s.copy(maxPositionPct = 1.0)),
                        "Your settings" to Backtest.run(bars, s),
                    ).map { (n, r) -> BacktestRow(n, r, Backtest.metrics(r, 365.0)) }
                }
            } catch (e: Exception) {
                error = e.message
            }
            busy = false
        }
    }) { Text(if (busy) "Running…" else "Run backtest") }
    error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

    val r = rows ?: return
    val first = Instant.ofEpochSecond(r[0].result.times.first()).atOffset(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochSecond(r[0].result.times.last()).atOffset(ZoneOffset.UTC).toLocalDate()
    Spacer(Modifier.height(16.dp))
    Text("$first to $last, starting with $100,000", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    val colors = listOf(Color(0xFF9E9E9E), Color(0xFF42A5F5), Color(0xFFAB47BC), Color(0xFF2E7D32))
    EquityChart(r.map { it.result }, colors)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        TableRow(listOf("", "Return", "Worst drop", "Trades", "Win rate", "Stops", "Invested"), header = true)
        r.forEachIndexed { i, row ->
            val m = row.metrics
            TableRow(listOf(row.name, pct(m.totalReturn), pct(m.maxDrawdown), "${m.trades}",
                if (m.winRate.isNaN()) "-" else pct(m.winRate), "${m.stopOuts}", pct(m.timeInMarket)), color = colors[i])
        }
    }
    Spacer(Modifier.height(12.dp))
    val bh = r[0].metrics
    val mine = r[3].metrics
    val verdict = if (mine.totalReturn >= bh.totalReturn) "beat" else "trailed"
    Text((if (s.mode == "portfolio") "The trend portfolio" else "Your settings") + " $verdict buy-and-hold over this period (${pct(mine.totalReturn)} vs ${pct(bh.totalReturn)}), " +
        "with a worst drop of ${pct(mine.maxDrawdown)} vs ${pct(bh.maxDrawdown)}. Past results don't predict future ones, " +
        "and tuning settings until the backtest looks good usually makes live results worse.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, color: Color? = null) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c ->
            Row(Modifier.width(if (i == 0) 190.dp else 84.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i == 0 && color != null) {
                    Spacer(Modifier.size(10.dp).background(color))
                    Spacer(Modifier.width(6.dp))
                }
                Text(c, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                    fontFamily = if (i == 0) FontFamily.Default else FontFamily.Monospace)
            }
        }
    }
}

/** Equity curves on a log scale, so equal percentage moves look the same size. */
@Composable
private fun EquityChart(results: List<BacktestResult>, colors: List<Color>) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val all = results.flatMap { it.equity.asList() }.map { ln(it) }
        val lo = all.min()
        val hi = all.max()
        val n = results[0].equity.size
        for (k in 0..4) {
            val y = size.height * k / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        results.forEachIndexed { idx, r ->
            var prev: Offset? = null
            r.equity.forEachIndexed { i, v ->
                val p = Offset(size.width * i / (n - 1), (size.height * (1 - (ln(v) - lo) / (hi - lo))).toFloat())
                prev?.let { drawLine(colors[idx], it, p, strokeWidth = 3f) }
                prev = p
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val current = BotStore.settings(context)
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var hasKeys by remember { mutableStateOf(KeyVault.load(context) != null) }
    var symbol by remember { mutableStateOf(current.symbol) }
    var fast by remember { mutableStateOf(current.fast.toString()) }
    var slow by remember { mutableStateOf(current.slow.toString()) }
    var cap by remember { mutableStateOf((current.maxPositionPct * 100).toInt().toString()) }
    var stop by remember { mutableStateOf((current.stopLossPct * 100).toInt().toString()) }
    var mode by remember { mutableStateOf(current.mode) }
    var rsi by remember { mutableStateOf(current.scalp.rsiBuy.toInt().toString()) }
    var tp by remember { mutableStateOf(trim(current.scalp.takeProfitPct * 100)) }
    var sStop by remember { mutableStateOf(trim(current.scalp.stopPct * 100)) }
    var hold by remember { mutableStateOf(trim(current.scalp.maxHoldBars / 4.0)) }
    var trendFilter by remember { mutableStateOf(current.scalp.trendFilter) }
    var entry by remember { mutableStateOf(current.scalp.entry) }
    var fTp by remember { mutableStateOf(trim(current.quick.takeProfitPct * 100)) }
    var fSl by remember { mutableStateOf(trim(current.quick.stopPct * 100)) }
    var fHold by remember { mutableStateOf(current.quick.maxHoldMinutes.toString()) }
    var fVol by remember { mutableStateOf(trim(current.quick.minHourlyMove * 100)) }
    var fEntry by remember { mutableStateOf(current.quick.entry) }
    var message by remember { mutableStateOf<String?>(null) }
    var amountMessage by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf(BotStore.startingAmount(context).takeIf { it > 0 }?.toInt()?.toString() ?: "") }

    Text("Alpaca paper keys", style = MaterialTheme.typography.titleMedium)
    Text("Sign up free at alpaca.markets, open your Paper account, and generate API keys there. " +
        "Keys are encrypted on this phone and only sent to Alpaca's paper endpoint.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (hasKeys) {
        Text("Keys saved.")
        OutlinedButton(onClick = { KeyVault.clear(context); Bot.schedule(context, false); hasKeys = false }) { Text("Remove keys") }
    } else {
        OutlinedTextField(keyId, { keyId = it.trim() }, label = { Text("API key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it.trim() }, label = { Text("Secret key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = keyId.isNotEmpty() && secret.isNotEmpty(), onClick = {
            KeyVault.save(context, ApiKeys(keyId, secret))
            keyId = ""; secret = ""; hasKeys = true
            message = "Keys saved. Use \"Check now\" on the Bot tab to test them."
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Save keys") }
    }

    Spacer(Modifier.height(24.dp))
    Text("Starting amount", style = MaterialTheme.typography.titleMedium)
    Text("The bot trades with this much of your paper money (plus whatever it gains or loses), and profit and loss " +
        "is measured from it, starting when you save. To change the paper account's own balance, use Reset on your " +
        "Paper account page at alpaca.markets.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Amount ($)", amount, Modifier.weight(1f)) { amount = it }
        Button(onClick = {
            val v = amount.toDoubleOrNull() ?: 0.0
            amountMessage = if (v < 10) "Enter at least $10." else {
                BotStore.setStartingAmount(context, v)
                "Starting amount set to ${Decision.money(v)}. Profit and loss counts from now."
            }
        }) { Text("Set") }
    }
    val savedAmount = BotStore.startingAmount(context)
    if (savedAmount > 0) Text("Current: ${Decision.money(savedAmount)}", style = MaterialTheme.typography.bodySmall)
    amountMessage?.let { Text(it, Modifier.padding(top = 4.dp)) }

    Spacer(Modifier.height(24.dp))
    Text("Strategy", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "portfolio", onClick = { mode = "portfolio" }, label = { Text("Trend portfolio") })
        FilterChip(selected = mode == "trend", onClick = { mode = "trend" }, label = { Text("Trend (one coin)") })
        FilterChip(selected = mode == "scalp", onClick = { mode = "scalp" }, label = { Text("Scalp (15-min)") })
        FilterChip(selected = mode == "fast", onClick = { mode = "fast" }, label = { Text("Fast (1-min)") })
    }
    if (mode == "portfolio") Text("Best model from testing. Splits the bot's money into thirds for BTC, ETH and SOL. Holds a coin " +
        "only while its price is above its 150-day average, and holds less of it when it's swinging wildly. Rebalances when a " +
        "holding drifts more than 10% from target. Settings are fixed to what was tested.", style = MaterialTheme.typography.bodySmall)
    else if (mode == "fast") Text("Many quick trades on 1-minute candles. Sells at your take-profit, your stop-loss, or a time " +
        "limit, whichever comes first. Each round trip costs about 0.5% in Alpaca fees, so the take-profit must be well above " +
        "that to come out ahead. In testing on real data, no fast setting made money after fees.", style = MaterialTheme.typography.bodySmall)
    else Text(if (mode == "scalp") "Buys sharp dips on 15-minute candles (RSI below your level) and sells quickly at a small " +
            "gain, a small loss, or a time limit. Trades often, so fees matter a lot."
        else "Buys when the fast moving average of daily closes rises above the slow one, sells when it drops below. Trades a few times a year.",
        style = MaterialTheme.typography.bodySmall)
    if (mode != "portfolio") Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BotStore.symbols.forEach { FilterChip(selected = symbol == it, onClick = { symbol = it }, label = { Text(it) }) }
    }
    if (mode == "scalp") {
        Text("When to buy", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = entry == "dip", onClick = { entry = "dip" }, label = { Text("Buy the dip") })
            FilterChip(selected = entry == "reversal", onClick = { entry = "reversal" }, label = { Text("Dip then turn up") })
        }
        Text(if (entry == "reversal") "Waits until, within 2 hours of a dip, the 9-candle average crosses above the 21-candle " +
                "average: a sign the price has started rising again."
            else "Buys as soon as RSI drops below your level.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Buy when RSI below", rsi, Modifier.weight(1f)) { rsi = it }
            NumberField("Max hold (hours)", hold, Modifier.weight(1f), decimal = true) { hold = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Take profit %", tp, Modifier.weight(1f), decimal = true) { tp = it }
            NumberField("Stop loss %", sStop, Modifier.weight(1f), decimal = true) { sStop = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Only buy dips while the 2-day trend is up", Modifier.weight(1f))
            Switch(checked = trendFilter, onCheckedChange = { trendFilter = it })
        }
        NumberField("Max position %", cap, Modifier.fillMaxWidth()) { cap = it }
    } else if (mode == "fast") {
        Text("When to buy", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = fEntry == "breakout", onClick = { fEntry = "breakout" }, label = { Text("Breakout") })
            FilterChip(selected = fEntry == "dip_turn_up", onClick = { fEntry = "dip_turn_up" }, label = { Text("Dip then turn up") })
        }
        Text(if (fEntry == "breakout") "Buys when the price makes a new 20-minute high while the short-term trend is up."
            else "Buys when, within 10 minutes of a sharp dip, the 9-minute average crosses above the 21-minute one.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Take profit %", fTp, Modifier.weight(1f), decimal = true) { fTp = it }
            NumberField("Stop loss %", fSl, Modifier.weight(1f), decimal = true) { fSl = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Max hold (minutes)", fHold, Modifier.weight(1f)) { fHold = it }
            NumberField("Min 1-hour move %", fVol, Modifier.weight(1f), decimal = true) { fVol = it }
        }
        NumberField("Max position %", cap, Modifier.fillMaxWidth()) { cap = it }
        val size = (BotStore.startingAmount(context).takeIf { it > 0 } ?: 1000.0) * ((cap.toDoubleOrNull() ?: 0.0) / 100)
        val tpV = (fTp.toDoubleOrNull() ?: 0.0) / 100
        val slV = (fSl.toDoubleOrNull() ?: 0.0) / 100
        val fee = 0.0025
        Text("On a ${Decision.money(size)} trade: a win makes about ${Decision.money(size * (tpV - 2 * fee))} after fees, " +
            "a loss costs about ${Decision.money(size * (slV + 2 * fee))}.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (tpV <= 2 * fee) Text("This take-profit is smaller than the fees, so even winning trades lose money.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("\"Min 1-hour move\" skips calm markets: set 0 to trade all the time, 1 to wait until a coin typically moves 1% an hour.",
            style = MaterialTheme.typography.bodySmall)
    } else if (mode == "trend") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Fast (days)", fast, Modifier.weight(1f)) { fast = it }
            NumberField("Slow (days)", slow, Modifier.weight(1f)) { slow = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Max position %", cap, Modifier.weight(1f)) { cap = it }
            NumberField("Stop loss %", stop, Modifier.weight(1f)) { stop = it }
        }
    }
    Button(onClick = {
        message = try {
            val s = Settings(symbol = symbol, fast = fast.toInt(), slow = slow.toInt(),
                maxPositionPct = cap.toDouble() / 100, stopLossPct = stop.toDouble() / 100, mode = mode,
                scalp = ScalpSettings(rsiBuy = rsi.toDouble(), takeProfitPct = tp.toDouble() / 100, stopPct = sStop.toDouble() / 100,
                    maxHoldBars = maxOf(1, Math.round(hold.toDouble() * 4).toInt()), trendFilter = trendFilter, entry = entry),
                quick = FastSettings(takeProfitPct = fTp.toDouble() / 100, stopPct = fSl.toDouble() / 100,
                    maxHoldMinutes = fHold.toInt(), minHourlyMove = (fVol.toDoubleOrNull() ?: 0.0) / 100, entry = fEntry))
            BotStore.saveSettings(context, s)
            if (BotStore.isEnabled(context)) Bot.schedule(context, true) // start or stop the fast loop to match the mode
            "Saved. The bot uses these from its next check."
        } catch (e: Exception) {
            "Not saved: ${e.message ?: "check the numbers"}"
        }
    }, modifier = Modifier.padding(top = 8.dp)) { Text("Save strategy") }
    message?.let { Text(it, Modifier.padding(top = 8.dp)) }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, { input ->
        val cleaned = input.filter { it.isDigit() || (decimal && it == '.') }
        if (cleaned.count { it == '.' } <= 1) onChange(cleaned)
    }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = modifier)
}

/** 1.5 -> "1.5", 2.0 -> "2" */
private fun trim(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0')

fun pct(v: Double, decimals: Int = 0) = "%.${decimals}f%%".format(v * 100)
