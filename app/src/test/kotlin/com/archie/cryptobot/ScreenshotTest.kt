package com.archie.cryptobot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import bot.Fast
import bot.Holding
import bot.Metrics
import bot.ProfitLoss
import bot.Settings
import bot.Tax
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/** Renders every screen with sample data, so the design can be reviewed without a phone. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 6000),
        renderingMode = SessionParams.RenderingMode.SHRINK,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    private val settings = Samples.settings
    private val home = Samples.home


    private fun curve(n: Int, drift: Double, wobble: Double, phase: Double) =
        DoubleArray(n) { 100_000 * exp(drift * it / n + wobble * sin(it / 9.0 + phase)) }

    private val backtest = BacktestUi(
        heading = "Fast · auto coin · last 7 days",
        description = "Replays fast mode on 1-minute candles from Alpaca. If the stop and take-profit are both touched in " +
            "the same minute, it assumes the stop hit first. Every fill pays a 0.25% fee.",
        period = "2026-10-19 to 2026-10-26",
        variants = listOf(
            VariantUi("Buy and hold BTC", curve(200, 0.04, 0.03, 0.0), Metrics(0.041, 0.38, -0.061, 0.0, 0, 0, Double.NaN, 1.0)),
            VariantUi("Auto coin, if trading were free", curve(200, 0.02, 0.01, 1.0), Metrics(0.021, 0.0, -0.032, 0.0, 31, 14, 0.52, 0.18)),
            VariantUi("Same, BTC only", curve(200, -0.05, 0.01, 2.0), Metrics(-0.048, 0.0, -0.061, 0.0, 12, 9, 0.25, 0.06)),
            VariantUi("Your settings (auto coin)", curve(200, -0.09, 0.01, 3.0), Metrics(-0.087, 0.0, -0.095, 0.0, 31, 22, 0.29, 0.18)),
        ),
        verdict = "Your settings trailed buy-and-hold here (-8.7% vs +4.1%), with a worst drop of -9.5% vs -6.1%. Past results " +
            "don't predict future ones, and tuning settings until a backtest looks good usually makes live results worse.",
    )

    @Composable
    private fun Page(content: @Composable ColumnScope.() -> Unit) = AppTheme {
        Column(Modifier.background(Palette.Background).padding(16.dp), content = content)
    }

    @Test fun home() = paparazzi.snapshot { Page { HomeContent(home, {}, {}, {}, {}, {}) } }

    @Test fun homeNoKeys() = paparazzi.snapshot { Page { HomeContent(home.copy(hasKeys = false), {}, {}, {}, {}, {}) } }

    @Test fun backtest() = paparazzi.snapshot { Page { BacktestContent(backtest) {} } }

    @Test fun settingsFast() = paparazzi.snapshot {
        Page { SettingsContent(settings, true, 1000.0, Tax(0.22), { _, _ -> }, {}, {}, {}, {}) }
    }

    @Test fun settingsPortfolioNoKeys() = paparazzi.snapshot {
        Page { SettingsContent(Settings(mode = "portfolio"), false, 0.0, Tax(0.22), { _, _ -> }, {}, {}, {}, {}) }
    }
}

/** Phone-sized shots with the top bar and bottom navigation. */
class FrameScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.NoActionBar", showSystemUi = false)

    @Test fun homeFrame() = paparazzi.snapshot {
        AppTheme {
            AppFrame(0, {}) { HomeContent(Samples.home, {}, {}, {}, {}, {}) }
        }
    }
}

object Samples {
    private const val now = 1_793_000_000_000L
    val settings = Settings(mode = "fast", autoCoin = true)

    val home = HomeUi(
        strategyName = strategyName(settings),
        strategyDetail = strategyDetail(settings, "SOL/USD"),
        hasKeys = true,
        running = true,
        runningHint = "Checks every 30 seconds with a notification while on (uses more battery).",
        overview = OverviewUi(
            pl = ProfitLoss(1000.0, 38.40, listOf(Holding("SOL/USD", 2.1, 182.40, 186.15))),
            since = "Oct 24, 2026",
            accountEquity = 100_412.0,
            today = 212.55,
            history = (0 until 60).map { 100_000 + 300 * sin(it / 6.0) + it * 9.0 },
            tax = Tax(0.22),
        ),
        check = CheckUi("HOLD", "Holding; take profit $190.05, stop $184.58", listOf(
            "Paper equity" to "$100,412.00", "Cash" to "$99,640.00", "Price" to "$186.15", "Position" to "2.100000 @ $182.40")),
        liveStatus = "Last check, 2:04:51 AM: SOL/USD $186.15: Holding; take profit $190.05, stop $184.58",
        volatility = listOf(
            "BTC/USD" to Fast.Volatility(0.0042, 0.019, 0.0051),
            "ETH/USD" to Fast.Volatility(0.0071, 0.028, 0.0066),
            "SOL/USD" to Fast.Volatility(0.0153, 0.041, 0.0089),
            "LTC/USD" to Fast.Volatility(0.0060, 0.024, 0.0070),
            "DOGE/USD" to Fast.Volatility(0.0098, 0.037, 0.0102),
        ),
        activeCoin = "SOL/USD",
        log = listOf(
            BotStore.LogEntry(now - 60_000, "BUY $500.00 of SOL/USD at about $182.40. New 20-minute high in a short uptrend"),
            BotStore.LogEntry(now - 600_000, "Switched to SOL/USD: it's moving most right now (typical 1-hour move 1.53% vs 0.42% for BTC/USD)"),
            BotStore.LogEntry(now - 3_600_000, "SELL all BTC/USD at about $67,410.00. Take profit at $67,410.00"),
            BotStore.LogEntry(now - 7_200_000, "Bot started"),
        ),
    )
}
