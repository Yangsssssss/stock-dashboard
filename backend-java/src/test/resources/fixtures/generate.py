"""生成 Java 侧测试用的金标准数据（golden file）。

用途：Java 的 Indicators / AnalysisService 注释里写着「与 Python 版逐条对齐」，
这个脚本用 legacy/quant/indicators.py 这个「老真相」算出期望值，落成 JSON，
Java 测试再拿同一份 bars 跑一遍做对拍（differential test）。

价格序列是确定性合成数据（无随机数），任何人任何机器重跑结果一致。

用法：
    python3 backend-java/src/test/resources/fixtures/generate.py

产物（与本脚本同目录，已入库，测试运行时不依赖 Python）：
    bars_120.csv        120 根日线：date,open,close,high,low,volume
    bars_bench.csv      同期基准指数 120 根
    expected.json       Python 版各个指标的期望输出
"""
from __future__ import annotations

import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[4]                      # fixtures -> resources -> test -> src -> backend-java -> stock-dashboard/
sys.path.insert(0, str(ROOT / "legacy"))

from quant import indicators as ind  # noqa: E402


def bars(n: int, base: float, drift: float, wave: float, seed: float) -> list[list]:
    """确定性合成日线：趋势漂移 + 正弦波动。"""
    out = []
    for i in range(n):
        close = base * (1 + drift * i) + wave * math.sin(i / 7.0 + seed)
        open_ = close - wave * 0.3 * math.cos(i / 5.0 + seed)
        high = max(open_, close) + wave * 0.4 * (1 + 0.2 * math.sin(i / 3.0))
        low = min(open_, close) - wave * 0.4 * (1 + 0.2 * math.cos(i / 4.0))
        vol = 1_000_000 * (1 + 0.35 * math.sin(i / 5.0 + seed))
        out.append([f"2025-{1 + i // 28:02d}-{1 + i % 28:02d}",
                    round(open_, 4), round(close, 4), round(high, 4), round(low, 4), round(vol)])
    return out


def flat(v) -> list:
    """把 [[d,o,c,h,l,v],...] 展开成 CSV 行。"""
    return [[str(x) for x in row] for row in v]


def main() -> None:
    main_bars = bars(120, base=100.0, drift=0.0015, wave=3.0, seed=0.0)
    bench = bars(120, base=3800.0, drift=0.0004, wave=25.0, seed=1.3)

    closes = [b[2] for b in main_bars]
    a = ind.analyze(main_bars, bench)

    expected = {
        "_generated_by": "legacy/quant/indicators.py（Python 版，Java 版以此为准对拍）",
        "primitives": {
            "sma5_last": ind.sma(closes, 5)[-1],
            "sma20_last": ind.sma(closes, 20)[-1],
            "sma60_last": ind.sma(closes, 60)[-1],
            "ema12_last": ind.ema(closes, 12)[-1],
            "stdev_last20": ind.stdev(closes[-20:]),
            "rsi14": ind.rsi(closes, 14),
            "macd": ind.macd(closes, 12, 26, 9),
            "atr14": ind.atr(main_bars, 14),
            "boll": ind.boll(closes, 20, 2.0),
        },
        "analyze": a,
        "correlation": ind.correlation({
            "MAIN": closes,
            "BENCH": [b[2] for b in bench],
            "MAIN2": [c * 1.01 for c in closes],
        }),
    }

    (HERE / "bars_120.csv").write_text(
        "date,open,close,high,low,volume\n"
        + "\n".join(",".join(r) for r in flat(main_bars)) + "\n", encoding="utf-8")
    (HERE / "bars_bench.csv").write_text(
        "date,open,close,high,low,volume\n"
        + "\n".join(",".join(r) for r in flat(bench)) + "\n", encoding="utf-8")
    (HERE / "expected.json").write_text(
        json.dumps(expected, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")

    print("bars_120.csv / bars_bench.csv / expected.json 已生成")
    print("score =", a.get("score"), a.get("signal"))


if __name__ == "__main__":
    main()
