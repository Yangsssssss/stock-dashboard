"""技术指标计算与多因子评分引擎。

输入统一为日线序列 [[date, open, close, high, low, volume], ...]（按时间升序）。
所有函数只依赖标准库，便于离线运行。
"""
from __future__ import annotations

import math
from typing import List, Optional, Sequence

Bar = Sequence  # [date, open, close, high, low, volume]


# ---------------------------------------------------------------- 基础工具
def sma(vals: Sequence[float], n: int) -> List[Optional[float]]:
    out: List[Optional[float]] = []
    s = 0.0
    for i, v in enumerate(vals):
        s += v
        if i >= n:
            s -= vals[i - n]
        out.append(s / n if i >= n - 1 else None)
    return out


def ema(vals: Sequence[float], n: int) -> List[float]:
    if not vals:
        return []
    k = 2.0 / (n + 1)
    out = [vals[0]]
    for v in vals[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def stdev(vals: Sequence[float]) -> float:
    n = len(vals)
    if n < 2:
        return 0.0
    m = sum(vals) / n
    return math.sqrt(sum((v - m) ** 2 for v in vals) / (n - 1))


def rsi(closes: Sequence[float], n: int = 14) -> Optional[float]:
    if len(closes) < n + 1:
        return None
    gains, losses = 0.0, 0.0
    for i in range(1, n + 1):
        d = closes[i] - closes[i - 1]
        gains += max(d, 0.0)
        losses += max(-d, 0.0)
    ag, al = gains / n, losses / n
    for i in range(n + 1, len(closes)):
        d = closes[i] - closes[i - 1]
        ag = (ag * (n - 1) + max(d, 0.0)) / n
        al = (al * (n - 1) + max(-d, 0.0)) / n
    if al == 0:
        return 100.0
    rs = ag / al
    return 100 - 100 / (1 + rs)


def macd(closes: Sequence[float], fast: int = 12, slow: int = 26, sig: int = 9):
    if len(closes) < slow:
        return None
    ef, es = ema(closes, fast), ema(closes, slow)
    dif = [a - b for a, b in zip(ef, es)]
    dea = ema(dif, sig)
    hist = [(a - b) * 2 for a, b in zip(dif, dea)]
    return {"dif": dif[-1], "dea": dea[-1], "hist": hist[-1],
            "hist_prev": hist[-2] if len(hist) > 1 else 0.0,
            "dif_prev": dif[-2] if len(dif) > 1 else 0.0}


def atr(bars: Sequence[Bar], n: int = 14) -> Optional[float]:
    if len(bars) < n + 1:
        return None
    trs = []
    for i in range(1, len(bars)):
        h, l, pc = float(bars[i][3]), float(bars[i][4]), float(bars[i - 1][2])
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    if len(trs) < n:
        return None
    a = sum(trs[:n]) / n
    for t in trs[n:]:
        a = (a * (n - 1) + t) / n
    return a


def boll(closes: Sequence[float], n: int = 20, k: float = 2.0):
    if len(closes) < n:
        return None
    seg = closes[-n:]
    mid = sum(seg) / n
    sd = stdev(seg)
    return {"mid": mid, "up": mid + k * sd, "low": mid - k * sd, "sd": sd}


def pct(a: float, b: float) -> float:
    if not b:
        return 0.0
    return (a / b - 1) * 100


# ---------------------------------------------------------------- 主分析
def analyze(bars: Sequence[Bar], benchmark_bars: Optional[Sequence[Bar]] = None) -> dict:
    """产出技术面指标 + 0~100 综合评分 + 信号标签 + 文字结论。"""
    if not bars or len(bars) < 65:
        return {"ok": False, "reason": "K线数据不足"}

    closes = [float(b[2]) for b in bars]
    vols = [float(b[5]) for b in bars]
    price = closes[-1]

    ma5 = sma(closes, 5)[-1]
    ma10 = sma(closes, 10)[-1]
    ma20 = sma(closes, 20)[-1]
    ma60 = sma(closes, 60)[-1]
    v5 = sum(vols[-5:]) / 5
    v20 = sum(vols[-20:]) / 20
    vol_ratio = v5 / v20 if v20 else 0.0

    r5 = pct(price, closes[-6]) if len(closes) > 6 else 0.0
    r20 = pct(price, closes[-21]) if len(closes) > 21 else 0.0
    r60 = pct(price, closes[-61]) if len(closes) > 61 else 0.0
    rsi14 = rsi(closes)
    m = macd(closes)
    at = atr(bars)
    bb = boll(closes)

    rets = [closes[i] / closes[i - 1] - 1 for i in range(1, len(closes))]
    vol_ann = stdev(rets[-60:]) * math.sqrt(252) * 100 if len(rets) >= 20 else 0.0
    hi60 = max(closes[-60:])
    lo60 = min(closes[-60:])
    dd = pct(price, hi60)          # 距 60 日高点（负数为回撤）
    pos60 = (price - lo60) / (hi60 - lo60) * 100 if hi60 > lo60 else 50.0

    # 相对强弱：20 日超额收益
    rs = None
    if benchmark_bars and len(benchmark_bars) > 21:
        bc = [float(b[2]) for b in benchmark_bars]
        rs = r20 - pct(bc[-1], bc[-21])

    # ---------------- 因子拆解评分 ---------------
    notes: List[str] = []

    def cl(v, lo, hi):
        return max(lo, min(hi, v))

    # 1) 趋势：均线结构 + 区间位置
    trend = 50.0
    if ma20:
        trend += 16 if price > ma20 else -16
    if ma20 and ma60:
        if ma20 > ma60:
            trend += 14
            notes.append("中期趋势向上（MA20>MA60）")
        else:
            trend -= 14
            notes.append("中期趋势向下（MA20<MA60）")
    if ma5 and ma10 and ma20:
        if ma5 > ma10 > ma20:
            trend += 8
            notes.append("短中期均线多头排列")
        elif ma5 < ma10 < ma20:
            trend -= 8
            notes.append("短中期均线空头排列")
    trend += (pos60 - 50) * 0.4
    trend = cl(trend, 0, 100)

    # 2) 动量：区间涨幅 + RSI + 相对强弱
    momentum = 50 + cl(r20 * 1.3, -32, 32) + cl(r5 * 1.1, -18, 18)
    if rsi14 is not None:
        if 50 <= rsi14 <= 70:
            momentum += 8
        elif 70 < rsi14 <= 80:
            momentum += 2
        elif rsi14 > 80:
            momentum -= 12
            notes.append(f"RSI {rsi14:.0f} 进入超买区")
        elif rsi14 < 30:
            momentum += 5
            notes.append(f"RSI {rsi14:.0f} 处于超卖区")
    if rs is not None:
        momentum += cl(rs * 1.5, -8, 8)
        if rs > 3:
            notes.append(f"20 日跑赢基准 {rs:.1f}pct")
        elif rs < -3:
            notes.append(f"20 日跑输基准 {abs(rs):.1f}pct")
    momentum = cl(momentum, 0, 100)

    # 3) 量能：量比方向配合 + MACD 动能
    volume_f = 50.0
    if vol_ratio >= 1.3:
        if r5 > 0:
            volume_f += 22
            notes.append(f"放量上涨（量比 {vol_ratio:.1f}）")
        else:
            volume_f -= 22
            notes.append(f"放量下跌（量比 {vol_ratio:.1f}）")
    elif vol_ratio <= 0.7:
        volume_f -= 6
        notes.append("成交明显缩量")
    if m:
        if m["hist"] > 0 and m["hist"] >= m["hist_prev"]:
            volume_f += 16
            notes.append("MACD 红柱走强")
        elif m["hist"] > 0:
            volume_f += 8
        else:
            volume_f -= 16
            notes.append("MACD 绿柱，动能偏弱")
    volume_f = cl(volume_f, 0, 100)

    # 4) 风险：波动率与回撤，波动越低、回撤越小越安全
    risk = 50 - max(0.0, vol_ann - 25) * 0.7 + min(12.0, max(0.0, 25 - vol_ann) * 0.45) \
        - max(0.0, -dd - 8) * 0.9
    if at and price and (at / price * 100) < 1.5:
        risk += 6
    risk = cl(risk, 0, 100)

    factors = {"trend": round(trend, 1), "momentum": round(momentum, 1),
               "volume": round(volume_f, 1), "risk": round(risk, 1)}
    score = round(0.34 * trend + 0.30 * momentum + 0.16 * volume_f + 0.20 * risk, 1)
    score = cl(score, 0.0, 100.0)

    if score >= 72:
        sig, cls = "强势", "strong"
    elif score >= 58:
        sig, cls = "偏强", "good"
    elif score >= 44:
        sig, cls = "中性", "neutral"
    elif score >= 30:
        sig, cls = "偏弱", "weak"
    else:
        sig, cls = "弱势", "bad"

    # 结论一句话（趋势 + 涨幅 + 量能 + 最多两条补充信号）
    parts: List[str] = []
    if ma20 and ma60:
        parts.append("中期趋势向上" if ma20 > ma60 else "中期趋势仍在下行")
    parts.append(f"20 日 {r20:+.1f}%")
    if vol_ratio:
        parts.append("量能放大" if vol_ratio > 1.25 else ("量能萎缩" if vol_ratio < 0.75 else "量能平稳"))
    extra = [n for n in notes if not n.startswith(("中期趋势", "短中期均线"))]
    conclusion = "，".join(parts)
    if extra:
        conclusion += "。" + "；".join(extra[:2]) + "。"

    return {
        "ok": True,
        "score": score,
        "signal": sig,
        "signal_cls": cls,
        "factors": factors,
        "conclusion": conclusion,
        "ma5": _r(ma5), "ma10": _r(ma10), "ma20": _r(ma20), "ma60": _r(ma60),
        "r5": round(r5, 2), "r20": round(r20, 2), "r60": round(r60, 2),
        "rsi": round(rsi14, 1) if rsi14 is not None else None,
        "macd": {k: round(v, 4) for k, v in m.items()} if m else None,
        "atr": round(at, 3) if at else None,
        "atr_pct": round(at / price * 100, 2) if at and price else None,
        "vol_ratio": round(vol_ratio, 2),
        "vol_ann": round(vol_ann, 1),
        "dd60": round(dd, 1),
        "pos60": round(pos60, 1),
        "rs": round(rs, 2) if rs is not None else None,
        "boll": {"up": round(bb["up"], 3), "mid": round(bb["mid"], 3),
                 "low": round(bb["low"], 3)} if bb else None,
        "support": round(lo60, 3),
        "resistance": round(hi60, 3),
    }


def _r(v: Optional[float]) -> Optional[float]:
    return round(v, 3) if v is not None else None


def correlation(series: dict) -> dict:
    """series: {name: [close...]}，按最后 N 个交易日对齐算收益率相关性。"""
    names = list(series.keys())
    n = min((len(v) for v in series.values()), default=0)
    if n < 25:
        return {"labels": names, "matrix": [[0] * len(names) for _ in names]}
    n = min(n, 60)
    rets = {}
    for k, v in series.items():
        seg = v[-n:]
        rets[k] = [seg[i] / seg[i - 1] - 1 for i in range(1, len(seg))]

    def corr(a, b):
        m = len(a)
        ma, mb = sum(a) / m, sum(b) / m
        cov = sum((a[i] - ma) * (b[i] - mb) for i in range(m))
        va = math.sqrt(sum((x - ma) ** 2 for x in a))
        vb = math.sqrt(sum((x - mb) ** 2 for x in b))
        return round(cov / (va * vb), 2) if va and vb else 0.0

    return {"labels": names,
            "matrix": [[corr(rets[a], rets[b]) for b in names] for a in names]}
