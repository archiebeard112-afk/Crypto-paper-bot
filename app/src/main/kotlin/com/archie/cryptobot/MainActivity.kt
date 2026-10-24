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
import bot.Metrics
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

    Text("${s.symbol}  ·  SMA ${s.fast}/${s.slow} days  ·  cap ${pct(s.maxPositionPct)}  ·  stop ${pct(s.stopLossPct)}",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    if (!hasKeys) {
        Card { Text("Add your Alpaca paper API keys in Settings to start.", Modifier.padding(16.dp)) }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bot running", fontWeight = FontWeight.Bold)
            Text("Checks every 15 minutes, even when the app is closed", style = MaterialTheme.typography.bodySmall)
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
                buildString {
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

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { log = BotStore.readLog(context) }) { Text("Refresh") }
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

private data class BacktestRow(val name: String, val result: BacktestResult, val metrics: Metrics)

@Composable
fun BacktestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<BacktestRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val s = BotStore.settings(context)

    Text("Replays the strategy on daily ${s.symbol} history from Alpaca and compares it with simply buying and holding. " +
        "Fills happen at the next day's open and pay a ${pct(s.feePct, 2)} fee per trade.",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Button(enabled = !busy, onClick = {
        busy = true
        error = null
        scope.launch {
            try {
                rows = withContext(Dispatchers.IO) {
                    val bars = AlpacaClient(KeyVault.load(context)).dailyBars(s.symbol, Instant.parse("2015-01-01T00:00:00Z"))
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
        TableRow(listOf("", "Return", "Yearly", "Worst drop", "Trades", "Stops", "Invested"), header = true)
        r.forEachIndexed { i, row ->
            val m = row.metrics
            TableRow(listOf(row.name, pct(m.totalReturn), pct(m.cagr), pct(m.maxDrawdown), "${m.trades}", "${m.stopOuts}", pct(m.timeInMarket)), color = colors[i])
        }
    }
    Spacer(Modifier.height(12.dp))
    val bh = r[0].metrics
    val mine = r[3].metrics
    val verdict = if (mine.totalReturn >= bh.totalReturn) "beat" else "trailed"
    Text("Your settings $verdict buy-and-hold over this period (${pct(mine.totalReturn)} vs ${pct(bh.totalReturn)}), " +
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
    var message by remember { mutableStateOf<String?>(null) }

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
    Text("Strategy", style = MaterialTheme.typography.titleMedium)
    Text("Buys when the fast moving average of daily closes rises above the slow one, sells when it drops below.",
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BotStore.symbols.forEach { FilterChip(selected = symbol == it, onClick = { symbol = it }, label = { Text(it) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Fast (days)", fast, Modifier.weight(1f)) { fast = it }
        NumberField("Slow (days)", slow, Modifier.weight(1f)) { slow = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Max position %", cap, Modifier.weight(1f)) { cap = it }
        NumberField("Stop loss %", stop, Modifier.weight(1f)) { stop = it }
    }
    Button(onClick = {
        message = try {
            val s = Settings(symbol = symbol, fast = fast.toInt(), slow = slow.toInt(),
                maxPositionPct = cap.toDouble() / 100, stopLossPct = stop.toDouble() / 100)
            BotStore.saveSettings(context, s)
            "Saved. The bot uses these from its next check."
        } catch (e: Exception) {
            "Not saved: ${e.message ?: "check the numbers"}"
        }
    }, modifier = Modifier.padding(top = 8.dp)) { Text("Save strategy") }
    message?.let { Text(it, Modifier.padding(top = 8.dp)) }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

fun pct(v: Double, decimals: Int = 0) = "%.${decimals}f%%".format(v * 100)
