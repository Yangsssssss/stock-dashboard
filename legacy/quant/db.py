"""MySQL 持久层：连接池、Flyway 风格迁移、各表 DAO。

设计原则：数据库是可降级的旁路。任何 DB 异常都不应影响看板取数与渲染，
因此所有写方法内部吞掉异常并返回 False，由调用方记录日志。
"""
from __future__ import annotations

import hashlib
import queue
import re
import threading
import time
from datetime import date, datetime
from typing import Dict, Iterable, List, Optional

import pymysql
from pymysql.cursors import DictCursor

from . import config

_READY = False
_LOCK = threading.Lock()
_POOL: "queue.Queue" = queue.Queue(maxsize=8)
_POOL_SIZE = 6
_last_snapshot: Dict[str, float] = {}


# ------------------------------------------------------------------ 连接池
def _new_conn(database: Optional[str] = None):
    kw = dict(config.DB)
    if database is not None:
        kw["database"] = database
    return pymysql.connect(cursorclass=DictCursor, autocommit=True,
                           init_command="SET time_zone = '+08:00'", **kw)


class _Conn:
    """上下文管理器：从池里借一个连接，异常时丢弃。"""

    def __init__(self):
        self.conn = None
        self.broken = False

    def __enter__(self):
        try:
            self.conn = _POOL.get_nowait()
            self.conn.ping(reconnect=True)
        except queue.Empty:
            self.conn = _new_conn()
        except Exception:
            self.conn = _new_conn()
        return self.conn

    def __exit__(self, exc_type, exc, tb):
        if self.conn is None:
            return False
        if self.broken or exc_type is not None:
            try:
                self.conn.close()
            except Exception:
                pass
            return False
        try:
            _POOL.put_nowait(self.conn)
        except queue.Full:
            try:
                self.conn.close()
            except Exception:
                pass
        return False


def cursor():
    return _Conn()


