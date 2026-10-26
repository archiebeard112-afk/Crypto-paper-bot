package com.archie.cryptobot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import bot.Action
import bot.Backtest
import bot.Decision
import bot.Fast
import bot.Portfolio
import bot.Scalp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(Tab("Home", Icons.Filled.Home), Tab("Backtest", Icons.Filled.PlayArrow), Tab("Settings", Icons.Filled.Settings))

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    AppFrame(tab, { tab = it }) {
        when (tab) {
            0 -> HomeScreen { tab = 2 }
            1 -> BacktestScreen()
            else -> SettingsScreen()
        }
    }
}

/** Top bar, scrolling content and bottom navigation; shared by the app and the screenshot tests. */
@Composable
fun AppFrame(tab: Int, onTab: (Int) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Text("₿", color = Palette.Accent, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(10.dp))
            Text("Crypto Paper Bot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill("Paper money", Palette.Warning)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
        NavigationBar(Modifier.navigationBarsPadding(), containerColor = Palette.Surface, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { onTab(i) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Palette.Accent, selectedTextColor = Palette.Accent, indicatorColor = Palette.Accent.copy(alpha = 0.14f),
                        unselectedIconColor = Palette.TextMuted, unselectedTextColor = Palette.TextMuted))
            }
        }
    }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { BotStore.settings(context) }
    val hasKeys = remember { KeyVault.load(context) != null }
    var refresh by remember { mutableIntStateOf(0) }
    var ui by remember {
        mutableStateOf(HomeUi(
            strategyName = strategyName(s),
            strategyDetail = strategyDetail(s, BotStore.activeSymbol(context)),
            hasKeys = hasKeys,
            running = BotStore.isEnabled(context),
            runningHint = if (s.mode == "fast") "Checks every 30 seconds with a notification while on (uses more battery)."
                else "Checks every 15 minutes, even when the app is closed.",
            activeCoin = if (s.mode == "fast") (if (s.autoCoin) BotStore.activeSymbol(context) ?: s.symbol else s.symbol) else null,
        ))
    }

    fun reloadLocal() {
        val status = if (s.mode == "fast") BotStore.status(context)?.let {
            "Last check, " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(BotStore.statusTime(context))) + ": " + it
        } else null
        ui = ui.copy(log = BotStore.readLog(context), liveStatus = status, running = BotStore.isEnabled(context),
            strategyDetail = strategyDetail(s, BotStore.activeSymbol(context)))
    }

    LaunchedEffect(refresh) {
        reloadLocal()
        if (!hasKeys) return@LaunchedEffect
        ui = try {
            val o = withContext(Dispatchers.IO) {
                val api = AlpacaClient(KeyVault.load(context))
                val acct = api.account()
                val last = acct.optDouble("last_equity", 0.0)
                val equity = acct.getDouble("equity")
                OverviewUi(
                    pl = Bot.profitLoss(context, api),
                    since = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(BotStore.startTime(context))),
                    accountEquity = equity,
                    today = if (last > 0) equity - last else null,
                    history = api.portfolioHistory("1M").map { it.second },
                    tax = BotStore.tax(context),
                )
            }
            ui.copy(overview = o, overviewError = null)
        } catch (e: Exception) {
            ui.copy(overviewError = e.message)
        }
    }

    HomeContent(
        ui,
        onToggle = {
            Bot.schedule(context, it)
            BotStore.log(context, if (it) "Bot started" else "Bot stopped")
            reloadLocal()
        },
        onCheck = {
            ui = ui.copy(checking = true, checkError = null)
            scope.launch {
                ui = try {
                    val snap = withContext(Dispatchers.IO) { Bot.runOnce(context, trade = false) }
                    val verb = when (snap.action) { is Action.Buy -> "BUY"; is Action.Sell -> "SELL"; is Action.Hold -> "HOLD" }
                    val lines = mutableListOf("Paper equity" to Decision.money(snap.equity), "Cash" to Decision.money(snap.cash))
                    if (s.mode != "portfolio") {
                        lines += "Price" to Decision.money(snap.price)
                        lines += "Position" to (snap.position?.let { "%.6f @ %s".format(java.util.Locale.US, it.qty, Decision.money(it.entryPrice)) } ?: "None")
                    }
                    ui.copy(checking = false, check = CheckUi(verb, snap.action.reason, lines))
                } catch (e: Exception) {
                    ui.copy(checking = false, checkError = e.message)
                }
            }
        },
        onRefresh = { refresh++ },
        onVolatility = {
            ui = ui.copy(volBusy = true)
            scope.launch {
                ui = try {
                    val rows = withContext(Dispatchers.IO) {
                        val api = AlpacaClient(KeyVault.load(context))
                        val since = Instant.now().minusSeconds(7L * 86_400)
                        BotStore.symbols.map { sym -> sym to Fast.volatility(api.bars(sym, "1Min", since).map { it.close }.toDoubleArray()) }
                    }
                    ui.copy(volBusy = false, volatility = rows, volError = null)
                } catch (e: Exception) {
                    ui.copy(volBusy = false, volError = e.message)
                }
            }
        },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun BacktestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { BotStore.settings(context) }
    val fee = pct(s.feePct, 2)
    var ui by remember {
        mutableStateOf(BacktestUi(
            heading = when (s.mode) {
                "fast" -> "Fast · ${if (s.autoCoin) "auto coin" else s.symbol} · last 7 days"
                "portfolio" -> "Trend portfolio · daily since 2015"
                "scalp" -> "Scalp · ${s.symbol} · last 90 days"
                else -> "Trend · ${s.symbol} · daily since 2015"
            },
            description = when (s.mode) {
                "fast" -> "Replays fast mode on 1-minute candles from Alpaca. If the stop and take-profit are both touched in " +
                    "the same minute, it assumes the stop hit first. Every fill pays a $fee fee."
                "portfolio" -> "Replays the trend portfolio on daily BTC, ETH and SOL history and compares it with buying and holding. Each trade pays a $fee fee."
                "scalp" -> "Replays scalp mode on 15-minute candles. Take-profits fill inside a candle; stops and time limits " +
                    "at the candle's close, like the live bot. Every fill pays a $fee fee."
                else -> "Replays the strategy on daily history and compares it with buying and holding. Fills at the next day's open with a $fee fee."
            },
        ))
    }
    BacktestContent(ui) {
        ui = ui.copy(busy = true, error = null)
        scope.launch {
            ui = try {
                val rows = withContext(Dispatchers.IO) { runBacktests(AlpacaClient(KeyVault.load(context)), s) }
                val first = Instant.ofEpochSecond(rows[0].second.times.first()).atOffset(ZoneOffset.UTC).toLocalDate()
                val last = Instant.ofEpochSecond(rows[0].second.times.last()).atOffset(ZoneOffset.UTC).toLocalDate()
                val perYear = when (s.mode) { "fast" -> 365.0 * 1440; "scalp" -> 365.0 * 96; else -> 365.0 }
                val variants = rows.map { (n, r) -> VariantUi(n, r.equity, Backtest.metrics(r, perYear)) }
                val bh = variants[0].metrics
                val mine = variants.last().metrics
                val beat = mine.totalReturn >= bh.totalReturn
                ui.copy(busy = false, period = "$first to $last", variants = variants, beat = beat,
                    verdict = (if (s.mode == "portfolio") "The trend portfolio" else "Your settings") +
                        " ${if (beat) "beat" else "trailed"} buy-and-hold here (${signedPct(mine.totalReturn)} vs ${signedPct(bh.totalReturn)}), " +
                        "with a worst drop of ${pct(mine.maxDrawdown)} vs ${pct(bh.maxDrawdown)}. Past results don't predict future ones, " +
                        "and tuning settings until a backtest looks good usually makes live results worse.")
            } catch (e: Exception) {
                ui.copy(busy = false, error = e.message)
            }
        }
    }
}

