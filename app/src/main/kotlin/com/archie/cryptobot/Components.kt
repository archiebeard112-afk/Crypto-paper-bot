package com.archie.cryptobot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bot.Decision
import kotlin.math.abs
import kotlin.math.ln

private val CardShape = RoundedCornerShape(20.dp)
private val ControlShape = RoundedCornerShape(14.dp)

@Composable
fun AppCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(Palette.Surface)
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), CardShape).padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false) =
    Text(text, modifier, color = Palette.TextMuted,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)

/** Small rounded label, tinted with [color]. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
        .padding(horizontal = 9.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold, maxLines = 1)
}

/** Profit chip, e.g. "▲ $120.00 · 1.2%". */
@Composable
fun ChangeChip(amount: Double, pct: Double?, modifier: Modifier = Modifier) {
    val c = Palette.gain(amount)
    val text = (if (amount >= 0) "▲ " else "▼ ") + Decision.money(abs(amount)) + (pct?.let { "  ·  " + pctAbs(it) } ?: "")
    Text(text, modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = c, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Text) {
    Column(modifier) {
        Text(label, color = Palette.TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.height(50.dp), enabled = enabled, shape = ControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color(0xFF00281A),
            disabledContainerColor = Palette.SurfaceHigh, disabledContentColor = Palette.TextMuted)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.Text) {
    OutlinedButton(onClick, modifier.height(44.dp), enabled = enabled, shape = ControlShape,
        border = BorderStroke(1.dp, Palette.Outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Palette.SurfaceHigh, contentColor = color)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Small text button for card headers, e.g. "Refresh". */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (enabled) Palette.Accent else Palette.TextMuted, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked, onChange, colors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFF00281A), checkedTrackColor = Palette.Accent,
        uncheckedThumbColor = Palette.TextMuted, uncheckedTrackColor = Palette.SurfaceHigh, uncheckedBorderColor = Palette.Outline))
}

@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Muted(it, small = true) }
        }
        AppSwitch(checked, onChange)
    }
}

/** A row of options where exactly one is picked. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(ControlShape).background(Palette.Background).border(1.dp, Palette.Outline, ControlShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(label, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Palette.SurfaceHigh else Color.Transparent)
                .clickable { onSelect(key) }.padding(vertical = 9.dp),
                color = if (on) Palette.Text else Palette.TextMuted, style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    hidden: Boolean = false,
    suffix: String? = null,
) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true, shape = ControlShape,
        keyboardOptions = keyboard,
        visualTransformation = if (hidden) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        suffix = suffix?.let { { Text(it, color = Palette.TextMuted) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
            focusedContainerColor = Palette.Background, unfocusedContainerColor = Palette.Background,
            focusedLabelColor = Palette.Accent, unfocusedLabelColor = Palette.TextMuted, cursorColor = Palette.Accent))
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = false, suffix: String? = null, onChange: (String) -> Unit) {
    AppTextField(label, value, { input ->
        val cleaned = input.filter { it.isDigit() || (decimal && it == '.') }
        if (cleaned.count { it == '.' } <= 1) onChange(cleaned)
    }, modifier, KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), suffix = suffix)
}

fun coinColor(symbol: String) = when (symbol.substringBefore('/').removeSuffix("USDT")) {
    "BTC" -> Color(0xFFF7931A)
    "ETH" -> Color(0xFF8A9CF5)
    "SOL" -> Color(0xFF9D6BFF)
    "LTC" -> Color(0xFF7FA6D9)
    "DOGE" -> Color(0xFFD4B24C)
    else -> Palette.Info
}

@Composable
fun CoinAvatar(symbol: String, size: Dp = 36.dp) {
    val c = coinColor(symbol)
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(symbol.substringBefore('/').take(1), color = c, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun StatusDot(on: Boolean) {
    val c = if (on) Palette.Accent else Palette.TextMuted
    Box(Modifier.size(18.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
    }
}

/** Area chart with a soft gradient under the line. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(96.dp)) {
    if (values.size < 2) return
    Canvas(modifier) {
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
        val pad = 4.dp.toPx()
        fun pt(i: Int) = Offset(size.width * i / (values.size - 1), (pad + (size.height - 2 * pad) * (1 - (values[i] - lo) / span)).toFloat())
        val line = Path().apply { values.indices.forEach { if (it == 0) moveTo(pt(it).x, pt(it).y) else lineTo(pt(it).x, pt(it).y) } }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 4.dp.toPx(), pt(values.size - 1))
        drawCircle(color.copy(alpha = 0.25f), 8.dp.toPx(), pt(values.size - 1))
    }
}

/** Several equity curves on a log scale, so equal percentage moves look the same size. The last series is drawn on top. */
@Composable
fun MultiLineChart(series: List<DoubleArray>, colors: List<Color>, modifier: Modifier = Modifier.fillMaxWidth().height(200.dp)) {
    Canvas(modifier) {
        val all = series.flatMap { s -> s.map { ln(it.coerceAtLeast(1e-9)) } }
        val lo = all.min()
        val span = (all.max() - lo).takeIf { it > 0 } ?: 1.0
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
        for (k in 0..3) {
            val y = size.height * k / 3
            drawLine(Palette.Outline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dash)
        }
        series.forEachIndexed { idx, s ->
            if (s.size < 2) return@forEachIndexed
            val path = Path()
            s.forEachIndexed { i, v ->
                val x = size.width * i / (s.size - 1)
                val y = (size.height * (1 - (ln(v.coerceAtLeast(1e-9)) - lo) / span)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val mine = idx == series.size - 1
            drawPath(path, colors[idx], style = Stroke((if (mine) 2.5 else 1.6).dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

/** Horizontal meter for "how busy is this coin vs normal": ratio 1 is normal, the tick marks it. */
@Composable
fun VolMeter(ratio: Double, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.SurfaceHigh, cornerRadius = r)
        val f = if (ratio.isNaN()) 0.0 else (ratio / 3).coerceIn(0.03, 1.0)
        drawRoundRect(color, size = Size((size.width * f).toFloat(), size.height), cornerRadius = r)
        val x = size.width / 3
        drawLine(Palette.Text.copy(alpha = 0.55f), Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 2f)
    }
}

/** Legend entry: colored bar and label. */
@Composable
fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A key/value line inside a card. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = Palette.TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Palette.Outline.copy(alpha = 0.6f)))

fun signedMoney(v: Double) = (if (v >= 0) "+" else "-") + Decision.money(abs(v))

fun signedPct(v: Double, decimals: Int = 1) = (if (v >= 0) "+" else "") + pct(v, decimals)

private fun pctAbs(v: Double) = pct(abs(v), 2)

fun pct(v: Double, decimals: Int = 0) = "%.${decimals}f%%".format(java.util.Locale.US, v * 100)

/** 1.5 -> "1.5", 2.0 -> "2" */
fun trim(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0')
