#!/usr/bin/env python3
"""A股 / 港股 / 美股 / ETF 看板后端。

启动：python3 server.py  → http://127.0.0.1:8848
依赖：pymysql（持久层），其余均为标准库。
"""
from __future__ import annotations

import json
import mimetypes
import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from quant import db, feed, indicators

ROOT = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(ROOT, "web")
PORT = int(os.environ.get("PORT", "8848"))

_board_lock = threading.Lock()
_board_cache = {"ts": 0, "data": None}
_persist_lock = threading.Lock()


# ------------------------------------------------------------------ 组装看板
def build_board(force: bool = False) -> dict:
    now = time.time()
    if not force and _board_cache["data"] and now - _board_cache["ts"] < 25:
        return _board_cache["data"]
    with _board_lock:
        if not force and _board_cache["data"] and time.time() - _board_cache["ts"] < 25:
            return _board_cache["data"]
        data, series = _build_board()
        _board_cache["ts"] = time.time()
        _board_cache["data"] = data
        persist_async(data, series)
        return data


# ------------------------------------------------------------------ 落库
def persist_async(payload: dict, series: Optional[dict] = None) -> None:
    """把一轮行情/评分异步写库，绝不阻塞接口响应。series 复用 board 已抓的 K 线。"""
    if not db.ready() or not _persist_lock.acquire(blocking=False):
        return

    def run():
        t0 = time.time()
        try:
            items = [i for g in payload["groups"].values() for i in g["items"]]
            db.upsert_instruments(items)
            n_k = db.upsert_klines(series or {})
            n_s = db.insert_snapshots(items)
            n_sc = db.upsert_scores(items)
            if n_k or n_s or n_sc:
                print(f"[db] 落库 日线+{n_k} 快照+{n_s} 评分+{n_sc} 用时 {time.time() - t0:.2f}s")
        except Exception as e:  # noqa: BLE001
            print(f"[db] 落库失败：{e}")
        finally:
            _persist_lock.release()

    threading.Thread(target=run, daemon=True).start()


def _make_item(code: str, q: dict, fallback_name: str, kind: str,
               gkey: str, klines: dict, bench: list, is_bench: bool = False) -> dict:
    bars = klines.get(code) or []
    ana = indicators.analyze(bars, None if is_bench else bench)
    return {
        **q,
        "name": q["name"] or fallback_name,
        "kind": kind,
        "group": gkey,
        "spark": [round(float(b[2]), 4) for b in bars[-45:]],
        "ana": ana,
    }


def _code_name(code: str) -> str:
    if code in feed.CODE_NAME:
        return feed.CODE_NAME[code]
    for it in db.custom_instruments():
        if it["code"] == code:
            return it["name"]
    return code


