package com.quantboard.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场外基金数据层（含债基）。
 * 净值历史 = 天天基金 pingzhongdata/{code}.js（单位净值 + 日增长率，可推复权序列）；
 * 搜索 = fundsuggest FundSearchAPI。基金无盘中估值接口（fundgz 已下线），日频净值即可。
 * 内部代码 = "f" + 6 位基金代码，如 f003847。
 */
@Service
public class FundService {
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper M = new ObjectMapper();
    private static final long TTL_MS = 900_000;   // 15 分钟
    private static final long NEG_MS = 60_000;    // 失败负缓存

    /** 一只基金的净值序列与派生指标。 */
    public static final class Fund {
        public final String code;
        public final String name;
        public final List<Object[]> bars;   // [date, open, close(复权), high, low, volume=0] 升序
        public final double lastNav;        // 最新单位净值
        public final double lastPct;        // 最新日增长率 %
        public final String navDate;        // 净值日期
        public final Double accNav;         // 最新累计净值（可能缺）
        public final Map<String, Double> returns; // w1/m1/m3/m6/y1/ytd 区间收益 %

        Fund(String code, String name, List<Object[]> bars, double lastNav, double lastPct,
             String navDate, Double accNav, Map<String, Double> returns) {
            this.code = code; this.name = name; this.bars = bars;
            this.lastNav = lastNav; this.lastPct = lastPct; this.navDate = navDate;
            this.accNav = accNav; this.returns = returns;
        }
    }

    private record Cache(long ts, Fund fund) {}

    private final Map<String, Cache> mem = new ConcurrentHashMap<>();

    public static boolean isFund(String code) {
        return code != null && code.matches("f\\d{6}");
    }

    public List<Object[]> klineBars(String code) {
        Fund f = fetchFund(code);
        return f == null ? List.of() : f.bars;
    }

    /** 拉取并解析一只基金，失败返回 null（60 秒负缓存）。 */
    public Fund fetchFund(String code) {
        String raw = code.substring(1);
        long now = System.currentTimeMillis();
        Cache hit = mem.get(code);
        if (hit != null) {
            if (now - hit.ts < (hit.fund != null ? TTL_MS : NEG_MS)) return hit.fund;
        }
        Fund f = fetchRemote(code, raw);
        mem.put(code, new Cache(now, f));
        return f;
    }

    private Fund fetchRemote(String code, String raw6) {
        try {
            String url = "https://fund.eastmoney.com/pingzhongdata/" + raw6 + ".js";
            String js = new String(HttpFetch.get(url, "https://fund.eastmoney.com/"), StandardCharsets.UTF_8);
            if (js.isEmpty()) return null;
            String name = extractStr(js, "fS_name");
            JsonNode trend = extractArray(js, "netWorthTrend");
            if (trend == null || !trend.isArray() || trend.isEmpty()) return null; // 货币基金等无单位净值序列

            // 复权序列：close[i] = close[i-1] * (1 + equityReturn/100)，分红/拆分不产生假跳空
            List<Object[]> bars = new ArrayList<>(trend.size());
            double prevAdj = 0;
            JsonNode first = trend.get(0);
            prevAdj = first.path("y").asDouble(0);
            String d0 = toDay(first.path("x").asLong(0));
            bars.add(new Object[]{d0, prevAdj, prevAdj, prevAdj, prevAdj, 0d});
            for (int i = 1; i < trend.size(); i++) {
                JsonNode e = trend.get(i);
                double y = e.path("y").asDouble(0);
                double er = e.path("equityReturn").asDouble(Double.NaN);
                double close = Double.isFinite(er) && prevAdj > 0 ? prevAdj * (1 + er / 100.0) : y;
                close = Math.max(close, 0.0001);
                String day = toDay(e.path("x").asLong(0));
                double open = prevAdj;
                bars.add(new Object[]{day, r3(open), r3(close), r3(Math.max(open, close)),
                        r3(Math.min(open, close)), 0d});
                prevAdj = close;
            }
            if (bars.size() < 2) return null;

            JsonNode last = trend.get(trend.size() - 1);
            double lastNav = last.path("y").asDouble(0);
            double lastPct = last.path("equityReturn").asDouble(0);
            String navDate = toDay(last.path("x").asLong(0));

            Double accNav = null;
            JsonNode acc = extractArray(js, "ACWorthTrend");
            if (acc != null && acc.isArray() && !acc.isEmpty()) {
                JsonNode al = acc.get(acc.size() - 1);
                if (al.isArray() && al.size() >= 2) accNav = al.get(1).asDouble(0);
            }

            List<Double> closes = new ArrayList<>(bars.size());
            for (Object[] b : bars) closes.add((Double) b[2]);
            Map<String, Double> returns = periodReturns(bars, closes);
            return new Fund(code, name != null ? name : code, bars, lastNav, lastPct,
                    navDate, accNav, returns);
        } catch (Exception e) {
            return null;
        }
    }

