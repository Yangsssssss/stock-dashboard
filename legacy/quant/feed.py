"""行情数据层：腾讯实时报价 + K 线 + 东财行业板块。

- 报价：qt.gtimg.cn（A 股 / 港股 / 美股 / ETF 同一接口，GBK 编码）
- K 线：A 股 / 港股 / ETF 走 web.ifzq.gtimg.cn；美股走新浪（腾讯美股日线只回 2 条）
- 板块：push2.eastmoney.com 行业板块涨跌
"""
from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Tuple
from urllib.parse import quote

# 进程内 DNS 缓存：macOS getaddrinfo 有全局锁，并发抓取时 DNS 串行化严重（52 只可拖 15s+）
_dns_cache: Dict[tuple, list] = {}
_orig_getaddrinfo = socket.getaddrinfo


def _cached_getaddrinfo(host, port, *args, **kwargs):
    key = (host, port, args, tuple(sorted(kwargs.items())))
    hit = _dns_cache.get(key)
    if hit:
        return hit
    result = _orig_getaddrinfo(host, port, *args, **kwargs)
    _dns_cache[key] = result
    return result


socket.getaddrinfo = _cached_getaddrinfo

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(ROOT, ".cache", "klines")
os.makedirs(CACHE_DIR, exist_ok=True)

UA = {"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"}
CST = timezone(timedelta(hours=8))

# ------------------------------------------------------------------ 标的池
# code, 名称兜底, 币种
UNIVERSE: Dict[str, Dict] = {
    "a": {
        "label": "A 股",
        "benchmark": "sh000300",
        "items": [
            ("sh000001", "上证指数", "index"),
            ("sz399001", "深证成指", "index"),
            ("sz399006", "创业板指", "index"),
            ("sh000688", "科创50", "index"),
            ("sh000300", "沪深300", "index"),
            ("sh000905", "中证500", "index"),
            ("sh600519", "贵州茅台", "stock"),
            ("sz300750", "宁德时代", "stock"),
            ("sz002594", "比亚迪", "stock"),
            ("sh688981", "中芯国际", "stock"),
            ("sh600036", "招商银行", "stock"),
            ("sz002475", "立讯精密", "stock"),
            ("sh601318", "中国平安", "stock"),
            ("sh600900", "长江电力", "stock"),
            ("sz000858", "五粮液", "stock"),
            ("sh601899", "紫金矿业", "stock"),
        ],
    },
    "hk": {
        "label": "港股",
        "benchmark": "hkHSI",
        "items": [
            ("hkHSI", "恒生指数", "index"),
            ("hkHSTECH", "恒生科技", "index"),
            ("hkHSCEI", "国企指数", "index"),
            ("hk00700", "腾讯控股", "stock"),
            ("hk09988", "阿里巴巴", "stock"),
            ("hk03690", "美团", "stock"),
            ("hk01810", "小米集团", "stock"),
            ("hk01211", "比亚迪股份", "stock"),
            ("hk00981", "中芯国际", "stock"),
            ("hk01024", "快手", "stock"),
            ("hk09618", "京东集团", "stock"),
            ("hk02015", "理想汽车", "stock"),
        ],
    },
    "us": {
        "label": "美股",
        "benchmark": "usIXIC",
        "items": [
            ("usDJI", "道琼斯", "index"),
            ("usIXIC", "纳斯达克", "index"),
            ("usINX", "标普500", "index"),
            ("usAAPL", "苹果", "stock"),
            ("usNVDA", "英伟达", "stock"),
            ("usMSFT", "微软", "stock"),
            ("usGOOGL", "谷歌", "stock"),
            ("usAMZN", "亚马逊", "stock"),
            ("usMETA", "Meta", "stock"),
            ("usTSLA", "特斯拉", "stock"),
            ("usTSM", "台积电", "stock"),
            ("usAVGO", "博通", "stock"),
        ],
    },
    "etf": {
        "label": "ETF",
        "benchmark": "sh000300",
        "items": [
            ("sh510300", "沪深300ETF", "etf"),
            ("sh510500", "中证500ETF", "etf"),
            ("sh588000", "科创50ETF", "etf"),
            ("sz159915", "创业板ETF", "etf"),
            ("sh512880", "证券ETF", "etf"),
            ("sh512480", "半导体ETF", "etf"),
            ("sh515790", "光伏ETF", "etf"),
            ("sh512690", "酒ETF", "etf"),
            ("sh513100", "纳指ETF", "etf"),
            ("sh513500", "标普500ETF", "etf"),
            ("sh513180", "恒生科技ETF", "etf"),
            ("sh518880", "黄金ETF", "etf"),
        ],
    },
}

