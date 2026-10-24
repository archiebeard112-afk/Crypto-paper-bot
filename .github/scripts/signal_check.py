"""Print what the bot would decide right now for each coin, using Alpaca's public crypto data.

Mirrors core/src/main/kotlin/bot (Strategy.kt and Scalp.kt) with the app's default settings.
"""
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

SYMBOLS = ["BTC/USD", "ETH/USD", "SOL/USD", "LTC/USD", "DOGE/USD"]


def bars(symbol, timeframe, days, seconds):
    start = (datetime.now(timezone.utc) - timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")
    out, token = [], None
    while True:
        q = {"symbols": symbol, "timeframe": timeframe, "start": start, "limit": 10000, "sort": "asc"}
        if token:
            q["page_token"] = token
        url = "https://data.alpaca.markets/v1beta3/crypto/us/bars?" + urllib.parse.urlencode(q)
        page = json.load(urllib.request.urlopen(url, timeout=30))
        out += page["bars"].get(symbol, [])
        token = page.get("next_page_token")
        if not token:
            break
    now = time.time()
    return [b for b in out if datetime.fromisoformat(b["t"].replace("Z", "+00:00")).timestamp() + seconds <= now]


def sma(xs, n):
    return sum(xs[-n:]) / n if len(xs) >= n else float("nan")


def rsi(xs, n=14):
    gain = loss = 0.0
    for i in range(1, n + 1):
        d = xs[i] - xs[i - 1]
        gain, loss = gain + max(d, 0), loss + max(-d, 0)
    gain, loss = gain / n, loss / n
    for i in range(n + 1, len(xs)):
        d = xs[i] - xs[i - 1]
        gain = (gain * (n - 1) + max(d, 0)) / n
        loss = (loss * (n - 1) + max(-d, 0)) / n
    return 100.0 if loss == 0 else 100 - 100 / (1 + gain / loss)


lines = [f"Checked {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC"]
for sym in SYMBOLS:
    d = [b["c"] for b in bars(sym, "1Day", 170, 86400)]
    f, s = sma(d, 20), sma(d, 100)
    trend = "BUY" if f > s else "no buy"
    q = [b["c"] for b in bars(sym, "15Min", 4, 900)]
    r, t200 = rsi(q), sma(q, 200)
    lows = [rsi(q[: i + 1]) for i in range(len(q) - 96, len(q))]  # RSI over the last 24h
    up = q[-1] > t200
    scalp = "BUY" if (r < 30 and up) else "no buy"
    lines.append(
        f"{sym}: price {d[-1]:,.4g} | Trend: 20d avg {f:,.4g} vs 100d avg {s:,.4g} -> {trend} "
        f"({len(d)} daily bars) | Scalp: RSI {r:.0f}, lowest RSI in 24h {min(lows):.0f}, "
        f"price {'above' if up else 'below'} 200-candle avg {t200:,.4g} -> {scalp} ({len(q)} candles)"
    )
print("\n".join(lines))
open("signal_report.txt", "w").write("\n".join(lines))