# ------------------------------------------------------------------ 初始化
def init() -> bool:
    """建库 → 建池 → 跑迁移。失败返回 False，看板继续跑。"""
    global _READY
    if _READY:
        return True
    with _LOCK:
        if _READY:
            return True
        try:
            root = _new_conn(database="mysql" if config.DB["database"] else None)
            with root.cursor() as cur:
                cur.execute(
                    f"CREATE DATABASE IF NOT EXISTS `{config.DB['database']}` "
                    "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            root.close()
        except Exception as e:
            print(f"[db] 建库失败：{e}")
            return False

        try:
            _migrate()
            with _Conn() as c, c.cursor() as cur:
                cur.execute("SELECT 1")
            _READY = True
            print(f"[db] 已连接 {config.DB['host']}:{config.DB['port']}/{config.DB['database']}")
            return True
        except Exception as e:
            print(f"[db] 初始化失败：{e}")
            return False


def ready() -> bool:
    return _READY


def _migrate() -> None:
    files = sorted(config.MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)", p.name).group(1)))
    with _Conn() as c, c.cursor() as cur:
        cur.execute("""CREATE TABLE IF NOT EXISTS schema_version (
            version VARCHAR(32) NOT NULL,
            script  VARCHAR(128) NOT NULL,
            checksum VARCHAR(64) NOT NULL,
            applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""")
        cur.execute("SELECT version FROM schema_version")
        done = {r["version"] for r in cur.fetchall()}
        for f in files:
            ver = f.name.split("__", 1)[0]
            if ver in done:
                continue
            sql = f.read_text(encoding="utf-8")
            for stmt in _split_sql(sql):
                cur.execute(stmt)
            cur.execute("INSERT INTO schema_version (version, script, checksum) VALUES (%s,%s,%s)",
                        (ver, f.name, hashlib.md5(sql.encode()).hexdigest()))
            print(f"[db] 迁移 {f.name} 完成")


def _split_sql(text: str) -> List[str]:
    out, buf = [], []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("--"):
            continue
        buf.append(line)
        if s.endswith(";"):
            stmt = "\n".join(buf).strip().rstrip(";").strip()
            if stmt:
                out.append(stmt)
            buf = []
    rest = "\n".join(buf).strip()
    if rest:
        out.append(rest)
    return out


# ------------------------------------------------------------------ 标的
def upsert_instruments(items: Iterable[dict]) -> bool:
    def _market(i: dict) -> str:
        m = i.get("market")
        if m:
            return m
        code = i["code"]
        if code.startswith("us"):
            return "US"
        if code.startswith("hk"):
            return "HK"
        return "CN"

    rows = [(i["code"], i["name"], _market(i), i["kind"], i.get("currency", "CNY")) for i in items]
    if not rows:
        return True
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.executemany(
                "INSERT INTO instrument (code, name, market, kind, currency) VALUES (%s,%s,%s,%s,%s) "
                "ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), "
                "kind=VALUES(kind), currency=VALUES(currency)", rows)
        return True
    except Exception as e:
        print(f"[db] instrument 写入失败：{e}")
        return False


# ------------------------------------------------------------------ 日线
def upsert_klines(series: Dict[str, List[list]]) -> int:
    """增量写入：只补每个标的最新日期之后的数据。返回写入行数。"""
    if not series:
        return 0
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("SELECT code, MAX(trade_date) AS d FROM kline_daily GROUP BY code")
            latest = {r["code"]: r["d"] for r in cur.fetchall()}
            rows = []
            for code, bars in series.items():
                last = latest.get(code)
                for b in bars:
                    if last and str(b[0]) <= str(last):
                        continue
                    rows.append((code, b[0], b[1], b[2], b[3], b[4], b[5]))
            if not rows:
                return 0
            cur.executemany(
                "INSERT INTO kline_daily (code, trade_date, open, close, high, low, volume) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE "
                "open=VALUES(open), close=VALUES(close), high=VALUES(high), "
                "low=VALUES(low), volume=VALUES(volume)", rows)
            return len(rows)
    except Exception as e:
        print(f"[db] kline 写入失败：{e}")
        return 0


# ------------------------------------------------------------------ 快照
def insert_snapshots(quotes: Iterable[dict], throttle: int = 180) -> int:
    now = time.time()
    rows = []
    for q in quotes:
        code = q["code"]
        if now - _last_snapshot.get(code, 0) < throttle:
            continue
        rows.append((code, datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                     q["price"], q["pct"], q["amount_yi"],
                     q.get("turnover"), q.get("pe"), q.get("mktcap_yi")))
        _last_snapshot[code] = now
    if not rows:
        return 0
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.executemany(
                "INSERT INTO quote_snapshot (code, snapshot_at, price, pct, amount_yi, "
                "turnover, pe, mktcap_yi) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", rows)
        return len(rows)
    except Exception as e:
        print(f"[db] snapshot 写入失败：{e}")
        return 0


# ------------------------------------------------------------------ 评分
def upsert_scores(items: Iterable[dict], trade_date: Optional[str] = None) -> int:
    d = trade_date or date.today().isoformat()
    rows = []
    for i in items:
        a = i.get("ana") or {}
        if not a.get("ok"):
            continue
        f = a.get("factors") or {}
        rows.append((i["code"], d, a["score"], a["signal"], f.get("trend", 0),
                     f.get("momentum", 0), f.get("volume", 0), f.get("risk", 0),
                     a.get("r5"), a.get("r20"), a.get("rsi")))
    if not rows:
        return 0
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.executemany(
                "INSERT INTO daily_score (code, trade_date, score, sig, trend, momentum, "
                "volume_f, risk, r5, r20, rsi) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                "ON DUPLICATE KEY UPDATE score=VALUES(score), sig=VALUES(sig), "
                "trend=VALUES(trend), momentum=VALUES(momentum), volume_f=VALUES(volume_f), "
                "risk=VALUES(risk), r5=VALUES(r5), r20=VALUES(r20), rsi=VALUES(rsi)", rows)
        return len(rows)
    except Exception as e:
        print(f"[db] score 写入失败：{e}")
        return 0


def upsert_score_rows(rows: List[tuple]) -> int:
    """rows: (code, trade_date, score, signal, trend, momentum, volume, risk, r5, r20, rsi)"""
    if not rows:
        return 0
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.executemany(
                "INSERT INTO daily_score (code, trade_date, score, sig, trend, momentum, "
                "volume_f, risk, r5, r20, rsi) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                "ON DUPLICATE KEY UPDATE score=VALUES(score), sig=VALUES(sig), "
                "trend=VALUES(trend), momentum=VALUES(momentum), volume_f=VALUES(volume_f), "
                "risk=VALUES(risk), r5=VALUES(r5), r20=VALUES(r20), rsi=VALUES(rsi)", rows)
        return len(rows)
    except Exception as e:
        print(f"[db] score 批量写入失败：{e}")
        return 0


def score_history(code: str, days: int = 30) -> List[dict]:
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute(
                "SELECT trade_date, score, sig FROM daily_score WHERE code=%s "
                "ORDER BY trade_date DESC LIMIT %s", (code, days))
            rows = cur.fetchall()
        return [{"date": str(r["trade_date"]), "score": float(r["score"]),
                 "signal": r["sig"]} for r in reversed(rows)]
    except Exception:
        return []


def score_movers() -> Dict[str, List[dict]]:
    """最近两个交易日之间评分变化最大的标的（动量拐点）。"""
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("SELECT DISTINCT trade_date FROM daily_score ORDER BY trade_date DESC LIMIT 2")
            ds = [str(r["trade_date"]) for r in cur.fetchall()]
            if len(ds) < 2:
                return {"up": [], "down": [], "dates": ds}
            cur.execute("""
                SELECT a.code, i.name, a.score AS s_new, b.score AS s_old,
                       ROUND(a.score - b.score, 1) AS delta
                FROM daily_score a
                JOIN daily_score b ON b.code = a.code AND b.trade_date = %s
                LEFT JOIN instrument i ON i.code = a.code
                WHERE a.trade_date = %s
                ORDER BY delta DESC""", (ds[1], ds[0]))
            rows = cur.fetchall()
        rows = [{"code": r["code"], "name": r["name"] or r["code"],
                 "score": float(r["s_new"]), "prev": float(r["s_old"]),
                 "delta": float(r["delta"])} for r in rows]
        return {"up": rows[:5], "down": rows[-5:][::-1], "dates": ds}
    except Exception:
        return {"up": [], "down": [], "dates": []}


# ------------------------------------------------------------------ 自选
# ------------------------------------------------------------------ 自定义标的
def custom_instruments() -> List[dict]:
    """用户添加、且未删除的标的。"""
    if not _READY:
        return []
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute(
                "SELECT code, name, market, kind, currency, grp FROM instrument "
                "WHERE custom=1 AND hidden=0 ORDER BY created_at, code")
            return [dict(r) for r in cur.fetchall()]
    except Exception as e:
        print(f"[db] 读取自定义标的失败：{e}")
        return []


def hidden_builtins() -> List[str]:
    """被用户移出看板的内置标的代码。"""
    if not _READY:
        return []
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("SELECT code FROM instrument WHERE custom=0 AND hidden=1")
            return [r["code"] for r in cur.fetchall()]
    except Exception:
        return []


def instrument_add(code: str, name: str, grp: str, kind: str,
                   market: str, currency: str = "CNY") -> bool:
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute(
                "INSERT INTO instrument (code, name, market, kind, currency, grp, custom, hidden) "
                "VALUES (%s,%s,%s,%s,%s,%s,1,0) "
                "ON DUPLICATE KEY UPDATE custom=1, hidden=0, name=VALUES(name), "
                "market=VALUES(market), kind=VALUES(kind), currency=VALUES(currency), grp=VALUES(grp)",
                (code, name, market, kind, currency, grp))
        return True
    except Exception as e:
        print(f"[db] 添加标的失败：{e}")
        return False


def instrument_remove(code: str) -> str:
    """返回处理方式：'deleted'（自定义，直接删行）/ 'hidden'（内置，标记隐藏）。"""
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("SELECT custom FROM instrument WHERE code=%s", (code,))
            row = cur.fetchone()
            if row and row["custom"]:
                cur.execute("DELETE FROM instrument WHERE code=%s", (code,))
                return "deleted"
            cur.execute(
                "INSERT INTO instrument (code, name, market, kind, hidden) "
                "VALUES (%s, %s, 'CN', 'stock', 1) "
                "ON DUPLICATE KEY UPDATE hidden=1", (code, code))
            return "hidden"
    except Exception as e:
        print(f"[db] 移除标的失败：{e}")
        return ""


def instrument_restore_all() -> int:
    """恢复所有被隐藏的内置标的，返回恢复数量。"""
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("UPDATE instrument SET hidden=0 WHERE custom=0 AND hidden=1")
            return cur.rowcount
    except Exception as e:
        print(f"[db] 恢复标的失败：{e}")
        return 0


def watchlist() -> List[dict]:
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("SELECT code, note, sort_no, created_at FROM watchlist ORDER BY sort_no, created_at")
            return [dict(r, created_at=str(r["created_at"])) for r in cur.fetchall()]
    except Exception:
        return []


def watch_add(code: str, note: Optional[str] = None) -> bool:
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("INSERT IGNORE INTO watchlist (code, note) VALUES (%s, %s)", (code, note))
        return True
    except Exception as e:
        print(f"[db] watchlist 写入失败：{e}")
        return False


def watch_remove(code: str) -> bool:
    try:
        with _Conn() as c, c.cursor() as cur:
            cur.execute("DELETE FROM watchlist WHERE code=%s", (code,))
        return True
    except Exception as e:
        print(f"[db] watchlist 删除失败：{e}")
        return False


# ------------------------------------------------------------------ 统计
def stats() -> dict:
    q = {
        "instruments": "SELECT COUNT(*) c FROM instrument",
        "klines": "SELECT COUNT(*) c FROM kline_daily",
        "snapshots": "SELECT COUNT(*) c FROM quote_snapshot",
        "scores": "SELECT COUNT(*) c FROM daily_score",
        "watchlist": "SELECT COUNT(*) c FROM watchlist",
    }
    out = {"ready": _READY, "db": f"{config.DB['host']}:{config.DB['port']}/{config.DB['database']}"}
    try:
        with _Conn() as c, c.cursor() as cur:
            for k, sql in q.items():
                cur.execute(sql)
                out[k] = int(cur.fetchone()["c"])
            cur.execute("SELECT MAX(trade_date) d FROM kline_daily")
            out["last_kline"] = str(cur.fetchone()["d"] or "")
    except Exception as e:
        out["error"] = str(e)
    return out