def _build_board() -> dict:
    t0 = time.time()
    hidden = set(db.hidden_builtins())
    custom = db.custom_instruments()
    custom_codes = [c["code"] for c in custom
                    if c["code"] not in {i for g in feed.UNIVERSE.values() for i, _, _ in g["items"]}]

    quotes = feed.fetch_quotes(feed.ALL_CODES + custom_codes)
    t1 = time.time()
    print(f"[board] quotes {len(quotes)}/{len(feed.ALL_CODES) + len(custom_codes)} 用时 {t1 - t0:.2f}s")

    # K 线（带缓存）——并行抓取：冷缓存时 52 只远端请求从 10s+ 压到 ~2s
    all_codes = feed.ALL_CODES + custom_codes
    with ThreadPoolExecutor(max_workers=8) as ex:
        klines = dict(zip(all_codes, ex.map(feed.fetch_kline, all_codes)))
    t2 = time.time()
    print(f"[board] klines 远端{sum(1 for v in klines.values() if v)}条 用时 {t2 - t1:.2f}s")

    # 自定义标的按分组挂载（code → 自定义条目）
    custom_by_grp: dict = {}
    for it in custom:
        custom_by_grp.setdefault(it["grp"] or "a", []).append(it)
    custom_codes_set = {it["code"] for it in custom}

    groups = {}
    for gkey, g in feed.UNIVERSE.items():
        bench = klines.get(g["benchmark"]) or []
        items = []
        for code, fallback_name, kind in g["items"]:
            if code in hidden:
                continue
            q = quotes.get(code)
            if not q:
                continue
            item = _make_item(code, q, fallback_name, kind, gkey,
                              klines, bench, code == g["benchmark"])
            if code in custom_codes_set:
                item["custom"] = True
            items.append(item)
        # 用户自定义标的
        for it in custom_by_grp.get(gkey, []):
            code = it["code"]
            q = quotes.get(code)
            if not q or any(i["code"] == code for i in items):
                continue
            item = _make_item(code, q, it["name"], it["kind"],
                              gkey, klines, klines.get(g["benchmark"]) or [])
            item["custom"] = True
            items.append(item)
        pcts = [i["pct"] for i in items if i["kind"] != "index"]
        gains = [i for i in items if i["kind"] != "index"]
        groups[gkey] = {
            "label": g["label"],
            "benchmark": g["benchmark"],
            "items": items,
            "stats": {
                "avg_pct": round(sum(pcts) / len(pcts), 2) if pcts else 0,
                "up": sum(1 for i in gains if i["pct"] > 0),
                "down": sum(1 for i in gains if i["pct"] < 0),
                "flat": sum(1 for i in gains if i["pct"] == 0),
                "avg_r20": round(
                    sum(i["ana"].get("r20", 0) for i in gains if i["ana"].get("ok"))
                    / max(1, len([i for i in gains if i["ana"].get("ok")])), 2),
            },
        }

    # 全市场信号排序
    pool = []
    for gkey, g in groups.items():
        for i in g["items"]:
            a = i.get("ana") or {}
            if a.get("ok"):
                pool.append({
                    "code": i["code"], "name": i["name"], "group": gkey,
                    "group_label": g["label"], "kind": i["kind"],
                    "price": i["price"], "pct": i["pct"], "currency": i["currency"],
                    "score": a["score"], "signal": a["signal"],
                    "signal_cls": a["signal_cls"],
                    "r20": a["r20"], "rsi": a["rsi"], "rs": a["rs"],
                    "conclusion": a["conclusion"],
                })
    ranked = sorted(pool, key=lambda x: -x["score"])

    # 相关性矩阵
    series = {}
    for code, label in feed.CORR_SET:
        bars = klines.get(code) or []
        if len(bars) > 25:
            series[label] = [float(b[2]) for b in bars]
    corr = indicators.correlation(series)

    # 行业板块
    t3 = time.time()
    sectors = feed.fetch_sectors()
    print(f"[board] sectors {len(sectors)}个 用时 {time.time() - t3:.2f}s"
          f" · 总计 {time.time() - t0:.2f}s")

    payload = {
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "sessions": feed.session_state(),
        "groups": groups,
        "om": {
            "indices": [i for g in groups.values() for i in g["items"] if i["kind"] == "index"],
        },
        "ranking": {"top": ranked[:8], "bottom": ranked[-8:][::-1], "all": ranked},
        "corr": corr,
        "sectors": {
            "up": sorted(sectors, key=lambda x: -x["pct"])[:14],
            "down": sorted(sectors, key=lambda x: x["pct"])[:10],
            "count": len(sectors),
        },
        "watch": [w["code"] for w in db.watchlist()],
        "custom_count": len(custom),
        "hidden": db.hidden_builtins(),
        "db": {"ready": db.ready()},
    }
    return payload, klines


