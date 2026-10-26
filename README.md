# Crypto Paper Bot

An Android app that runs a simple crypto trading strategy on an **Alpaca paper account**. It only ever trades fake money: the trading URL is hard-coded to Alpaca's paper endpoint, and the app refuses to run if the account is not a paper account.

## What it does

- **Trend portfolio (recommended):** the model that held up best in out-of-sample testing. One third of the bot's money each in BTC, ETH and SOL; hold a coin only while it is above its 150-day average, sized down when it is volatile (target ~50% yearly swings), rebalanced when a holding drifts more than 10% from target.

- **Strategy:** moving-average crossover on daily closes. Buy when the fast average (default 20 days) rises above the slow one (default 100 days); sell when it falls back below.
- **Risk rules:**
  - Position cap: a buy uses at most 50% of account equity (adjustable). The cap applies when buying; a winning position can grow past it.
  - Stop loss: sell if the price falls 15% below the entry price (adjustable). A stop-limit order rests at Alpaca, and the bot also checks the stop itself every 15 minutes.
  - After an exit, no re-entry until the next fresh cross up.
- **Scalp mode (optional):** on 15-minute candles, buys sharp dips (RSI below 30, only while the ~2-day trend is up), then sells at +1.5% (a resting limit order), -1.5%, or after 6 hours. Optional "dip then turn up" entry waits for the price to start rising first. All adjustable.
- **Fast mode (optional):** 1-minute candles, buys a 20-minute breakout (or a dip that turns up), sells at your take-profit, your stop-loss or a time limit. Can skip calm markets with a minimum 1-hour move. Runs every 30 seconds in a foreground service with an ongoing notification while switched on.
- **Market volatility panel:** typical 1-hour move per coin now vs a normal hour over the past week (Calm / Normal / Busy).
- **Runs in the background** every 15 minutes (Android's minimum) and sends a notification for every trade.
- **Starting amount and profit/loss:** set how much paper money the bot trades with; the Bot tab shows profit or loss since then (closed trades and open position), plus the whole account's value over the last month.
- **Backtest tab:** replays the strategy on daily history from Alpaca and compares it with buy-and-hold, including fees.

Coins: BTC, ETH, SOL, LTC and DOGE against USD.

## Install

1. Download `CryptoPaperBot.apk` from the [latest release](../../releases/latest) on your phone.
2. Open it and allow installing from your browser when Android asks.
3. Create a free account at [alpaca.markets](https://alpaca.markets), switch to your **Paper** account, and generate API keys.
4. In the app, open **Settings** and paste the key ID and secret. They are encrypted with the Android Keystore and never leave the phone except to Alpaca.
5. On the **Bot** tab, tap **Check now** to test the keys, then turn on **Bot running**.

## Honest results so far

Monthly Bitcoin closes, Oct 2012 to Dec 2024, 2/10-month crossover, 0.25% fee per fill (`core/src/test/kotlin/bot/BitcoinReportTest.kt`):

|                               | Total return | Per year | Worst drop | Trades |
|-------------------------------|-------------:|---------:|-----------:|-------:|
| Buy and hold                  | 866,394%     | 110.7%   | -79.2%     | 0      |
| Crossover, all-in, no stop    | 784,741%     | 109.0%   | -72.6%     | 6      |
| Crossover, all-in, 15% stop   | 192,708%     | 86.2%    | -68.5%     | 7      |
| Crossover, 50% cap + 15% stop | 42,998%      | 64.6%    | -60.8%     | 7      |

The crossover did **not** beat buy-and-hold. It cut the worst drop somewhat, and the stop loss and position cap made returns lower because Bitcoin's dips are often deeper than 15% before it recovers. Run the in-app backtest for daily results on whichever coin you pick. Past results don't predict future ones.

### Scalp mode, last 12 months of real 15-minute candles

Oct 2025 to Sep 2026, Binance data, all-in, default settings (`.github/workflows/research.yml` reruns this on every change to `core/`):

|          | Buy and hold | Scalp at 0.25% fee | Scalp at 0.10% fee | Scalp with no fees | Scalp without trend filter |
|----------|-------------:|-------------------:|-------------------:|-------------------:|---------------------------:|
| BTC      | -30.6%       | -11.7%             | -3.6%              | +2.1%              | -83.2%                     |
| ETH      | -40.4%       | -5.8%              | +5.9%              | +14.4%             | -85.3%                     |
| SOL      | -49.2%       | -5.7%              | +4.1%              | +11.2%             | -84.5%                     |

In a falling year the scalper lost much less than holding, mostly because the trend filter kept it out of the market 98% of the time. It still lost money at Alpaca's fee: about 0.5% per round trip wipes out most of a 1.5% target. Without the trend filter it bought every dip on the way down and lost over 80%.

**Dip then turn up** (scalp option): instead of buying while RSI is below 30, wait until, within 2 hours of such a dip, the 9-candle average crosses above the 21-candle average. Same 12 months, 0.25% fee, all-in:

|     | Buy the dip, no filter | Dip then turn up, no filter | Buy the dip + trend filter | Dip then turn up + trend filter |
|-----|-----:|-----:|-----:|-----:|
| BTC | -83.2% | -11.7% | -11.7% | -3.3% |
| ETH | -85.3% | -20.9% | -5.8% | -10.2% |
| SOL | -84.5% | -12.3% | -5.7% | -5.6% |

Waiting for the turn up avoids most falling knives, but it did not reliably beat the trend filter, and none of the versions made money after fees. The filtered versions trade only 7 to 39 times a year, so differences between them are mostly luck.

### Trend portfolio: how it was chosen

`research/strategy_search.py` tested daily SMA crossovers, price-above-average, momentum, breakouts, coin rotation and hourly trend rules on Binance BTC/ETH/SOL candles from 2020. Parameters were picked on 2020-2023 only, then judged on Jan 2024 to Sep 2026, which the search never saw. Daily trend rules with volatility sizing did best out of sample, and every parameter setting of them was positive there; hourly rules lost money after fees. Kotlin backtest of the final model (`PortfolioTest`, 0.25% fee):

|                                   | Total   | Per year | Worst drop | Sharpe |
|-----------------------------------|--------:|---------:|-----------:|-------:|
| Trend portfolio, 2024 to Sep 2026 | +70%    | 21%      | -26%       | 0.78   |
| Buy and hold thirds, same period  | +10% to +54%* | 4% to 17% | -64% to -75% | 0.43 to 0.56 |
| Buy and hold BTC only             | +98%    | 28%      | -53%       | 0.76   |

\*+10% if bought once and left alone, +54% if rebalanced to equal thirds daily.

It is a "lose less in crashes" model: in 2021 it made +129% while holding made +1,150%, and in 2022 it lost 11% while holding lost 80%. Holding only Bitcoin made more since 2024, with twice the worst drop. Under three years of out-of-sample data is not proof it will keep working.

### Fast mode, 1-minute candles (Jul to Sep 2026)

`research/fast_search.py` tried breakout, dip-then-turn-up, average-cross and random entries with take-profit/stop-loss pairs from 0.2% to 2% and volatility filters, across BTC, ETH and SOL (over 1,000 combinations). At Alpaca's 0.25% fee, **none** made money, in the tuning months or the test month; at 0.10% about 1% did; with zero fees about half did, which is what chance looks like. The many-trades setting (+0.3% / -0.3%, ~25 trades a day) lost essentially everything in two months, because each round trip costs ~0.5%, more than the take-profit. Meanwhile buying and holding gained 42% to 71%.

## Project layout

- `core/`: plain Kotlin strategy, risk rules, backtest and the live decision logic, with unit tests. The backtest is checked against an independent Python version of the same rules.
- `app/`: the Android app (Jetpack Compose UI, WorkManager background job, Alpaca client).
- `.github/workflows/build.yml`: runs the tests, builds the APK and publishes it as the latest release on every push to `main`.

Build locally with Android Studio, or `./gradlew :core:test :app:assembleDebug` with the Android SDK installed.
