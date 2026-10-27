package com.quantboard.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.util.U;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 行情数据层：多源容灾。
 * 报价=腾讯；K线 A股/ETF=新浪、港股=腾讯hkfqkline、美股=腾讯usfqkline（东财备用）；
 * 板块=新浪行业；搜索=东财 suggest。
 */
@Service
public class FeedService {
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper M = new ObjectMapper();
    private static final Charset GBK = Charset.forName("GBK");

    /** K 线 bar：[date, open, close, high, low, volume]。 */
    public static final class Bars {
        public final long ts;
        public final List<Object[]> rows;
        public final boolean negative; // 远端失败负缓存

        Bars(long ts, List<Object[]> rows, boolean negative) {
            this.ts = ts;
            this.rows = rows;
            this.negative = negative;
        }
    }

    public static final long KLINE_TTL_MS = 900_000;   // 15 分钟
    private static final long KLINE_NEG_MS = 60_000;    // 失败负缓存 60s

    private final Map<String, Bars> klineMem = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> quoteCache = new ConcurrentHashMap<>();
    private final FundService fundService;

    public FeedService(FundService fundService) {
        this.fundService = fundService;
    }

    /** 美股代码大小写敏感（腾讯接口）：us 前缀后的部分统一转大写。 */
    private static String normalizeUs(String code) {
        return code.length() > 2 && code.startsWith("us")
                ? "us" + code.substring(2).toUpperCase()
                : code;
    }

