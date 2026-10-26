package com.archie.cryptobot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.Decision
import bot.FastSettings
import bot.Fast
import bot.Metrics
import bot.ProfitLoss
import bot.ScalpSettings
import bot.Settings
import bot.Tax
import java.text.DateFormat
import java.util.Date

// ---------- Home ----------

data class OverviewUi(
    val pl: ProfitLoss?,
    val since: String?,
    val accountEquity: Double,
    val today: Double?,
    val history: List<Double>,
    val tax: Tax,
)

data class CheckUi(val verb: String, val reason: String, val lines: List<Pair<String, String>>)

data class HomeUi(
    val strategyName: String,
    val strategyDetail: String,
    val hasKeys: Boolean,
    val running: Boolean,
    val runningHint: String,
    val overview: OverviewUi? = null,
    val overviewError: String? = null,
    val checking: Boolean = false,
    val check: CheckUi? = null,
    val checkError: String? = null,
    val liveStatus: String? = null,
    val volatility: List<Pair<String, Fast.Volatility>>? = null,
    val volBusy: Boolean = false,
    val volError: String? = null,
    val activeCoin: String? = null,
    val log: List<BotStore.LogEntry> = emptyList(),
)

fun strategyName(s: Settings) = when (s.mode) {
    "portfolio" -> "Trend portfolio"
    "fast" -> "Fast · 1-min"
    "scalp" -> "Scalp · 15-min"
    else -> "Trend · one coin"
}

fun strategyDetail(s: Settings, activeCoin: String? = null) = when (s.mode) {
    "portfolio" -> "BTC + ETH + SOL · 150-day trend · volatility-sized"
    "fast" -> (if (s.autoCoin) "Auto coin (${(activeCoin ?: s.symbol).substringBefore('/')})" else s.symbol) +
        " · +${pct(s.quick.takeProfitPct, 1)} / -${pct(s.quick.stopPct, 1)} · max ${s.quick.maxHoldMinutes} min"
    "scalp" -> "${s.symbol} · RSI < ${s.scalp.rsiBuy.toInt()} · +${pct(s.scalp.takeProfitPct, 1)} / -${pct(s.scalp.stopPct, 1)}"
    else -> "${s.symbol} · SMA ${s.fast}/${s.slow} days · stop ${pct(s.stopLossPct)}"
}