# ------------------------------------------------------------------ HTTP
class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, body: bytes, ctype: str, cache: str = "no-store"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code: int = 200):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def do_GET(self):
        u = urlparse(self.path)
        path, qs = u.path, parse_qs(u.query)

        try:
            if path == "/api/board":
                self._json(build_board(force=qs.get("force", ["0"])[0] == "1"))
                return
            if path == "/api/kline":
                code = qs.get("code", [""])[0]
                days = int(qs.get("days", ["180"])[0])
                bars = feed.fetch_kline(code, days=400)
                bars = bars[-days:]
                closes = [float(b[2]) for b in bars]
                self._json({
                    "code": code,
                    "name": _code_name(code),
                    "bars": bars,
                    "ma5": indicators.sma(closes, 5),
                    "ma10": indicators.sma(closes, 10),
                    "ma20": indicators.sma(closes, 20),
                    "ma60": indicators.sma(closes, 60),
                })
                return
            if path == "/api/search":
                self._json({"items": feed.search_symbols(qs.get("q", [""])[0])})
                return
            if path == "/api/watchlist":
                self._json({"items": db.watchlist(), "ready": db.ready()})
                return
            if path == "/api/history":
                code = qs.get("code", [""])[0]
                days = int(qs.get("days", ["30"])[0])
                self._json({"code": code, "points": db.score_history(code, days)})
                return
            if path == "/api/movers":
                self._json(db.score_movers())
                return
            if path == "/api/db":
                self._json(db.stats())
                return
        except Exception as e:  # noqa: BLE001
            self._json({"error": str(e)}, 500)
            return

        # 静态文件
        rel = "index.html" if path in ("/", "") else path.lstrip("/")
        full = os.path.normpath(os.path.join(WEB, rel))
        if not full.startswith(WEB) or not os.path.isfile(full):
            self._send(404, b"not found", "text/plain")
            return
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript",):
            ctype += "; charset=utf-8"
        self._send(200, open(full, "rb").read(), ctype, cache="no-cache")

    def do_POST(self):
        u = urlparse(self.path)
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            body = {}

        try:
            if u.path == "/api/instruments":
                action = body.get("action", "add")
                if action == "restore":
                    n = db.instrument_restore_all()
                    self._json({"ok": True, "restored": n})
                    return
                code = str(body.get("code", "")).strip().lower()
                if not re.fullmatch(r"(sh|sz|hk|us)[A-Za-z0-9.]{1,10}", code):
                    self._json({"error": "无效代码，格式如 sh600036 / hk09988 / usNVDA / sh510300"}, 400)
                    return
                if action == "remove":
                    mode = db.instrument_remove(code)
                    if not mode:
                        self._json({"error": "移除失败"}, 500)
                        return
                    self._json({"ok": True, "mode": mode})
                    return
                # 添加：先验证行情是否可取
                q = feed.fetch_quotes([code]).get(code)
                if not q or not q.get("price"):
                    self._json({"error": f"代码 {code} 无法取到行情，请确认是否有效"}, 400)
                    return
                grp, kind, market, currency = feed.classify_code(code)
                name = q.get("name") or code
                ok = db.instrument_add(code, name, grp, kind, market, currency)
                self._json({"ok": ok, "code": code, "name": name,
                            "grp": grp, "kind": kind})
                return
            if u.path == "/api/watchlist":
                code = str(body.get("code", "")).strip()
                action = body.get("action", "add")
                if not re.fullmatch(r"[a-z]{2}[A-Za-z0-9.]{1,12}", code):
                    self._json({"error": "invalid code"}, 400)
                    return
                ok = db.watch_remove(code) if action == "remove" else db.watch_add(code, body.get("note"))
                self._json({"ok": ok, "watch": [w["code"] for w in db.watchlist()]})
                return
        except Exception as e:  # noqa: BLE001
            self._json({"error": str(e)}, 500)
            return
        self._json({"error": "not found"}, 404)


def main():
    db.init()
    print("K 线将由首个请求并行预热（8 线程，冷启动约 3~4 秒）")
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"看板已启动 → http://127.0.0.1:{PORT}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