    // ---------------------------------------------------------------- 实时报价
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> fetchQuotes(List<String> codes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        // 场外基金不走腾讯行情（由 FundService 提供），调用方应自行拆分
        List<String> stockCodes = codes.stream().filter(c -> !FundService.isFund(c)).toList();
        codes = stockCodes;
        for (int i = 0; i < codes.size(); i += 40) {
            List<String> chunk = codes.subList(i, Math.min(codes.size(), i + 40));
            // 腾讯接口对美股代码大小写敏感：请求前统一转大写，返回仍按原始 code 键
            Map<String, String> back = new HashMap<>();
            List<String> remote = new ArrayList<>();
            for (String c : chunk) {
                String r = normalizeUs(c);
                remote.add(r);
                back.put(r, c);
            }
            String raw = "";
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    raw = new String(HttpFetch.get("https://qt.gtimg.cn/q=" + String.join(",", remote)), GBK);
                    if (raw.contains("v_")) break;
                } catch (IOException e) {
                    sleep(300 + 300L * attempt);
                }
            }
            if (raw.isEmpty()) continue;
            for (String line0 : raw.split(";")) {
                String line = line0.trim();
                int eq = line.indexOf("=\"");
                if (!line.startsWith("v_") || eq < 0) continue;
                String code = back.getOrDefault(line.substring(2, eq), line.substring(2, eq));
                String body = line.substring(eq + 2);
                if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
                String[] f = body.split("~");
                if (f.length < 46) continue;
                String prefix = code.substring(0, 2);
                String cur; double amtDiv; String amtUnit;
                if (prefix.equals("sh") || prefix.equals("sz") || prefix.equals("bj")) {
                    cur = "CNY"; amtDiv = 1e4; amtUnit = "亿";          // f37 单位：万元
                } else if (prefix.equals("hk")) {
                    cur = "HKD"; amtDiv = 1e8; amtUnit = "亿";
                } else {
                    cur = "USD"; amtDiv = 1e8; amtUnit = "亿";
                }
                boolean hasCn = false;
                for (int c = 0; c < f[1].length(); c++) {
                    if (f[1].charAt(c) >= 0x4e00 && f[1].charAt(c) <= 0x9fff) { hasCn = true; break; }
                }
                String name = hasCn ? f[1].replace(" ", "") : f[1];
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("code", code);
                q.put("name", name);
                q.put("price", U.num(f[3]));
                q.put("prev_close", U.num(f[4]));
                q.put("open", U.num(f[5]));
                q.put("high", U.num(f[33]));
                q.put("low", U.num(f[34]));
                q.put("change", U.num(f[31]));
                q.put("pct", U.num(f[32]));
                q.put("volume", U.num(f[36]));
                q.put("amount_yi", U.round(U.num(f[37]) / amtDiv, 2));
                q.put("turnover", cur.equals("CNY") ? U.num(f[38]) : null);
                q.put("pe", U.num(f[39]) != 0 ? U.num(f[39]) : null);
                q.put("amplitude", U.num(f[43]) != 0 ? U.num(f[43]) : null);
                q.put("mktcap_yi", U.num(f[45]) != 0 ? U.round(U.num(f[45]), 1) : null);
                // 国内 ETF：f78 ≈ 净值/IOPV，溢价率 = 现价/净值 - 1
                if (f.length > 78 && (code.startsWith("sh5") || code.startsWith("sz15"))) {
                    double nav = U.num(f[78]);
                    double p = U.num(f[3]);
                    q.put("nav", nav > 0 ? U.round(nav, 4) : null);
                    q.put("premium", (nav > 0 && p > 0) ? U.round((p / nav - 1) * 100, 2) : null);
                }
                q.put("currency", cur);
                q.put("unit", amtUnit);
                q.put("time", f[30]);
                // 港股/美股指数的 f37 成交额单位不可靠（恒指 0、纳指给出 1.7e14 美元），
                // 置空让前端显示 "—"；A股指数的 f37（万元）是真实成交额，保留
                if ("index".equals(Universe.codeKind(code))
                        && !prefix.equals("sh") && !prefix.equals("sz") && !prefix.equals("bj")) {
                    q.put("amount_yi", null);
                }
                out.put(code, q);
            }
        }
        if (!out.isEmpty()) quoteCache.putAll(out);
        // 个别标的拉取失败时沿用上一次成功的快照，避免整行消失
        for (String c : codes) {
            if (!out.containsKey(c)) {
                Map<String, Object> prev = quoteCache.get(c);
                if (prev != null) out.put(c, prev);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- K 线
    /** 返回 [[date, open, close, high, low, volume], ...] 升序，最多 days 根。 */
    public List<Object[]> fetchKline(String code, int days) {
        return fetchKline(code, days, KLINE_TTL_MS);
    }

    public List<Object[]> fetchKline(String code, int days, long maxAgeMs) {
        long now = System.currentTimeMillis();
        Bars hit = klineMem.get(code);
        if (hit != null && now - hit.ts < maxAgeMs) {
            return tail(hit.rows, days);
        }
        List<Object[]> bars = fetchKlineRemote(code);
        if (!bars.isEmpty()) {
            klineMem.put(code, new Bars(now, bars, false));
            return tail(bars, days);
        }
        // 远端失败：写 60 秒负缓存，退回旧数据
        List<Object[]> stale = hit != null && !hit.rows.isEmpty() ? hit.rows : List.of();
        klineMem.put(code, new Bars(now - maxAgeMs + KLINE_NEG_MS, stale, true));
        return tail(stale, days);
    }

    private static List<Object[]> tail(List<Object[]> rows, int days) {
        if (rows.size() <= days) return rows;
        return rows.subList(rows.size() - days, rows.size());
    }

    /** K 线源：允许抛异常的函数接口。 */
    @FunctionalInterface
    private interface KlineSource {
        List<Object[]> fetch(String code) throws Exception;
    }

    private List<Object[]> fetchKlineRemote(String code) {
        // 场外基金：净值复权序列走 FundService，不进股票数据源
        if (FundService.isFund(code)) {
            try {
                List<Object[]> bars = fundService.klineBars(code);
                if (!bars.isEmpty()) return bars;
            } catch (Exception ignore) {
            }
            return List.of();
        }
        List<KlineSource> attempts = new ArrayList<>();
        if (code.startsWith("us")) {
            attempts.add(this::klineUsTencent);
        } else if (code.startsWith("hk")) {
            attempts.add(this::klineHkTencent);
        } else {
            attempts.add(this::klineSinaCn);
        }
        attempts.add(this::klineEm);
        for (int i = 0; i < attempts.size(); i++) {
            try {
                List<Object[]> bars = attempts.get(i).fetch(code);
                if (!bars.isEmpty()) return bars;
            } catch (Exception ignore) {
                // 换下一个源
            }
            if (i < attempts.size() - 1) sleep(200);
        }
        try {
            return klineTencent(code); // 最后兜底：腾讯通用 fqkline（可能被 WAF 拦）
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Object[]> parseTencentRows(JsonNode node) {
        JsonNode rows = node.has("qfqday") ? node.get("qfqday") : node.get("day");
        List<Object[]> out = new ArrayList<>();
        if (rows == null) return out;
        for (JsonNode r : rows) {
            if (r.size() < 6) continue;
            out.add(new Object[]{r.get(0).asText(), U.num(r.get(1).asText()), U.num(r.get(2).asText()),
                    U.num(r.get(3).asText()), U.num(r.get(4).asText()), U.num(r.get(5).asText())});
        }
        return out;
    }

    /** 美股：腾讯 usfqkline。 */
    private List<Object[]> klineUsTencent(String code) throws IOException {
        String sym = Universe.US_TENCENT.getOrDefault(code, normalizeUs(code) + ".OQ");
        String url = "https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=" + sym + ",day,,,320,qfq";
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
        JsonNode node = d.path("data").path(sym);
        return node.isMissingNode() ? List.of() : parseTencentRows(node);
    }

    /** 港股：腾讯 hkfqkline 端点。 */
    private List<Object[]> klineHkTencent(String code) throws IOException {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/hkfqkline/get?param=" + code + ",day,,,320,qfq";
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
        JsonNode node = d.path("data").path(code);
        return node.isMissingNode() ? List.of() : parseTencentRows(node);
    }

    /** 腾讯通用 fqkline（兜底）。 */
    private List<Object[]> klineTencent(String code) throws IOException {
        String end = java.time.LocalDate.now(CST).toString();
        String start = java.time.LocalDate.now(CST).minusDays(420).toString();
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + code
                + ",day," + start + "," + end + ",320,qfq";
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
        JsonNode node = d.path("data").path(code);
        return node.isMissingNode() ? List.of() : parseTencentRows(node);
    }

    /** A股/ETF：新浪日线。 */
    private List<Object[]> klineSinaCn(String code) throws IOException {
        String url = "https://quotes.sina.cn/cn/api/jsonp_v2.php/x/CN_MarketDataService.getKLineData"
                + "?symbol=" + code + "&scale=240&ma=no&datalen=320";
        String raw = new String(HttpFetch.get(url, "https://finance.sina.com.cn"), StandardCharsets.UTF_8);
        int l = raw.indexOf('['), r = raw.lastIndexOf(']');
        if (l < 0 || r <= l) return List.of();
        JsonNode rows = M.readTree(raw.substring(l, r + 1));
        List<Object[]> out = new ArrayList<>();
        for (JsonNode it : rows) {
            String day = it.path("day").asText("");
            if (day.isEmpty()) continue;
            out.add(new Object[]{day.substring(0, Math.min(10, day.length())),
                    U.num(it.path("open").asText()), U.num(it.path("close").asText()),
                    U.num(it.path("high").asText()), U.num(it.path("low").asText()),
                    U.num(it.path("volume").asText())});
        }
        return out;
    }

    /** 东财 K 线（备用）。 */
    private List<Object[]> klineEm(String code) throws IOException {
        String sid = Universe.secid(code);
        if (sid.isEmpty()) return List.of();
        String end = java.time.LocalDate.now(CST).format(DateTimeFormatter.BASIC_ISO_DATE);
        String beg = java.time.LocalDate.now(CST).minusDays(560).format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?"
                + "secid=" + sid + "&klt=101&fqt=1&beg=" + beg + "&end=" + end
                + "&fields1=f1,f2,f3,f4,f5&fields2=f51,f52,f53,f54,f55,f56";
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, "https://quote.eastmoney.com/"));
        JsonNode rows = d.path("data").path("klines");
        List<Object[]> out = new ArrayList<>();
        if (rows.isMissingNode()) return out;
        for (JsonNode r : rows) {
            String[] p = r.asText().split(",");
            if (p.length < 6) continue;
            out.add(new Object[]{p[0], U.num(p[1]), U.num(p[2]), U.num(p[3]), U.num(p[4]), U.num(p[5])});
        }
        return out;
    }

    // ---------------------------------------------------------------- 标的搜索
    private static final Map<String, String> US_IDX = Map.of(
            ".DJI", "usDJI", ".IXIC", "usIXIC", ".INX", "usINX", ".SPX", "usINX");
    private static final Map<String, String> HK_IDX = Map.of(
            "HSI", "hkHSI", "HSCEI", "hkHSCEI", "HSTECH", "hkHSTECH");

    /** QuoteID → (code, grp, kind, market, currency)；不支持的市场返回 null。 */
    private String[] fromQuoteId(String qid) {
        int dot = qid.indexOf('.');
        if (dot <= 0) return null;
        String mkt = qid.substring(0, dot), sym = qid.substring(dot + 1);
        if (sym.isEmpty()) return null;
        String code;
        switch (mkt) {
            case "1" -> code = "sh" + sym;
            case "0" -> code = "sz" + sym;
            case "116" -> code = "hk" + sym;
            case "105", "106", "107" -> code = "us" + sym;
            case "124" -> { if (!HK_IDX.containsKey(sym)) return null; code = HK_IDX.get(sym); }
            case "100" -> {
                if (US_IDX.containsKey(sym)) code = US_IDX.get(sym);
                else if (HK_IDX.containsKey(sym)) code = HK_IDX.get(sym);
                else return null;
            }
            default -> { return null; }
        }
        String grp, market, currency;
        if (code.startsWith("us")) { grp = "us"; market = "US"; currency = "USD"; }
        else if (code.startsWith("hk")) { grp = "hk"; market = "HK"; currency = "HKD"; }
        else { grp = "a"; market = "CN"; currency = "CNY"; }
        String kind;
        if (mkt.equals("100") || mkt.equals("124")) {
            kind = "index";
        } else if (code.matches("^(sh5|sz15).*")) {
            kind = "etf"; grp = "etf";
        } else if (code.matches("^(sh000|sh899|sz39[59]).*")) {
            kind = "index";
        } else {
            kind = "stock";
        }
        return new String[]{code, grp, kind, market, currency};
    }

    public List<Map<String, Object>> searchSymbols(String q, int count) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return List.of();
        String url = "https://searchapi.eastmoney.com/api/suggest/get?input=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=" + count;
        JsonNode d;
        try {
            d = M.readTree(HttpFetch.getUtf8(url, "https://quote.eastmoney.com/"));
        } catch (IOException e) {
            return List.of();
        }
        JsonNode rows = d.path("QuotationCodeTable").path("Data");
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (rows.isMissingNode()) return out;
        for (JsonNode r : rows) {
            String[] parsed = fromQuoteId(r.path("QuoteID").asText(""));
            if (parsed == null) continue;
            String code = parsed[0];
            if (!seen.add(code)) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("code", code);
            o.put("name", r.path("Name").asText(code));
            o.put("grp", parsed[1]);
            o.put("kind", parsed[2]);
            o.put("market", parsed[3]);
            o.put("currency", parsed[4]);
            o.put("type_label", r.path("SecurityTypeName").asText(""));
            out.add(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- 行业板块
    /** A 股行业板块：优先新浪，失败退东财。 */
    public List<Map<String, Object>> fetchSectors() {
        try {
            return sectorsSina();
        } catch (Exception ignore) {
            // fallthrough
        }
        try {
            String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=200&po=1&np=1&fltt=2"
                    + "&invt=2&fid=f3&fs=m:90+t:2&fields=f3,f12,f14,f104,f105,f62";
            JsonNode d = M.readTree(HttpFetch.getUtf8(url, "https://quote.eastmoney.com/"));
            JsonNode diff = d.path("data").path("diff");
            List<Map<String, Object>> out = new ArrayList<>();
            if (diff.isArray()) {
                for (JsonNode it : diff) out.add(emSector(it));
            } else if (diff.isObject()) {
                diff.fields().forEachRemaining(e -> out.add(emSector(e.getValue())));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> emSector(JsonNode it) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", it.path("f14").asText(null));
        o.put("pct", U.num(it.path("f3").asText("0")));
        o.put("up", it.path("f104").asInt(0));
        o.put("down", it.path("f105").asInt(0));
        o.put("flow", U.round(U.num(it.path("f62").asText("0")) / 1e8, 2));
        return o;
    }

    /** 新浪行业板块：字段 0代码,1名称,2家数,3均价,4涨跌额,5涨跌幅%… */
    private List<Map<String, Object>> sectorsSina() throws IOException {
        String raw = new String(
                HttpFetch.get("https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php",
                        "https://finance.sina.com.cn"), GBK);
        int l = raw.indexOf('{'), r = raw.lastIndexOf('}');
        if (l < 0 || r <= l) return List.of();
        JsonNode body = M.readTree(raw.substring(l, r + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        body.fields().forEachRemaining(e -> {
            String[] f = e.getValue().asText().split(",");
            if (f.length < 6) return;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", f[1]);
            o.put("pct", U.round(U.num(f[5]), 2));
            o.put("up", 0);
            o.put("down", 0);
            o.put("count", (int) U.num(f[2]));
            out.add(o);
        });
        return out;
    }

    // ---------------------------------------------------------------- 交易时段
    public Map<String, Object> sessionState() {
        ZonedDateTime now = ZonedDateTime.now(CST);
        boolean wd = now.getDayOfWeek().getValue() <= 5;
        int hm = now.getHour() * 60 + now.getMinute();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", session(wd, hm, 9 * 60 + 30, 11 * 60 + 30, 13 * 60, 15 * 60));
        out.put("hk", session(wd, hm, 9 * 60 + 30, 12 * 60, 13 * 60, 16 * 60));
        // 美股：北京时间 21:30/22:30 开盘，次日 4:00/5:00 收盘（简化）
        Map<String, Object> us = new LinkedHashMap<>();
        if (wd && (hm >= 21 * 60 + 30 || hm < 4 * 60)) {
            us.put("state", "交易中"); us.put("cls", "live");
        } else if (wd && hm < 21 * 60 + 30) {
            us.put("state", "未开盘"); us.put("cls", "pre");
        } else {
            us.put("state", "已收盘"); us.put("cls", "closed");
        }
        out.put("us", us);
        out.put("etf", out.get("a"));
        // 场外基金按净值日频更新，展示跟随 A 股交易时段即可
        out.put("fund", out.get("a"));
        out.put("server_time", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    private Map<String, Object> session(boolean wd, int hm,
                                        int openAm, int closeAm, int openPm, int closePm) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!wd) { m.put("state", "休市"); m.put("cls", "closed"); return m; }
        if (hm >= openAm && hm < closeAm) { m.put("state", "交易中"); m.put("cls", "live"); return m; }
        if (openPm > 0 && hm >= openPm && hm < closePm) { m.put("state", "交易中"); m.put("cls", "live"); return m; }
        if (hm >= closeAm && hm < (openPm > 0 ? openPm : closePm)) {
            m.put("state", "午间休市"); m.put("cls", "break"); return m;
        }
        if (hm < openAm) { m.put("state", "未开盘"); m.put("cls", "pre"); return m; }
        m.put("state", "已收盘"); m.put("cls", "closed");
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