CORR_SET = [
    ("sh510300", "沪深300ETF"),
    ("sz159915", "创业板ETF"),
    ("sh513180", "恒生科技ETF"),
    ("sh513100", "纳指ETF"),
    ("sh518880", "黄金ETF"),
    ("sh510500", "中证500ETF"),
]

ALL_CODES = [c for g in UNIVERSE.values() for c, _, _ in g["items"]]
CODE_NAME = {c: n for g in UNIVERSE.values() for c, n, _ in g["items"]}
CODE_KIND = {c: k for g in UNIVERSE.values() for c, _, k in g["items"]}


# ------------------------------------------------------------------ 工具
def _get(url: str, referer: Optional[str] = None, timeout: int = 10) -> bytes:
    # 经 subprocess 走系统 curl：Python ssl 的 TLS 指纹会被东财等源站识别并断连/降速
    cmd = ["curl", "-s", "--max-time", str(timeout), "--compressed",
           "-H", "User-Agent: " + UA["User-Agent"]]
    if referer:
        cmd += ["-H", "Referer: " + referer]
    cmd.append(url)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout + 5)
    if r.returncode != 0 or not r.stdout:
        raise IOError(f"curl {url[:60]} rc={r.returncode}")
    return r.stdout


def _num(x, default=0.0) -> float:
    try:
        s = str(x).strip()
        return float(s) if s not in ("", "-", "None") else default
    except Exception:
        return default


# ------------------------------------------------------------------ 实时报价
_quote_cache: Dict[str, dict] = {}


_last_quotes: Dict[str, dict] = {}


def fetch_quotes(codes: List[str]) -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    for i in range(0, len(codes), 40):
        chunk = codes[i:i + 40]
        raw = ""
        for attempt in range(3):
            try:
                raw = _get("https://qt.gtimg.cn/q=" + ",".join(chunk)).decode("gbk", "ignore")
                if "v_" in raw:
                    break
            except Exception:
                time.sleep(0.3 + 0.3 * attempt)
                continue
        if not raw:
            continue
        for line in raw.split(";"):
            line = line.strip()
            if not line.startswith("v_") or '="' not in line:
                continue
            code = line[2:line.index("=")]
            f = line.split('="', 1)[1].rstrip('"').split("~")
            if len(f) < 46:
                continue
            prefix = code[:2]
            if prefix == "sh" or prefix == "sz" or prefix == "bj":
                cur, amt_div, amt_unit = "CNY", 1e4, "亿"      # f37 单位：万元
            elif prefix == "hk":
                cur, amt_div, amt_unit = "HKD", 1e8, "亿"
            else:
                cur, amt_div, amt_unit = "USD", 1e8, "亿"
            price = _num(f[3])
            prev = _num(f[4])
            name = f[1].replace(" ", "") if any("\u4e00" <= ch <= "\u9fff" for ch in f[1]) else f[1]
            out[code] = {
                "code": code,
                "name": name,
                "price": price,
                "prev_close": prev,
                "open": _num(f[5]),
                "high": _num(f[33]),
                "low": _num(f[34]),
                "change": _num(f[31]),
                "pct": _num(f[32]),
                "volume": _num(f[36]),
                "amount_yi": round(_num(f[37]) / amt_div, 2),
                "turnover": _num(f[38]) if cur == "CNY" else None,
                "pe": _num(f[39]) or None,
                "amplitude": _num(f[43]) or None,
                "mktcap_yi": round(_num(f[45]), 1) or None,
                "currency": cur,
                "unit": amt_unit,
                "time": f[30],
            }
    if out:
        _quote_cache.update(out)
    # 个别标的拉取失败时沿用上一次成功的快照，避免整行消失
    for c in codes:
        if c not in out and c in _quote_cache:
            out[c] = _quote_cache[c]
    return out


# ------------------------------------------------------------------ K 线
_kline_lock = threading.Lock()
_mem: Dict[str, tuple] = {}


def fetch_kline(code: str, days: int = 260, max_age: int = 900) -> List[list]:
    """返回 [[date, open, close, high, low, volume], ...] 升序。带磁盘+内存缓存。"""
    path = os.path.join(CACHE_DIR, f"{code}.json")
    now = time.time()

    hit = _mem.get(code)
    if hit and now - hit[0] < max_age:
        return hit[1][-days:]

    with _kline_lock:
        if os.path.exists(path):
            try:
                if now - os.path.getmtime(path) < max_age:
                    bars = json.load(open(path, encoding="utf-8"))
                    _mem[code] = (now, bars)
                    return bars[-days:]
            except Exception:
                pass

    bars = _fetch_kline_remote(code)
    if bars:
        try:
            json.dump(bars, open(path, "w", encoding="utf-8"))
        except Exception:
            pass
        _mem[code] = (now, bars)
        return bars[-days:]

    # 远端失败时退回旧缓存，并写入 60 秒负缓存避免每轮 board 重复重试
    _mem[code] = (now - max_age + 60, [])
    if os.path.exists(path):
        try:
            stale = json.load(open(path, encoding="utf-8"))
            if stale:
                return stale[-days:]
        except Exception:
            pass
    return []


