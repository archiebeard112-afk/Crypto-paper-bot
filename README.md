# Crypto Paper Bot

An Android app that runs a simple crypto trading strategy on an **Alpaca paper account**. It only ever trades fake money: the trading URL is hard-coded to Alpaca's paper endpoint, and the app refuses to run if the account is not a paper account.

## What it does

- **Strategy:** moving-average crossover on daily closes. Buy when the fast average (default 20 days) rises above the slow one (default 100 days); sell when it falls back below.
- **Risk rules:**
  - Position cap: a buy uses at most 50% of account equity (adjustable). The cap applies when buying; a winning position can grow past it.
  - Stop loss: sell if the price falls 15% below the entry price (adjustable). A stop-limit order rests at Alpaca, and the bot also checks the stop itself every 15 minutes.
  - After an exit, no re-entry until the next fresh cross up.
- **Runs in the background** every 15 minutes (Android's minimum) and sends a notification for every trade.
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

## Project layout

- `core/`: plain Kotlin strategy, risk rules, backtest and the live decision logic, with unit tests. The backtest is checked against an independent Python version of the same rules.
- `app/`: the Android app (Jetpack Compose UI, WorkManager background job, Alpaca client).
- `.github/workflows/build.yml`: runs the tests, builds the APK and publishes it as the latest release on every push to `main`.

Build locally with Android Studio, or `./gradlew :core:test :app:assembleDebug` with the Android SDK installed.