    /** 区间收益率（%）：近1周/1月/3月/6月/1年/今年来。 */
    private static Map<String, Double> periodReturns(List<Object[]> bars, List<Double> closes) {
        int n = closes.size();
        Map<String, Double> out = new LinkedHashMap<>();
        double last = closes.get(n - 1);
        String curYear = String.valueOf(LocalDate.now(CST).getYear());
        Double ytd = null;
        for (int i = 1; i < n; i++) {
            if (((String) bars.get(i)[0]).startsWith(curYear)) {
                double base = closes.get(i - 1);
                ytd = base > 0 ? com.quantboard.util.U.round((last / base - 1) * 100, 2) : null;
                break;
            }
        }
        out.put("w1", ret(closes, last, n - 1 - 5));
        out.put("m1", ret(closes, last, n - 1 - 21));
        out.put("m3", ret(closes, last, n - 1 - 63));
        out.put("m6", ret(closes, last, n - 1 - 126));
        out.put("y1", ret(closes, last, n - 1 - 250));
        out.put("ytd", ytd);
        return out;
    }

    private static Double ret(List<Double> closes, double last, int idx) {
        if (idx < 0 || idx >= closes.size()) return null;
        double base = closes.get(idx);
        return base > 0 ? com.quantboard.util.U.round((last / base - 1) * 100, 2) : null;
    }

    private static double r3(double v) {
        return com.quantboard.util.U.round(v, 4);
    }

    private static String toDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(CST).toLocalDate().toString();
    }

    private static String extractStr(String js, String key) {
        int i = js.indexOf(key);
        if (i < 0) return null;
        int q1 = js.indexOf('"', i);
        if (q1 < 0) return null;
        int q2 = js.indexOf('"', q1 + 1);
        return q2 < 0 ? null : js.substring(q1 + 1, q2);
    }

    /** 提取 key = [...] 的 JSON 数组（括号配对扫描）。 */
    private static JsonNode extractArray(String js, String key) {
        int k = js.indexOf(key);
        if (k < 0) return null;
        int s = js.indexOf('[', k);
        if (s < 0) return null;
        int depth = 0;
        for (int i = s; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    try {
                        return M.readTree(js.substring(s, i + 1));
                    } catch (IOException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 报价
    /** 与股票 quote 字段对齐的快照，供 BoardService 合并。 */
    public Map<String, Map<String, Object>> fetchQuotes(List<String> codes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String code : codes) {
            Fund f = fetchFund(code);
            if (f == null || f.bars.isEmpty()) continue;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("code", code);
            q.put("name", f.name);
            q.put("price", com.quantboard.util.U.round(f.lastNav, 4));
            q.put("prev_close", null);
            q.put("open", null);
            q.put("high", null);
            q.put("low", null);
            q.put("change", com.quantboard.util.U.round(f.lastNav * f.lastPct / 100, 4));
            q.put("pct", com.quantboard.util.U.round(f.lastPct, 2));
            q.put("volume", null);
            q.put("amount_yi", null);
            q.put("turnover", null);
            q.put("pe", null);
            q.put("amplitude", null);
            q.put("mktcap_yi", null);
            q.put("nav", com.quantboard.util.U.round(f.lastNav, 4));
            q.put("nav_acc", f.accNav);
            q.put("nav_date", f.navDate);
            q.put("returns", f.returns);
            q.put("currency", "CNY");
            q.put("unit", "亿");
            q.put("time", f.navDate + " 净值");
            out.put(code, q);
        }
        return out;
    }

    // ---------------------------------------------------------------- 搜索
    /** 天天基金搜索，返回与 /api/search 对齐的条目。 */
    public List<Map<String, Object>> searchFunds(String q, int count) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return List.of();
        String url = "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key="
                + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8);
        JsonNode d;
        try {
            d = M.readTree(HttpFetch.getUtf8(url, "https://fund.eastmoney.com/"));
        } catch (IOException e) {
            return List.of();
        }
        JsonNode rows = d.path("Datas");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!rows.isArray()) return out;
        for (JsonNode r : rows) {
            String code = r.path("CODE").asText("");
            if (!code.matches("\\d{6}")) continue;
            String name = r.path("NAME").asText(code);
            JsonNode base = r.path("FundBaseInfo");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("code", "f" + code);
            o.put("name", name);
            o.put("grp", "fund");
            o.put("kind", "fund");
            o.put("market", "CN");
            o.put("currency", "CNY");
            o.put("type_label", base.path("FTYPE").asText("基金"));
            o.put("nav", base.path("DWJZ").asDouble(0));
            o.put("nav_date", base.path("FSRQ").asText(""));
            out.add(o);
            if (out.size() >= count) break;
        }
        return out;
    }
}
