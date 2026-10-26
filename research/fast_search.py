# Fast-mode (1-minute) search. Data: the research-data release (1m folders).
# Fast-mode research on 1-minute candles. Usage: python fast.py <dir containing data/>
import glob, sys, itertools
import numpy as np, pandas as pd
from numba import njit

D = sys.argv[1]
COINS = ["BTCUSDT", "ETHUSDT", "SOLUSDT"]
TEST_FROM = "2026-09-01"  # tune on Jul-Aug, check on Sep

def load(sym):
    fs = sorted(glob.glob(f"{D}/data/{sym}/1m/*.csv"))
    df = pd.concat(pd.read_csv(f, header=None, usecols=[0,1,2,3,4]) for f in fs)
    df.columns = ["t","open","high","low","close"]
    df["t"] = np.where(df.t > 1e14, df.t // 1000, df.t)
    df.index = pd.to_datetime(df.t, unit="ms"); return df.drop(columns="t").sort_index()

def ema(x, n): return x.ewm(span=n, adjust=False).mean()
def rsi(c, n=14):
    d = c.diff(); up = d.clip(lower=0).ewm(alpha=1/n, adjust=False).mean(); dn = (-d.clip(upper=0)).ewm(alpha=1/n, adjust=False).mean()
    return 100 - 100 / (1 + up / dn)

def signals(df):
    c = df.close
    e9, e21 = ema(c, 9), ema(c, 21)
    cross = (e9 > e21) & (e9.shift() <= e21.shift())
    r = rsi(c)
    vol60 = np.log(c).diff().rolling(60).std() * np.sqrt(60)  # typical 1-hour move
    return {
        "breakout": (c > df.high.rolling(20).max().shift(1)) & (e9 > e21),
        "dip_turn_up": (r.rolling(10).min() < 30) & cross,
        "ema_cross": cross,
        "random_every_30m": pd.Series(np.arange(len(c)) % 30 == 0, index=c.index),
    }, vol60

@njit
def sim(o, h, l, c, sig, tp, sl, hold, fee):
    eq = 1.0; i = 0; n = len(c); trades = 0; wins = 0
    while i < n - 2:
        if not sig[i]:
            i += 1; continue
        entry = o[i + 1]; j = i + 1
        while True:
            stop = entry * (1 - sl); take = entry * (1 + tp)
            if l[j] <= stop:
                px = min(stop, o[j]); break
            if h[j] >= take:
                px = max(take, o[j]); break
            if j - i >= hold or j == n - 1:
                px = c[j]; break
            j += 1
        r = px * (1 - fee) / (entry * (1 + fee)) - 1
        eq *= 1 + r; trades += 1
        if r > 0: wins += 1
        i = j + 1
    return eq - 1, trades, wins

data = {c: load(c) for c in COINS}
TPSL = [(0.002, 0.002), (0.003, 0.003), (0.005, 0.005), (0.005, 0.003), (0.01, 0.005), (0.01, 0.01), (0.02, 0.01)]
VOLF = [0.0, 0.005, 0.01]
FEES = [0.0025, 0.001, 0.0]
rows = []
for coin, df in data.items():
    sigs, vol = signals(df)
    for name, s in sigs.items():
        for vf in VOLF:
            mask = (s & (vol > vf)).fillna(False).values
            for (tp, sl) in TPSL:
                for fee in FEES:
                    for part, sel in [("tune", df.index < TEST_FROM), ("test", df.index >= TEST_FROM)]:
                        d = df[sel]; m = mask[sel]
                        ret, n, w = sim(d.open.values, d.high.values, d.low.values, d.close.values, m, tp, sl, 60, fee)
                        rows.append(dict(coin=coin[:3], rule=name, volf=vf, tp=tp, sl=sl, fee=fee, part=part, ret=ret, trades=n, win=w / n if n else np.nan))
R = pd.DataFrame(rows)
R.to_csv(f"{D}/fast_results.csv", index=False)
months = {"tune": 2, "test": 1}
R["trades_per_day"] = R.apply(lambda x: x.trades / (months[x.part] * 30.5), axis=1)

pd.set_option("display.width", 200)
print("Share of configs with positive return, by fee (all rules/coins/params):")
print(R.groupby(["fee", "part"]).ret.apply(lambda s: f"{(s > 0).mean()*100:.0f}% positive, median {s.median()*100:.1f}%").unstack())
print("\nBuy & hold per coin:", {c: f"{(df.close.iloc[-1]/df.close.iloc[0]-1)*100:.1f}%" for c, df in data.items()},
      " test month:", {c: f"{(df[TEST_FROM:].close.iloc[-1]/df[TEST_FROM:].close.iloc[0]-1)*100:.1f}%" for c, df in data.items()})
# best per rule chosen on tune (averaged over coins) at 0.25% fee, then report test
f = R[R.fee == 0.0025]
key = ["rule", "volf", "tp", "sl"]
tune = f[f.part == "tune"].groupby(key).ret.mean()
test = f[f.part == "test"].groupby(key).agg(ret=("ret", "mean"), tpd=("trades_per_day", "mean"), win=("win", "mean"))
print("\nAt 0.25% fee: best settings per rule (picked on Jul-Aug, averaged over BTC/ETH/SOL), then September result")
for rule in f.rule.unique():
    b = tune.xs(rule, level="rule").idxmax(); k = (rule,) + b
    print(f"  {rule:18s} vol>{b[0]:.3f} TP {b[1]*100:.1f}% SL {b[2]*100:.1f}% | tune {tune[k]*100:6.1f}% | Sep {test.loc[k,'ret']*100:6.1f}%, {test.loc[k,'tpd']:.1f} trades/day, win {test.loc[k,'win']*100:.0f}%")
for fee in [0.001, 0.0]:
    g = R[R.fee == fee]; tune2 = g[g.part == "tune"].groupby(key).ret.mean(); test2 = g[g.part == "test"].groupby(key).ret.mean()
    b = tune2.idxmax()
    print(f"At {fee*100:.2f}% fee, best on tune {b}: tune {tune2[b]*100:.1f}%, Sep {test2[b]*100:.1f}%")
# how many trades/day for the "lots of trades" settings
print("\nTrades per day, 0.25% fee, no vol filter, TP/SL 0.3%:")
print(f[(f.volf == 0) & (f.tp == 0.003) & (f.sl == 0.003)].groupby(["rule", "part"]).agg(tpd=("trades_per_day", "mean"), ret=("ret", "mean"), win=("win", "mean")))