def _fetch_kline_remote(code: str) -> List[list]:
    """多源容灾：A股/ETF=新浪→东财；港股=腾讯hkfqkline→东财；美股=腾讯usfqkline→东财。"""
    attempts: List = []
    if code.startswith("us"):
        attempts = [_kline_us_tencent, _kline_em]
    elif code.startswith("hk"):
        attempts = [_kline_hk_tencent, _kline_em]
    else:
        attempts = [_kline_sina_cn, _kline_em]
    for i, fn in enumerate(attempts):
        try:
            bars = fn(code)
            if bars:
                return bars
        except Exception:
            pass
        if i < len(attempts) - 1:
            time.sleep(0.2)
    # 最后兜底：腾讯通用 fqkline（可能被 WAF 拦）
    try:
        return _kline_tencent(code)
    except Exception:
        return []


# 腾讯美股代码：指数直接用，个股按交易所加后缀（.OQ 纳斯达克 / .N 纽交所 / .A 美交所）
US_TENCENT = {
    "usDJI": "usDJI", "usIXIC": "usIXIC", "usINX": "usINX",
    "usAAPL": "usAAPL.OQ", "usNVDA": "usNVDA.OQ", "usMSFT": "usMSFT.OQ",
    "usGOOGL": "usGOOGL.OQ", "usAMZN": "usAMZN.OQ", "usMETA": "usMETA.OQ",
    "usTSLA": "usTSLA.OQ", "usAVGO": "usAVGO.OQ", "usTSM": "usTSM.N",
}


def _kline_us_tencent(code: str) -> List[list]:
    sym = US_TENCENT.get(code, code + ".OQ")
    url = f"https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param={sym},day,,,320,qfq"
    d = json.loads(_get(url).decode("utf-8", "ignore"))
    node = (d.get("data") or {}).get(sym) or {}
    rows = node.get("qfqday") or node.get("day") or []
    out = []
    for r in rows:
        if len(r) < 6:
            continue
        out.append([r[0], _num(r[1]), _num(r[2]), _num(r[3]), _num(r[4]), _num(r[5])])
    return out


def _kline_hk_tencent(code: str) -> List[list]:
    """港股：腾讯 hkfqkline 端点（fqkline 被 WAF 拦但 hkfqkline 正常）。"""
    url = f"https://web.ifzq.gtimg.cn/appstock/app/hkfqkline/get?param={code},day,,,320,qfq"
    d = json.loads(_get(url).decode("utf-8", "ignore"))
    node = (d.get("data") or {}).get(code) or {}
    rows = node.get("qfqday") or node.get("day") or []
    out = []
    for r in rows:
        if len(r) < 6:
            continue
        out.append([r[0], _num(r[1]), _num(r[2]), _num(r[3]), _num(r[4]), _num(r[5])])
    return out


def _kline_sina_cn(code: str) -> List[list]:
    """A股/ETF：新浪日线（不复权，scale=240 分钟）。"""
    url = ("https://quotes.sina.cn/cn/api/jsonp_v2.php/x/CN_MarketDataService.getKLineData"
           f"?symbol={code}&scale=240&ma=no&datalen=320")
    raw = _get(url, referer="https://finance.sina.com.cn").decode("utf-8", "ignore")
    l, r = raw.find("["), raw.rfind("]")
    if l < 0 or r <= l:
        return []
    rows = json.loads(raw[l:r + 1])
    out = []
    for it in rows:
        try:
            out.append([it["day"][:10], _num(it["open"]), _num(it["close"]),
                        _num(it["high"]), _num(it["low"]), _num(it["volume"])])
        except Exception:
            continue
    return out


# 东财 secid：1=沪 / 0=深 / 116=港股 / 124=恒生科技 / 100=海外指数
US_SECID = {
    "usDJI": "100.DJIA", "usIXIC": "100.NDX", "usINX": "100.SPX",
    "usAAPL": "105.AAPL", "usNVDA": "105.NVDA", "usMSFT": "105.MSFT",
    "usGOOGL": "105.GOOGL", "usAMZN": "105.AMZN", "usMETA": "105.META",
    "usTSLA": "105.TSLA", "usAVGO": "105.AVGO", "usTSM": "106.TSM",
}
HK_INDEX_SECID = {"hkHSI": "100.HSI", "hkHSCEI": "100.HSCEI", "hkHSTECH": "124.HSTECH"}


