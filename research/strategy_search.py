# Strategy search used to pick the "Trend portfolio" model. Usage: python strategy_search.py <dir with data/ from the research-data release>
import glob, itertools, sys
import numpy as np, pandas as pd

D = sys.argv[1]
FEE = 0.0025
IS_END, OOS_START = "2023-12-31", "2024-01-01"
COINS = ["BTCUSDT", "ETHUSDT", "SOLUSDT"]

def load(sym, tf):
    fs = sorted(glob.glob(f"{D}/data/{sym}/{tf}/*.csv"))
    df = pd.concat(pd.read_csv(f, header=None, usecols=[0,1,2,3,4]) for f in fs)
    df.columns = ["t","open","high","low","close"]
    df["t"] = np.where(df.t > 1e14, df.t // 1000, df.t)
    df.index = pd.to_datetime(df.t, unit="ms"); return df.drop(columns="t").sort_index()

daily = {c: load(c, "1d") for c in COINS}
hourly = {c: load(c, "1h") for c in COINS}

def run(close, pos, ppy):
    """pos: target exposure decided at close t (0..1), held over t+1. Returns net returns series."""
    ret = close.pct_change().fillna(0)
    held = pos.shift(1).fillna(0)
    cost = held.diff().abs().fillna(held.abs()) * FEE
    return held * ret - cost

def stats(r, ppy):
    r = r.dropna()
    if len(r) < 10: return dict(cagr=np.nan, sharpe=np.nan, dd=np.nan, tot=np.nan)
    eq = (1 + r).cumprod()
    yrs = len(r) / ppy
    sd = r.std()
    return dict(tot=eq.iloc[-1]-1, cagr=eq.iloc[-1]**(1/yrs)-1, sharpe=r.mean()/sd*np.sqrt(ppy) if sd>0 else 0, dd=(eq/eq.cummax()-1).min())

def voltarget(close, pos, target, ppy, win=30):
    vol = close.pct_change().rolling(win).std() * np.sqrt(ppy)
    return (pos * (target / vol).clip(upper=1.0)).fillna(0)

# ---- strategy families: f(close, params) -> pos
def sma_cross(c, p): return (c.rolling(p["f"]).mean() > c.rolling(p["s"]).mean()).astype(float)
def above_sma(c, p): return (c > c.rolling(p["n"]).mean()).astype(float)
def tsmom(c, p): return (c / c.shift(p["n"]) - 1 > 0).astype(float)
def donchian(c, p):
    hi = c.rolling(p["n"]).max().shift(1); lo = c.rolling(p["m"]).min().shift(1)
    pos = pd.Series(np.nan, index=c.index); pos[c > hi] = 1; pos[c < lo] = 0
    return pos.ffill().fillna(0)

FAM = {
  "SMA crossover": (sma_cross, [dict(f=f, s=s) for f in [5,10,20,30,50] for s in [50,100,150,200] if f < s]),
  "Price above SMA": (above_sma, [dict(n=n) for n in [20,30,50,75,100,150,200]]),
  "Momentum (N-day return > 0)": (tsmom, [dict(n=n) for n in [10,20,30,60,90,120,180]]),
  "Breakout (Donchian)": (donchian, [dict(n=n, m=m) for n in [20,30,50,100] for m in [10,20,30,50] if m <= n]),
}

def evaluate(name, fn, grid, data, ppy, vt=None):
    rows = []
    for p in grid:
        per = {}
        for coin, df in data.items():
            c = df.close
            pos = fn(c, p)
            if vt: pos = voltarget(c, pos, vt, ppy)
            per[coin] = run(c, pos, ppy)
        port = pd.concat(per, axis=1).fillna(0).mean(axis=1)  # equal-weight sleeves
        rows.append((p, per, port))
    def isr(x): return x[:IS_END]
    def oos(x): return x[OOS_START:]
    best = max(rows, key=lambda r: stats(isr(r[2]), ppy)["sharpe"])
    all_oos = [stats(oos(r[2]), ppy)["sharpe"] for r in rows]
    return best, np.median(all_oos), np.mean([s > 0 for s in all_oos])

def bh(data, ppy):
    per = {coin: run(df.close, pd.Series(1.0, index=df.index), ppy) for coin, df in data.items()}
    return per, pd.concat(per, axis=1).fillna(0).mean(axis=1)

def fmt(s): return f"tot {s['tot']*100:7.0f}%  CAGR {s['cagr']*100:6.1f}%  Sharpe {s['sharpe']:5.2f}  maxDD {s['dd']*100:6.1f}%"

out = []
def P(x=""): out.append(x); print(x)

for label, data, ppy in [("DAILY", daily, 365), ("HOURLY", hourly, 365*24)]:
    P(f"===== {label} candles, fee {FEE*100:.2f}% per trade, in-sample to {IS_END}, out-of-sample from {OOS_START}")
    per, port = bh(data, ppy)
    P(f"Buy & hold, equal weight   IS: {fmt(stats(port[:IS_END], ppy))}")
    P(f"                          OOS: {fmt(stats(port[OOS_START:], ppy))}")
    for coin in COINS: P(f"   B&H {coin} OOS: {fmt(stats(per[coin][OOS_START:], ppy))}")
    fams = FAM if label == "DAILY" else {
        "SMA crossover (hours)": (sma_cross, [dict(f=f, s=s) for f in [12,24,48,72] for s in [96,168,240,336,480] if f < s]),
        "Price above SMA (hours)": (above_sma, [dict(n=n) for n in [48,96,168,240,336,480,720]]),
    }
    for vt in [None, 0.5]:
        for name, (fn, grid) in fams.items():
            (p, perc, port), med, frac = evaluate(name, fn, grid, data, ppy, vt)
            tag = f"{name}{' + vol target 50%' if vt else ''}"
            P(f"{tag}: best in-sample params {p}")
            P(f"     IS: {fmt(stats(port[:IS_END], ppy))}")
            P(f"    OOS: {fmt(stats(port[OOS_START:], ppy))}   | all params OOS Sharpe median {med:.2f}, {frac*100:.0f}% positive")
            P("     OOS by coin: " + "  ".join(f"{c[:3]} {stats(perc[c][OOS_START:], ppy)['tot']*100:.0f}%" for c in COINS))
    P()

# ---- rotation: hold the coin with the best N-day momentum if positive
P("===== ROTATION (daily): hold the strongest of BTC/ETH/SOL by N-day return, cash if all negative")
closes = pd.concat({c: daily[c].close for c in COINS}, axis=1)
rets = closes.pct_change().fillna(0)
best_is, rows = None, []
for n in [10,20,30,60,90]:
    for top in [1, 2]:
        mom = closes / closes.shift(n) - 1
        rank = mom.rank(axis=1, ascending=False)
        w = ((rank <= top) & (mom > 0)).astype(float) / top
        held = w.shift(1).fillna(0)
        r = (held * rets).sum(axis=1) - held.diff().abs().sum(axis=1).fillna(0) * FEE
        rows.append((dict(n=n, top=top), r))
b = max(rows, key=lambda x: stats(x[1][:IS_END], 365)["sharpe"])
P(f"best IS params {b[0]}\n     IS: {fmt(stats(b[1][:IS_END], 365))}\n    OOS: {fmt(stats(b[1][OOS_START:], 365))}")
P("     all params OOS Sharpe: " + " ".join(f"{p['n']}/{p['top']}:{stats(r[OOS_START:],365)['sharpe']:.2f}" for p, r in rows))
open(f"{D}/report.txt", "w").write("\n".join(out))