@Composable
fun HomeContent(
    ui: HomeUi,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onRefresh: () -> Unit,
    onVolatility: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!ui.hasKeys) {
        AppCard {
            Pill("Step 1", Palette.Info)
            Spacer(Modifier.height(12.dp))
            Text("Connect your Alpaca paper account", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Muted("Create a free account at alpaca.markets, switch to Paper, and generate API keys. Paste them in " +
                "Settings. They're encrypted on this phone and only ever sent to Alpaca's paper endpoint.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Open Settings", onOpenSettings, Modifier.fillMaxWidth())
        }
        return
    }
    HeroCard(ui.overview, ui.overviewError)
    Spacer(Modifier.height(14.dp))
    ControlCard(ui, onToggle, onCheck)

    val holdings = ui.overview?.pl?.holdings.orEmpty()
    if (holdings.isNotEmpty()) {
        SectionTitle("Holdings")
        AppCard(padding = 6.dp) {
            holdings.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoinAvatar(h.symbol)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.symbol.substringBefore('/'), style = MaterialTheme.typography.titleSmall)
                        Muted("%.6f".format(java.util.Locale.US, h.qty) + " @ " + Decision.money(h.avgCost), small = true)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Decision.money(h.value), style = MaterialTheme.typography.titleSmall)
                        Text(signedMoney(h.unrealized), color = Palette.gain(h.unrealized), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    SectionTitle("Market volatility") {
        LinkButton(if (ui.volBusy) "Loading…" else if (ui.volatility == null) "Check" else "Refresh", onVolatility, enabled = !ui.volBusy)
    }
    AppCard {
        Muted("Typical move in an hour right now, against a normal hour this week. The line marks normal.", small = true)
        ui.volError?.let { Text("Couldn't load: $it", color = Palette.Loss, style = MaterialTheme.typography.bodySmall) }
        val rows = ui.volatility
        if (rows == null) {
            Spacer(Modifier.height(10.dp))
            Muted("Tap Check to load live data for all five coins.")
        } else rows.forEach { (sym, v) -> VolRow(sym, v, active = sym == ui.activeCoin) }
    }

    SectionTitle("Activity") { LinkButton("Refresh", onRefresh) }
    AppCard(padding = 6.dp) {
        if (ui.log.isEmpty()) Muted("Nothing yet. Trades and checks show up here.", Modifier.padding(12.dp))
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        ui.log.take(40).forEachIndexed { i, e ->
            val c = when {
                e.text.startsWith("BUY") || e.text.contains("BOUGHT") -> Palette.Gain
                e.text.startsWith("SELL") || e.text.contains("SOLD") -> Palette.Loss
                e.text.startsWith("Error") || e.text.startsWith("Stopped") -> Palette.Warning
                e.text.startsWith("Switched") -> Palette.Info
                else -> Palette.TextMuted
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Muted(fmt.format(Date(e.time)), small = true)
                }
            }
            if (i < minOf(ui.log.size, 40) - 1) Box(Modifier.fillMaxWidth().padding(start = 32.dp).height(1.dp).background(Palette.Outline.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun HeroCard(o: OverviewUi?, error: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF14261F), Palette.Surface)))
        .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(20.dp)) {
        when {
            error != null -> { Muted("Balance"); Text("Couldn't load: $error", color = Palette.Loss) ; return@Column }
            o == null -> { Muted("Balance"); Text("Loading…", style = MaterialTheme.typography.displaySmall, color = Palette.TextMuted); return@Column }
        }
        o!!
        val pl = o.pl
        Text(if (pl != null) "BOT BALANCE" else "PAPER ACCOUNT", color = Palette.TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(Decision.money(pl?.currentValue ?: o.accountEquity), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pl != null) ChangeChip(pl.total, pl.totalPct) else o.today?.let { ChangeChip(it, null) }
            Spacer(Modifier.width(8.dp))
            Muted(if (pl != null) "since ${o.since}" else "today", small = true)
        }
        if (o.history.size > 1) {
            Spacer(Modifier.height(14.dp))
            Sparkline(o.history, Palette.gain(o.history.last() - o.history.first()))
            Muted("Whole paper account, last 30 days", small = true)
        }
        Divider()
        if (pl == null) {
            Muted("Set a starting amount in Settings to track the bot's own profit and loss.", small = true)
            return@Column
        }
        val taxOn = o.tax.rate > 0
        Row {
            StatTile("Closed trades", signedMoney(pl.realized), Modifier.weight(1f), Palette.gain(pl.realized))
            StatTile("Open", signedMoney(pl.unrealized), Modifier.weight(1f), Palette.gain(pl.unrealized))
            if (taxOn) StatTile("After tax", signedMoney(pl.afterTax(o.tax)), Modifier.weight(1f), Palette.gain(pl.afterTax(o.tax)))
            else StatTile("Whole account", Decision.money(o.accountEquity), Modifier.weight(1f))
        }
        if (taxOn) {
            Spacer(Modifier.height(10.dp))
            Muted("Estimated tax at ${trim(o.tax.rate * 100)}%: ${Decision.money(pl.taxIfSoldNow(o.tax))} if you sold everything now " +
                "(${Decision.money(pl.taxSoFar(o.tax))} on closed trades). A rough guide, not tax advice.", small = true)
        }
    }
}

@Composable
private fun ControlCard(ui: HomeUi, onToggle: (Boolean) -> Unit, onCheck: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ui.running)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ui.running) "Bot running" else "Bot stopped", style = MaterialTheme.typography.titleMedium)
                Text(ui.strategyName, color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
            }
            AppSwitch(ui.running, onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Muted(ui.strategyDetail, small = true)
        Muted(ui.runningHint, small = true)
        ui.liveStatus?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Background).padding(12.dp),
                color = Palette.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        GhostButton(if (ui.checking) "Checking…" else "Check now (no trade)", onCheck, Modifier.fillMaxWidth(), enabled = !ui.checking)
        ui.checkError?.let { Text("Error: $it", color = Palette.Loss, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        ui.check?.let { c ->
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Next step", Modifier.weight(1f), small = true)
                Pill(c.verb, when (c.verb) { "BUY" -> Palette.Gain; "SELL" -> Palette.Loss; else -> Palette.Info })
            }
            Spacer(Modifier.height(6.dp))
            Text(c.reason, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            c.lines.forEach { (k, v) -> InfoRow(k, v) }
        }
    }
}