/** Four variants per mode: buy-and-hold first, the user's settings last. */
private fun runBacktests(api: AlpacaClient, s: bot.Settings): List<Pair<String, bot.BacktestResult>> {
    if (s.mode == "fast") {
        val since = Instant.now().minusSeconds(7L * 86_400)
        val bars = api.bars(s.symbol, "1Min", since)
        if (s.autoCoin) {
            val all = BotStore.symbols.associateWith { api.bars(it, "1Min", since) }
            return listOf(
                "Buy and hold ${s.symbol.substringBefore('/')}" to Fast.buyAndHold(bars, s),
                "Auto coin, if trading were free" to Fast.backtestAuto(all, s.copy(feePct = 0.0), s.quick),
                "Same, ${s.symbol.substringBefore('/')} only" to Fast.backtest(bars, s, s.quick),
                "Your settings (auto coin)" to Fast.backtestAuto(all, s, s.quick),
            )
        }
        return listOf(
            "Buy and hold" to Fast.buyAndHold(bars, s),
            "Your settings, if trading were free" to Fast.backtest(bars, s.copy(feePct = 0.0), s.quick),
            (if (s.quick.minHourlyMove > 0) "Without the volatility filter" else "Only when 1-hour move > 1%") to
                Fast.backtest(bars, s, s.quick.copy(minHourlyMove = if (s.quick.minHourlyMove > 0) 0.0 else 0.01)),
            "Your settings" to Fast.backtest(bars, s, s.quick),
        )
    }
    if (s.mode == "portfolio") {
        val data = Portfolio.COINS.associateWith { api.dailyBars(it, Instant.parse("2015-01-01T00:00:00Z")) }
        return listOf(
            "Buy and hold, equal thirds" to Portfolio.buyAndHold(data, s.feePct),
            "Buy and hold BTC only" to Portfolio.buyAndHold(mapOf("BTC/USD" to data["BTC/USD"]!!), s.feePct),
            "Trend portfolio, if trading were free" to Portfolio.backtest(data, 0.0),
            "Trend portfolio" to Portfolio.backtest(data, s.feePct),
        )
    }
    if (s.mode == "scalp") {
        val bars = api.bars(s.symbol, "15Min", Instant.now().minusSeconds(90L * 86_400))
        return listOf(
            "Buy and hold" to Scalp.buyAndHold(bars, s),
            "Your settings, if trading were free" to Scalp.backtest(bars, s.copy(feePct = 0.0)),
            (if (s.scalp.entry == "reversal") "Buy the dip itself" else "Wait for the turn up") to
                Scalp.backtest(bars, s.copy(scalp = s.scalp.copy(entry = if (s.scalp.entry == "reversal") "dip" else "reversal"))),
            "Your settings" to Scalp.backtest(bars, s),
        )
    }
    val bars = api.dailyBars(s.symbol, Instant.parse("2015-01-01T00:00:00Z"))
    return listOf(
        "Buy and hold" to Backtest.buyAndHold(bars, s),
        "All-in, no stop" to Backtest.run(bars, s.copy(maxPositionPct = 1.0, stopLossPct = 1.0)),
        "All-in with stop" to Backtest.run(bars, s.copy(maxPositionPct = 1.0)),
        "Your settings" to Backtest.run(bars, s),
    )
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    SettingsContent(
        initial = remember { BotStore.settings(context) },
        hasKeys = remember { KeyVault.load(context) != null },
        startingAmount = remember { BotStore.startingAmount(context) },
        tax = remember { BotStore.tax(context) },
        onSaveKeys = { id, secret -> KeyVault.save(context, ApiKeys(id, secret)) },
        onRemoveKeys = { KeyVault.clear(context); Bot.schedule(context, false) },
        onSetAmount = { BotStore.setStartingAmount(context, it) },
        onSaveTax = { BotStore.setTax(context, it) },
        onSave = {
            BotStore.saveSettings(context, it)
            if (BotStore.isEnabled(context)) Bot.schedule(context, true) // start or stop the fast loop to match the mode
        },
    )
}