def _secid(code: str) -> str:
    if code.startswith("us"):
        return US_SECID.get(code, "105." + code[2:])
    if code in HK_INDEX_SECID:
        return HK_INDEX_SECID[code]
    if code.startswith("hk"):
        return "116." + code[2:]
    if code.startswith("sh"):
        return "1." + code[2:]
    if code.startswith("sz") or code.startswith("bj"):
        return "0." + code[2:]
    return ""


def _kline_em(code: str) -> List[list]:
    sid = _secid(code)
    if not sid:
        return []
    end = datetime.now(CST).strftime("%Y%m%d")
    beg = (datetime.now(CST) - timedelta(days=560)).strftime("%Y%m%d")
    url = ("https://push2his.eastmoney.com/api/qt/stock/kline/get?"
           f"secid={sid}&klt=101&fqt=1&beg={beg}&end={end}"
           "&fields1=f1,f2,f3,f4,f5&fields2=f51,f52,f53,f54,f55,f56")
    d = json.loads(_get(url, referer="https://quote.eastmoney.com/").decode("utf-8"))
    rows = ((d.get("data") or {}).get("klines")) or []
    out = []
    for r in rows:
        p = r.split(",")
        if len(p) < 6:
            continue
        out.append([p[0], _num(p[1]), _num(p[2]), _num(p[3]), _num(p[4]), _num(p[5])])
    return out


def _kline_tencent(code: str) -> List[list]:
    end = datetime.now(CST).strftime("%Y-%m-%d")
    start = (datetime.now(CST) - timedelta(days=420)).strftime("%Y-%m-%d")
    url = (f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?"
           f"param={code},day,{start},{end},320,qfq")
    d = json.loads(_get(url).decode("utf-8", "ignore"))
    node = (d.get("data") or {}).get(code) or {}
    rows = node.get("qfqday") or node.get("day") or []
    out = []
    for r in rows:
        if len(r) < 6:
            continue
        out.append([r[0], _num(r[1]), _num(r[2]), _num(r[3]), _num(r[4]), _num(r[5])])
    return out


def warm_cache(codes: List[str], workers: int = 10):
    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(lambda c: fetch_kline(c), codes))


# ------------------------------------------------------------------ 标的搜索
# 东财 suggest：QuoteID 形如 1.600519 / 0.000001 / 116.00700 / 105.AAPL / 100.HSI
_US_IDX = {".DJI": "usDJI", ".IXIC": "usIXIC", ".INX": "usINX", ".SPX": "usINX"}
_HK_IDX = {"HSI": "hkHSI", "HSCEI": "hkHSCEI", "HSTECH": "hkHSTECH"}


def _from_quote_id(qid: str, name: str) -> Optional[Tuple[str, str, str, str, str]]:
    """QuoteID → (code, grp, kind, market)。不支持的市场返回 None。"""
    mkt, _, sym = qid.partition(".")
    if not sym:
        return None
    if mkt == "1":
        code = "sh" + sym
    elif mkt == "0":
        code = "sz" + sym
    elif mkt == "116":
        code = "hk" + sym
    elif mkt in ("105", "106", "107"):
        code = "us" + sym
    elif mkt == "124" and sym in _HK_IDX:
        code = _HK_IDX[sym]
    elif mkt == "100" and sym in _US_IDX:
        code = _US_IDX[sym]
    elif mkt == "100" and sym in _HK_IDX:
        code = _HK_IDX[sym]
    else:
        return None
    if code.startswith("us"):
        grp, market, currency = "us", "US", "USD"
    elif code.startswith("hk"):
        grp, market, currency = "hk", "HK", "HKD"
    else:
        grp, market, currency = "a", "CN", "CNY"
    if mkt in ("100", "124"):
        kind = "index"
    elif re.match(r"^(sh5|sz15)", code):
        kind, grp = "etf", "etf"
    elif re.match(r"^(sh000|sh899|sz39[59])", code):
        kind = "index"
    else:
        kind = "stock"
    return code, grp, kind, market, currency  # type: ignore[return-value]


