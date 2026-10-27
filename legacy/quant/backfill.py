"""历史评分回填：用历史日线切片重新跑一遍评分模型，写入 daily_score。

用途：首次接入时让「评分历史」「评分变化榜」立刻有数据，而不是从零攒。
用法：python3 -m quant.backfill [天数]
"""
from __future__ import annotations

import sys
import time

from . import db, feed, indicators


def run(days: int = 30) -> int:
    if not db.init():
        print("数据库不可用")
        return 0
    t0 = time.time()
    total = 0
    for gkey, g in feed.UNIVERSE.items():
        bench = feed.fetch_kline(g["benchmark"], days=400)
        for code, name, _kind in g["items"]:
            bars = feed.fetch_kline(code, days=400)
            if len(bars) < 75:
                continue
            rows = []
            n = len(bars)
            for k in range(days, 0, -1):
                i = n - k                     # 用前 i 根（不含当日）回算
                if i < 70:
                    continue
                sub = bars[:i]
                bsub = bench[:i] if code != g["benchmark"] and len(bench) >= i else None
                a = indicators.analyze(sub, bsub)
                if not a.get("ok"):
                    continue
                f = a["factors"]
                rows.append((code, str(bars[i - 1][0]), a["score"], a["signal"],
                             f["trend"], f["momentum"], f["volume"], f["risk"],
                             a["r5"], a["r20"], a["rsi"]))
            total += db.upsert_score_rows(rows)
    print(f"回填 {total} 行评分，用时 {time.time() - t0:.1f}s")
    return total


if __name__ == "__main__":
    run(int(sys.argv[1]) if len(sys.argv) > 1 else 30)