@Composable
private fun VolRow(sym: String, v: Fast.Volatility, active: Boolean) {
    val c = when (v.label) { "Busy" -> Palette.Warning; "Calm" -> Palette.TextMuted; else -> Palette.Accent }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CoinAvatar(sym, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sym.substringBefore('/'), style = MaterialTheme.typography.titleSmall)
                if (active) { Spacer(Modifier.width(6.dp)); Pill("Trading", Palette.Info) }
                Spacer(Modifier.weight(1f))
                Text(pct(v.lastHour, 2), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            }
            Spacer(Modifier.height(6.dp))
            VolMeter(v.ratio, c, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row {
                Muted("normal ${pct(v.normalHour, 2)} · 24h ${pct(v.lastDay, 1)}", Modifier.weight(1f), small = true)
                Text(v.label, color = c, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------- Backtest ----------

data class VariantUi(val name: String, val equity: DoubleArray, val metrics: Metrics)

data class BacktestUi(
    val heading: String,
    val description: String,
    val busy: Boolean = false,
    val error: String? = null,
    val period: String? = null,
    val variants: List<VariantUi>? = null,
    val verdict: String? = null,
    val beat: Boolean = false,
)

@Composable
fun BacktestContent(ui: BacktestUi, onRun: () -> Unit) {
    Text("Backtest", style = MaterialTheme.typography.titleLarge)
    Text(ui.heading, color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Muted(ui.description, small = true)
    Spacer(Modifier.height(14.dp))
    PrimaryButton(if (ui.busy) "Running…" else if (ui.variants == null) "Run backtest" else "Run again", onRun, Modifier.fillMaxWidth(), enabled = !ui.busy)
    ui.error?.let { Text("Error: $it", color = Palette.Loss, modifier = Modifier.padding(top = 10.dp)) }
    val v = ui.variants ?: return
    val colors = Palette.Series.takeLast(v.size)

    SectionTitle("Growth of \$100,000")
    AppCard {
        ui.period?.let { Muted(it, small = true) }
        Spacer(Modifier.height(10.dp))
        MultiLineChart(v.map { it.equity }, colors)
        Spacer(Modifier.height(12.dp))
        v.chunked(2).forEachIndexed { r, pair ->
            Row(Modifier.padding(vertical = 3.dp)) {
                pair.forEachIndexed { k, item -> LegendItem(colors[r * 2 + k], item.name, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    SectionTitle("Results")
    v.forEachIndexed { i, item ->
        val m = item.metrics
        val mine = i == v.size - 1
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
            .border(1.dp, if (mine) Palette.Accent.copy(alpha = 0.6f) else Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                Spacer(Modifier.width(8.dp))
                Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(signedPct(m.totalReturn), color = Palette.gain(m.totalReturn), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                StatTile("Worst drop", pct(m.maxDrawdown), Modifier.weight(1f), Palette.Loss)
                StatTile("Trades", "${m.trades}", Modifier.weight(1f))
                StatTile("Win rate", if (m.winRate.isNaN()) "–" else pct(m.winRate), Modifier.weight(1f))
                StatTile("Invested", pct(m.timeInMarket), Modifier.weight(1f))
            }
        }
    }
    ui.verdict?.let {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(16.dp)) {
            Box(Modifier.width(4.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(if (ui.beat) Palette.Accent else Palette.Warning))
            Spacer(Modifier.width(12.dp))
            Muted(it, small = true)
        }
    }
}

// ---------- Settings ----------

private data class Mode(val key: String, val title: String, val tag: String, val tagColor: Color, val blurb: String)

private val modes = listOf(
    Mode("portfolio", "Trend portfolio", "Best tested", Palette.Accent,
        "Thirds in BTC, ETH and SOL. Holds a coin only above its 150-day average, smaller when it swings hard. Trades a few times a month."),
    Mode("trend", "Trend · one coin", "Trailed buy & hold", Palette.Warning,
        "Buys when the fast daily average crosses above the slow one, sells when it crosses back. A few trades a year."),
    Mode("scalp", "Scalp · 15-min", "Lost money in tests", Palette.Loss,
        "Buys sharp dips on 15-minute candles and sells at a small gain, a small loss, or a time limit."),
    Mode("fast", "Fast · 1-min", "Lost money in tests", Palette.Loss,
        "Many quick trades on 1-minute candles with your take-profit and stop-loss. Fees eat most small wins."),
)

@Composable
fun SettingsContent(
    initial: Settings,
    hasKeys: Boolean,
    startingAmount: Double,
    tax: Tax,
    onSaveKeys: (String, String) -> Unit,
    onRemoveKeys: () -> Unit,
    onSetAmount: (Double) -> Unit,
    onSaveTax: (Tax) -> Unit,
    onSave: (Settings) -> Unit,
) {
    var keysSaved by remember { mutableStateOf(hasKeys) }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var symbol by remember { mutableStateOf(initial.symbol) }
    var fast by remember { mutableStateOf(initial.fast.toString()) }
    var slow by remember { mutableStateOf(initial.slow.toString()) }
    var cap by remember { mutableStateOf((initial.maxPositionPct * 100).toInt().toString()) }
    var stop by remember { mutableStateOf((initial.stopLossPct * 100).toInt().toString()) }
    var mode by remember { mutableStateOf(initial.mode) }
    var rsi by remember { mutableStateOf(initial.scalp.rsiBuy.toInt().toString()) }
    var tp by remember { mutableStateOf(trim(initial.scalp.takeProfitPct * 100)) }
    var sStop by remember { mutableStateOf(trim(initial.scalp.stopPct * 100)) }
    var hold by remember { mutableStateOf(trim(initial.scalp.maxHoldBars / 4.0)) }
    var trendFilter by remember { mutableStateOf(initial.scalp.trendFilter) }
    var entry by remember { mutableStateOf(initial.scalp.entry) }
    var fTp by remember { mutableStateOf(trim(initial.quick.takeProfitPct * 100)) }
    var fSl by remember { mutableStateOf(trim(initial.quick.stopPct * 100)) }
    var fHold by remember { mutableStateOf(initial.quick.maxHoldMinutes.toString()) }
    var fVol by remember { mutableStateOf(trim(initial.quick.minHourlyMove * 100)) }
    var fEntry by remember { mutableStateOf(initial.quick.entry) }
    var autoCoin by remember { mutableStateOf(initial.autoCoin) }
    var message by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf(startingAmount.takeIf { it > 0 }?.let { trim(it) } ?: "") }
    var savedAmount by remember { mutableStateOf(startingAmount) }
    var amountMessage by remember { mutableStateOf<String?>(null) }
    var taxRate by remember { mutableStateOf(trim(tax.rate * 100)) }
    var taxAllowance by remember { mutableStateOf(trim(tax.allowance)) }
    var taxMessage by remember { mutableStateOf<String?>(null) }

    @Composable
    fun KeysCard() {
        SectionTitle("Alpaca paper keys")
        AppCard {
            if (keysSaved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(true)
                    Spacer(Modifier.width(10.dp))
                    Text("Keys saved and encrypted", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    LinkButton("Remove", { onRemoveKeys(); keysSaved = false; keyMessage = null })
                }
            } else {
                Muted("From your Paper account at alpaca.markets. Encrypted on this phone and only sent to Alpaca's paper endpoint.", small = true)
                Spacer(Modifier.height(12.dp))
                AppTextField("API key ID", keyId, { keyId = it.trim() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                AppTextField("Secret key", secret, { secret = it.trim() }, Modifier.fillMaxWidth(), hidden = true)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Save keys", {
                    onSaveKeys(keyId, secret)
                    keyId = ""; secret = ""; keysSaved = true
                    keyMessage = "Saved. Tap \"Check now\" on Home to test them."
                }, Modifier.fillMaxWidth(), enabled = keyId.isNotEmpty() && secret.isNotEmpty())
            }
            keyMessage?.let { Muted(it, Modifier.padding(top = 8.dp), small = true) }
        }
    }

    Text("Settings", style = MaterialTheme.typography.titleLarge)
    if (!keysSaved) KeysCard()

    SectionTitle("Strategy")
    modes.forEach { m ->
        val on = mode == m.key
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) Palette.SurfaceHigh else Palette.Surface)
            .border(if (on) 1.5.dp else 1.dp, if (on) Palette.Accent else Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable { mode = m.key }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) Palette.Accent else Palette.Outline, CircleShape),
                    contentAlignment = Alignment.Center) { if (on) Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent)) }
                Spacer(Modifier.width(10.dp))
                Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill(m.tag, m.tagColor)
            }
            Spacer(Modifier.height(6.dp))
            Muted(m.blurb, Modifier.padding(start = 28.dp), small = true)
        }
    }

    SectionTitle("${modes.first { it.key == mode }.title} settings")
    AppCard {
        if (mode == "portfolio") {
            Muted("Fixed to the settings that were tested: one third each in BTC, ETH and SOL, 150-day trend, " +
                "50% yearly volatility target, rebalance when a holding drifts 10% from target.", small = true)
        } else {
            if (mode == "fast") {
                SwitchRow("Auto coin", "Trade whichever coin is moving most. Only switches when nothing is held.", autoCoin) { autoCoin = it }
                Spacer(Modifier.height(8.dp))
            }
            Muted(if (mode == "fast" && autoCoin) "Starting coin" else "Coin", small = true)
            Spacer(Modifier.height(6.dp))
            Segmented(BotStore.symbols.map { it to it.substringBefore('/') }, symbol, { symbol = it })
            Spacer(Modifier.height(16.dp))
        }
        when (mode) {
            "scalp" -> {
                Muted("When to buy", small = true)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("dip" to "Buy the dip", "reversal" to "Dip then turn up"), entry, { entry = it })
                Muted(if (entry == "reversal") "Waits until, within 2 hours of a dip, the 9-candle average crosses above the 21-candle one."
                    else "Buys as soon as RSI drops below your level.", Modifier.padding(top = 6.dp), small = true)
                Spacer(Modifier.height(12.dp))
                FieldPair({ NumberField("Buy below RSI", rsi, it) { v -> rsi = v } }, { NumberField("Max hold", hold, it, decimal = true, suffix = "h") { v -> hold = v } })
                FieldPair({ NumberField("Take profit", tp, it, decimal = true, suffix = "%") { v -> tp = v } }, { NumberField("Stop loss", sStop, it, decimal = true, suffix = "%") { v -> sStop = v } })
                SwitchRow("Trend filter", "Only buy dips while the 2-day trend is up", trendFilter) { trendFilter = it }
                NumberField("Max position", cap, Modifier.fillMaxWidth(), suffix = "%") { cap = it }
            }
            "fast" -> {
                Muted("When to buy", small = true)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("breakout" to "Breakout", "dip_turn_up" to "Dip then turn up"), fEntry, { fEntry = it })
                Muted(if (fEntry == "breakout") "Buys a new 20-minute high while the short-term trend is up."
                    else "Buys when, within 10 minutes of a sharp dip, the 9-minute average crosses above the 21-minute one.",
                    Modifier.padding(top = 6.dp), small = true)
                Spacer(Modifier.height(12.dp))
                FieldPair({ NumberField("Take profit", fTp, it, decimal = true, suffix = "%") { v -> fTp = v } }, { NumberField("Stop loss", fSl, it, decimal = true, suffix = "%") { v -> fSl = v } })
                FieldPair({ NumberField("Max hold", fHold, it, suffix = "min") { v -> fHold = v } }, { NumberField("Min 1-hour move", fVol, it, decimal = true, suffix = "%") { v -> fVol = v } })
                NumberField("Max position", cap, Modifier.fillMaxWidth(), suffix = "%") { cap = it }
                val size = (savedAmount.takeIf { it > 0 } ?: 1000.0) * ((cap.toDoubleOrNull() ?: 0.0) / 100)
                val tpV = (fTp.toDoubleOrNull() ?: 0.0) / 100
                val slV = (fSl.toDoubleOrNull() ?: 0.0) / 100
                val fee = 0.0025
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Background).padding(12.dp)) {
                    Text("On a ${Decision.money(size)} trade, after fees", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                    InfoRow("A win makes about", signedMoney(size * (tpV - 2 * fee)), Palette.gain(tpV - 2 * fee))
                    InfoRow("A loss costs about", "-" + Decision.money(size * (slV + 2 * fee)), Palette.Loss)
                    if (tpV <= 2 * fee) Text("This take-profit is smaller than the fees, so even winning trades lose money.",
                        color = Palette.Loss, style = MaterialTheme.typography.bodySmall)
                }
                Muted("Min 1-hour move skips calm markets: 0 trades all the time, 1 waits until a coin typically moves 1% an hour.",
                    Modifier.padding(top = 8.dp), small = true)
            }
            "trend" -> {
                FieldPair({ NumberField("Fast average", fast, it, suffix = "days") { v -> fast = v } }, { NumberField("Slow average", slow, it, suffix = "days") { v -> slow = v } })
                FieldPair({ NumberField("Max position", cap, it, suffix = "%") { v -> cap = v } }, { NumberField("Stop loss", stop, it, suffix = "%") { v -> stop = v } })
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Save strategy", {
            message = try {
                onSave(Settings(symbol = symbol, fast = fast.toInt(), slow = slow.toInt(),
                    maxPositionPct = cap.toDouble() / 100, stopLossPct = stop.toDouble() / 100, mode = mode,
                    scalp = ScalpSettings(rsiBuy = rsi.toDouble(), takeProfitPct = tp.toDouble() / 100, stopPct = sStop.toDouble() / 100,
                        maxHoldBars = maxOf(1, Math.round(hold.toDouble() * 4).toInt()), trendFilter = trendFilter, entry = entry),
                    quick = FastSettings(takeProfitPct = fTp.toDouble() / 100, stopPct = fSl.toDouble() / 100,
                        maxHoldMinutes = fHold.toInt(), minHourlyMove = (fVol.toDoubleOrNull() ?: 0.0) / 100, entry = fEntry),
                    autoCoin = autoCoin))
                "Saved. The bot uses these from its next check."
            } catch (e: Exception) {
                "Not saved: ${e.message ?: "check the numbers"}"
            }
        }, Modifier.fillMaxWidth())
        message?.let { Muted(it, Modifier.padding(top = 8.dp), small = true) }
    }

    SectionTitle("Starting amount")
    AppCard {
        Muted("The bot trades with this much paper money plus whatever it gains or loses, and profit is measured from when you set it.", small = true)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField("Amount", amount, Modifier.weight(1f), decimal = true, suffix = "$") { amount = it }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Set", {
                val v = amount.toDoubleOrNull() ?: 0.0
                amountMessage = if (v < 10) "Enter at least \$10." else {
                    onSetAmount(v); savedAmount = v
                    "Set to ${Decision.money(v)}. Profit and loss counts from now."
                }
            }, Modifier.padding(top = 6.dp))
        }
        Muted(amountMessage ?: if (savedAmount > 0) "Current: ${Decision.money(savedAmount)}" else "Not set: profit and loss is hidden.",
            Modifier.padding(top = 8.dp), small = true)
    }

    SectionTitle("Tax estimate")
    AppCard {
        Muted("Shows profit after an estimated tax on gains. In the US every crypto sale is taxable, and coins held under " +
            "a year (all of the bot's trades) are taxed like income: 10% to 37% federal, plus any state tax. Losses offset " +
            "gains. Set your own rate, or 0 to hide it. Paper trades owe no real tax; this is a rough guide, not tax advice.", small = true)
        Spacer(Modifier.height(12.dp))
        FieldPair({ NumberField("Tax rate", taxRate, it, decimal = true, suffix = "%") { v -> taxRate = v } },
            { NumberField("Tax-free allowance", taxAllowance, it, decimal = true, suffix = "$") { v -> taxAllowance = v } })
        Spacer(Modifier.height(4.dp))
        GhostButton("Save tax estimate", {
            taxMessage = try {
                onSaveTax(Tax((taxRate.toDoubleOrNull() ?: 0.0) / 100, taxAllowance.toDoubleOrNull() ?: 0.0))
                "Saved."
            } catch (e: IllegalArgumentException) {
                "Not saved: ${e.message}"
            }
        }, Modifier.fillMaxWidth())
        taxMessage?.let { Muted(it, Modifier.padding(top = 8.dp), small = true) }
    }

    if (keysSaved) KeysCard()
}

@Composable
private fun FieldPair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}