def search_symbols(q: str, count: int = 8) -> List[dict]:
    """按名称/拼音/代码联想，返回可直接加入看板的候选。"""
    q = q.strip()
    if not q:
        return []
    url = ("https://searchapi.eastmoney.com/api/suggest/get?input=" + quote(q)
           + f"&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count={count}")
    try:
        d = json.loads(_get(url, referer="https://quote.eastmoney.com/").decode("utf-8"))
        rows = ((d.get("QuotationCodeTable") or {}).get("Data")) or []
    except Exception:
        return []
    out, seen = [], set()
    for r in rows:
        parsed = _from_quote_id(r.get("QuoteID", ""), r.get("Name", ""))
        if not parsed:
            continue
        code, grp, kind, market, currency = parsed
        if code in seen:
            continue
        seen.add(code)
        out.append({"code": code, "name": r.get("Name", code), "grp": grp,
                    "kind": kind, "market": market, "currency": currency,
                    "type_label": r.get("SecurityTypeName", "")})
    return out


def classify_code(code: str) -> Tuple[str, str, str, str]:
    """内部代码 → (grp, kind, market, currency)。"""
    if code.startswith("us"):
        return "us", "index" if code in US_SECID and code in ("usDJI", "usIXIC", "usINX") else "stock", "US", "USD"
    if code in HK_INDEX_SECID:
        return "hk", "index", "HK", "HKD"
    if code.startswith("hk"):
        return "hk", "stock", "HK", "HKD"
    if re.match(r"^(sh5|sz15)", code):
        return "etf", "etf", "CN", "CNY"
    if re.match(r"^(sh000|sh899|sz39[59])", code):
        return "a", "index", "CN", "CNY"
    return "a", "stock", "CN", "CNY"


# ------------------------------------------------------------------ 行业板块
def fetch_sectors() -> List[dict]:
    """A 股行业板块：优先新浪（东财 push2 域族对本机 IP 已断连），失败退东财。"""
    try:
        return _sectors_sina()
    except Exception:
        pass
    try:
        url = ("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=200&po=1&np=1&fltt=2"
               "&invt=2&fid=f3&fs=m:90+t:2&fields=f3,f12,f14,f104,f105,f62")
        d = json.loads(_get(url, referer="https://quote.eastmoney.com/").decode("utf-8"))
        diff = (d.get("data") or {}).get("diff") or {}
        items = list(diff.values()) if isinstance(diff, dict) else diff
        out = []
        for it in items:
            out.append({"name": it.get("f14"), "pct": _num(it.get("f3")),
                        "up": it.get("f104") or 0, "down": it.get("f105") or 0,
                        "flow": round(_num(it.get("f62")) / 1e8, 2)})
        return out
    except Exception:
        return []


def _sectors_sina() -> List[dict]:
    """新浪行业板块。字段：0代码,1名称,2家数,3均价,4涨跌额,5涨跌幅%,…,12领涨股名。"""
    raw = _get("https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php",
               referer="https://finance.sina.com.cn").decode("gbk", "ignore")
    l, r = raw.find("{"), raw.rfind("}")
    body = json.loads(raw[l:r + 1])
    out = []
    for v in body.values():
        f = v.split(",")
        if len(f) < 6:
            continue
        out.append({"name": f[1], "pct": round(_num(f[5]), 2),
                    "up": 0, "down": 0, "count": int(_num(f[2]))})
    return out


# ------------------------------------------------------------------ 交易时段
def session_state() -> Dict[str, dict]:
    now = datetime.now(CST)
    wd = now.weekday() < 5
    hm = now.hour * 60 + now.minute

    def st(open_am, close_am, open_pm, close_pm, extra_close=None):
        if not wd:
            return {"state": "休市", "cls": "closed"}
        if open_am <= hm < close_am:
            return {"state": "交易中", "cls": "live"}
        if open_pm and open_pm <= hm < close_pm:
            return {"state": "交易中", "cls": "live"}
        if close_am <= hm < (open_pm or close_pm):
            return {"state": "午间休市", "cls": "break"}
        if hm < open_am:
            return {"state": "未开盘", "cls": "pre"}
        return {"state": "已收盘", "cls": "closed"}

    a = st(9 * 60 + 30, 11 * 60 + 30, 13 * 60, 15 * 60)
    hk = st(9 * 60 + 30, 12 * 60, 13 * 60, 16 * 60)

    # 美股：北京时间 21:30/22:30 开盘，次日 4:00/5:00 收盘（简化）
    us_open, us_close = (21 * 60 + 30, 4 * 60)
    if wd and (hm >= us_open or hm < us_close):
        us = {"state": "交易中", "cls": "live"}
    elif wd and hm < us_open:
        us = {"state": "未开盘", "cls": "pre"}
    else:
        us = {"state": "已收盘", "cls": "closed"}

    return {"a": a, "hk": hk, "us": us, "etf": a,
            "server_time": now.strftime("%Y-%m-%d %H:%M:%S")}
