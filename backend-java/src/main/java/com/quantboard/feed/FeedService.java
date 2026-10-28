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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private static final long KLINE_RAW_TTL_MS = 6 * 3600_000L;   // 不复权序列 6 小时

    private final Map<String, Bars> klineMem = new ConcurrentHashMap<>();
    private final Map<String, Bars> klineRawMem = new ConcurrentHashMap<>();
    private final Map<String, Bars> klineFqMem = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> quoteCache = new ConcurrentHashMap<>();
    private final Map<String, Long> quoteTs = new ConcurrentHashMap<>();
    /** single-flight：正在拉取的标的 → 结果 Future（同一批代码并发时只打一次腾讯）。 */
    private final Map<String, CompletableFuture<Map<String, Object>>> inflight = new ConcurrentHashMap<>();
    private final Object flightLock = new Object();
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
    /**
     * 批量实时报价。带 per-code TTL 缓存：同一个标的的报价在短时间窗口内复用，
     * 避免一次页面请求里多个环节各拉一次腾讯行情（收益页曾经因此叠加出 7 次 HTTP）。
     *
     * @param force 为 true 时跳过缓存强制拉取（用于用户主动刷新）
     */
    public Map<String, Map<String, Object>> fetchQuotes(List<String> codes) {
        return fetchQuotes(codes, false);
    }

    public Map<String, Map<String, Object>> fetchQuotes(List<String> codes, boolean force) {
        // 场外基金不走腾讯行情（由 FundService 提供），调用方应自行拆分
        List<String> want = codes.stream().filter(c -> !FundService.isFund(c)).distinct().toList();
        long ttl = force ? 0 : quoteTtlMs();
        long now = System.currentTimeMillis();
        List<String> need = new ArrayList<>();
        Map<String, Map<String, Object>> cached = new LinkedHashMap<>();
        for (String c : want) {
            Map<String, Object> hit = quoteCache.get(c);
            if (hit != null && now - quoteTs.getOrDefault(c, 0L) < ttl) cached.put(c, hit);
            else need.add(c);
        }
        Map<String, Map<String, Object>> fresh = new LinkedHashMap<>();
        if (!need.isEmpty()) {
            // single-flight：先占坑，只让一个线程真正去打腾讯；同批的其余请求等它的结果。
            // 之前 board 后台刷新 / 收益页 / 家庭页会各拉一次同一批代码，既浪费往返又把重试概率放大。
            List<String> lead = new ArrayList<>();
            Map<String, CompletableFuture<Map<String, Object>>> waiters = new LinkedHashMap<>();
            synchronized (flightLock) {
                for (String c : need) {
                    CompletableFuture<Map<String, Object>> f = inflight.get(c);
                    if (f == null) {
                        f = new CompletableFuture<>();
                        inflight.put(c, f);
                        lead.add(c);
                    }
                    waiters.put(c, f);
                }
            }
            if (!lead.isEmpty()) {
                Map<String, Map<String, Object>> got = new LinkedHashMap<>();
                try {
                    got = fetchQuotesRemote(lead);
                } catch (RuntimeException ignore) {
                    // 远端异常不向上抛：下面按“本轮失败”回退旧快照
                } finally {
                    long t = System.currentTimeMillis();
                    for (String c : lead) {
                        Map<String, Object> q = got.get(c);
                        if (q != null) {
                            quoteCache.put(c, q);
                            quoteTs.put(c, t);
                            fresh.put(c, q);
                        }
                        CompletableFuture<Map<String, Object>> f = inflight.remove(c);
                        if (f != null) f.complete(q);
                    }
                }
            }
            for (var e : waiters.entrySet()) {
                if (fresh.containsKey(e.getKey())) continue;
                try {
                    Map<String, Object> q = e.getValue().get(8, TimeUnit.SECONDS);
                    if (q != null) fresh.put(e.getKey(), q);
                } catch (Exception ignore) {
                    // 等超时：退回上一份快照，不阻塞请求线程更久
                }
            }
        }
        long t = System.currentTimeMillis();
        for (var e : fresh.entrySet()) {
            quoteCache.put(e.getKey(), e.getValue());
            quoteTs.put(e.getKey(), t);
        }
        // 保持入参顺序：请求失败的标的沿用上一次成功快照，避免整行消失
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String c : want) {
            Map<String, Object> q = fresh.get(c);
            if (q == null) q = cached.get(c);
            if (q == null) q = quoteCache.get(c);
            if (q != null) out.put(c, q);
        }
        return out;
    }

    /** 报价缓存时长：交易时段新鲜度要求高（15s），休市时可以放宽到 5 分钟。 */
    private static long quoteTtlMs() {
        return tradingHours() ? 15_000L : 300_000L;
    }

    /** A 股交易时段（含美股盘 A 股收盘后仍按提高新鲜度处理，保守取 8:00-16:00 与 20:30-次日 5:00）。 */
    private static boolean tradingHours() {
        ZonedDateTime t = ZonedDateTime.now(CST);
        int dow = t.getDayOfWeek().getValue();
        if (dow >= 6) return false;                       // 周末休市
        int min = t.getHour() * 60 + t.getMinute();
        return (min >= 510 && min <= 960) || min >= 1230 || min <= 300;   // 9:00-16:00 / 20:30-5:00(美股)
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> fetchQuotesRemote(List<String> codes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
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
            out.putAll(parseTencentRaw(raw, back));
        }
        // 美股补充盘前/盘后（夜盘）行情：腾讯报价不含非常规时段数据，从新浪 gb_ 接口补齐。
        // 任一环节失败只跳过夜盘字段，不影响主报价。
        try {
            mergeUsExt(out);
        } catch (Exception ignore) {
        }
        // 注意：这里不要自行回填上一次的旧快照——那会被上层当成新鲜数据打上时间戳。
        // 失败标的的兜底统一在 {@link #fetchQuotes} 里做。
        return out;
    }

    /**
     * 解析腾讯 qt.gtimg.cn 的批量报价响应（调用方已按 GBK 解码）。
     * 单独抽出来是为了能脱离 HTTP 做单测：抓一份真实响应存进 fixtures，
     * 就能覆盖字段位错位、ETF 净值/溢价、港股美股成交额口径、指数成交额置空这些分支。
     *
     * @param back 远端代码 → 调用方原始 code 的映射（美股请求前转大写，返回时还原）
     */
    static Map<String, Map<String, Object>> parseTencentRaw(String raw, Map<String, String> back) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
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
        return out;
    }

    /**
     * 美股夜盘（盘前/盘后）字段合并：新浪 gb_ 实时接口含非常规时段行情，
     * 字段（split(",")，0 起）：21=夜盘最新价 22=涨跌幅% 23=涨跌额 24=夜盘成交时间(美东如 Oct 27 07:59PM EDT)。
     * 实测五只大盘股校验：ext_pct ≈ (夜盘价-常规收盘)/常规收盘，语义与涨跌幅一致。
     */
    private void mergeUsExt(Map<String, Map<String, Object>> out) {
        List<String> syms = new ArrayList<>();
        for (String c : out.keySet()) {
            if (c.length() > 2 && c.startsWith("us") && !"index".equals(Universe.codeKind(c))) {
                syms.add("gb_" + c.substring(2).toLowerCase());
            }
        }
        if (syms.isEmpty()) return;
        String raw;
        try {
            raw = new String(HttpFetch.get("https://hq.sinajs.cn/list=" + String.join(",", syms),
                    "https://finance.sina.com.cn"), GBK);
        } catch (IOException e) {
            return;
        }
        for (String line0 : raw.split("\n")) {
            String line = line0.trim();
            int eq = line.indexOf("=\"");
            // 新浪返回行格式：var hq_str_gb_aapl="苹果,347.10,..."
            int k = line.indexOf("hq_str_gb_");
            if (k < 0 || eq < 0) continue;
            String code = "us" + line.substring(k + "hq_str_gb_".length(), eq).toUpperCase();
            Map<String, Object> q = out.get(code);
            if (q == null) continue;
            String body = line.substring(eq + 2);
            if (body.endsWith(";")) body = body.substring(0, body.length() - 1);
            if (body.endsWith("\"")) body = body.substring(0, body.length() - 1);
            String[] f = body.split(",");
            if (f.length < 25) continue;
            double extPrice = U.num(f[21]);
            if (extPrice <= 0) continue;
            q.put("ext_price", U.round(extPrice, 4));
            q.put("ext_pct", U.num(f[22]));
            q.put("ext_change", U.num(f[23]));
            q.put("ext_time", f[24]);
            q.put("ext_tag", extTag(f[24]));
        }
    }

    /** 夜盘时段标签：美东 04:00-09:30 成交的归「盘前」，其余（盘后及隔夜留存的最新一笔）归「盘后」。 */
    private static String extTag(String ts) {
        if (ts == null) return "盘后";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2}):(\\d{2})(AM|PM)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(ts);
        if (!m.find()) return "盘后";
        int h = Integer.parseInt(m.group(1)) % 12 + (m.group(3).equalsIgnoreCase("PM") ? 12 : 0);
        return h < 9 ? "盘前" : "盘后";
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
            attempts.add(this::klineEmUs);
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
        return parseTencentArray(rows);
    }

    private List<Object[]> parseTencentArray(JsonNode rows) {
        List<Object[]> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) return out;
        for (JsonNode r : rows) {
            if (r.size() < 6) continue;
            out.add(new Object[]{r.get(0).asText(), U.num(r.get(1).asText()), U.num(r.get(2).asText()),
                    U.num(r.get(3).asText()), U.num(r.get(4).asText()), U.num(r.get(5).asText())});
        }
        return out;
    }

    /**
     * 腾讯 fqkline 通用周期：day / week / month。
     * count 上限约 800（day 超过 800 直接返回空）；fq 传 qfq / bfq。
     * 注意腾讯 month 的 qfq 是加法复权，长历史会出负价，月线要用 bfq。
     */
    private List<Object[]> tencentKline(String code, String period, int count, String fq) throws IOException {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + code
                + "," + period + ",,," + count + "," + fq;
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
        JsonNode node = d.path("data").path(code);
        if (node.isMissingNode()) return List.of();
        for (String k : new String[]{"qfq" + period, period}) {
            JsonNode rows = node.path(k);
            if (rows.isArray() && !rows.isEmpty()) return parseTencentArray(rows);
        }
        return List.of();
    }

    /**
     * 美股：腾讯 usfqkline。交易所后缀必须精确，错了只返回 1 根伪数据，
     * 因此按 缓存 → 已知映射 → .OQ/.N/.AM 依次探测，取第一份 ≥20 根的结果并记住后缀。
     */
    private List<Object[]> klineUsTencent(String code) throws IOException {
        for (String sym : Universe.usTencentCandidates(code)) {
            List<Object[]> bars = tencentUs(sym);
            if (bars.size() >= 20) {
                Universe.rememberUsSymbol(code, sym);
                return bars;
            }
            sleep(120);
        }
        return List.of();
    }

    private List<Object[]> tencentUs(String sym) throws IOException {
        return tencentUs(sym, "day", 320);
    }

    /** 美股腾讯通用周期（usfqkline）：day / week / month。 */
    private List<Object[]> tencentUs(String sym, String period, int count) throws IOException {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=" + sym
                + "," + period + ",,," + count + ",qfq";
        JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
        JsonNode node = d.path("data").path(sym);
        if (node.isMissingNode()) return List.of();
        for (String k : new String[]{"qfq" + period, period}) {
            JsonNode rows = node.path(k);
            if (rows.isArray() && !rows.isEmpty()) return parseTencentArray(rows);
        }
        return List.of();
    }

    /** 东财美股：secid 市场号不定（105 纳斯达克 / 106 纽交所 / 107 美交所），逐个试。 */
    private List<Object[]> klineEmUs(String code) throws IOException {
        for (String sid : Universe.usSecidCandidates(code)) {
            List<Object[]> bars = emKline(sid, 1, 560, "101");
            if (bars.size() >= 20) return bars;
            sleep(120);
        }
        return List.of();
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

    /** 东财 K 线（备用，前复权）。 */
    private List<Object[]> klineEm(String code) throws IOException {
        String sid = Universe.secid(code);
        if (sid.isEmpty()) return List.of();
        return emKline(sid, 1, 560, "101");
    }

    private final Map<String, Bars> klineAllMem = new ConcurrentHashMap<>();
    private final Map<String, Object[]> trendsMem = new ConcurrentHashMap<>();   // code → [ts, prices, preClose, times]

    /**
     * 当天盘中分时（1 分钟收盘价序列 + 昨收基准 + 时间标签）：东财 trends2，A/HK/US 通用。
     * 缓存 60s；失败或不支持（基金/无 secid）返回 null，由调用方退回日线。
     */
    public Map<String, Object> fetchTrends(String code) {
        if (FundService.isFund(code)) return null;
        long now = System.currentTimeMillis();
        Object[] hit = trendsMem.get(code);
        // 空序列为负缓存（30s），避免远端限流时高频重试；正常缓存 60s
        if (hit != null && now - (long) hit[0] < (((List<?>) hit[1]).isEmpty() ? 30_000L : 60_000L)) {
            return ((List<?>) hit[1]).isEmpty() ? null : trendOut(hit);
        }
        Map<String, Object> out = trendsEm(code);
        if (out == null) out = trendsTencent(code);   // 东财被限流时退腾讯 minute
        if (out == null) trendsMem.put(code, new Object[]{now, List.of(), 0.0, List.of()});
        return out;
    }

    private Map<String, Object> trendsEm(String code) {
        Map<String, Object> out = null;
        try {
            String sid = Universe.secid(code);
            if (!sid.isEmpty()) {
                String url = "https://push2his.eastmoney.com/api/qt/stock/trends2/get?"
                        + "secid=" + sid + "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,f14,f15,f16,f17,f18"
                        + "&fields2=f51,f53&iscr=0&ndays=1";
                JsonNode d = M.readTree(HttpFetch.getUtf8(url, "https://quote.eastmoney.com/"));
                JsonNode data = d.path("data");
                JsonNode rows = data.path("trends");
                if (!rows.isMissingNode() && rows.size() > 1) {
                    List<Double> prices = new ArrayList<>(rows.size());
                    List<String> times = new ArrayList<>(rows.size());
                    for (JsonNode r : rows) {
                        String[] p = r.asText().split(",");
                        if (p.length >= 2) {
                            prices.add(U.num(p[1]));
                            times.add(p[0].length() >= 16 ? p[0].substring(11, 16) : p[0]);
                        }
                    }
                    if (prices.size() > 1) {
                        double pre = U.num(data.path("preClose"));
                        Object[] cache = new Object[]{System.currentTimeMillis(), prices, pre, times};
                        out = trendOut(cache);
                        trendsMem.put(code, cache);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 腾讯 minute 接口备用源："0930 3676.92 ..." 序列，昨收取 qt[code][4]。 */
    private Map<String, Object> trendsTencent(String code) {
        try {
            String url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=" + code;
            JsonNode d = M.readTree(HttpFetch.getUtf8(url, null));
            JsonNode node = d.path("data").path(code);
            JsonNode rows = node.path("data").path("data");
            if (!rows.isMissingNode() && rows.size() > 1) {
                List<Double> prices = new ArrayList<>(rows.size());
                List<String> times = new ArrayList<>(rows.size());
                for (JsonNode r : rows) {
                    String[] p = r.asText().trim().split("\\s+");
                    if (p.length >= 2 && p[0].length() >= 4) {
                        times.add(p[0].substring(0, 2) + ":" + p[0].substring(2, 4));
                        prices.add(U.num(p[1]));
                    }
                }
                JsonNode qt = node.path("qt").path(code);
                double pre = qt.isArray() && qt.size() > 4 ? U.num(qt.get(4).asText()) : 0;
                if (prices.size() > 1) {
                    Object[] cache = new Object[]{System.currentTimeMillis(), prices, pre, times};
                    trendsMem.put(code, cache);
                    return trendOut(cache);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static Map<String, Object> trendOut(Object[] src) {
        @SuppressWarnings("unchecked")
        List<Double> prices = (List<Double>) src[1];
        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) src[3];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prices", prices);
        m.put("preClose", src[2]);
        m.put("times", times);
        return m;
    }


    /**
     * 上市至今全量日线（前复权）：东财 beg=0 直取；美股逐 secid 探测；
     * 基金走净值复权序列；全部失败则退回普通日线源（长度受限）。
     */
    public List<Object[]> fetchKlineAll(String code) {
        long now = System.currentTimeMillis();
        Bars hit = klineAllMem.get(code);
        if (hit != null && now - hit.ts < 3600_000L && !hit.rows.isEmpty()) return hit.rows;
        List<Object[]> bars = List.of();
        try {
            if (FundService.isFund(code)) {
                bars = fundService.klineBars(code);
            } else if (code.startsWith("us")) {
                for (String sid : Universe.usSecidCandidates(code)) {
                    bars = emKline(sid, 1, 0, "101");
                    if (bars.size() >= 20) break;
                    sleep(120);
                }
            } else {
                String sid = Universe.secid(code);
                if (!sid.isEmpty()) bars = emKline(sid, 1, 0, "101");
            }
        } catch (Exception ignore) {
        }
        if (bars.isEmpty() && !code.startsWith("us") && !FundService.isFund(code)) {
            try {
                bars = tencentKline(code, "day", 800, "qfq");   // 东财被限流时，腾讯能补 3 年
            } catch (Exception ignore) {
            }
        }
        if (bars.isEmpty()) bars = fetchKline(code, 1000000);
        if (!bars.isEmpty()) klineAllMem.put(code, new Bars(now, bars, false));
        return bars;
    }

    /**
     * 月线序列（上市至今）+ 复权口径：东财月 K（前复权）→ 腾讯月 K（不复权）→ 全量日线聚合。
     * 供详情页 月线 / 季线 / 年线 三个周期共用。
     *
     * <p>坑位记录：
     * <ul>
     *   <li>东财 push2his 对高频请求常回空/超时（curl rc=52/28），此时只能靠日线聚合，
     *       而 A 股日线主源新浪固定 datalen=320，聚合出来只有 16 个月、年线只剩 1~2 根
     *       —— 这就是「月线/年线只剩最近一两年」的根因。</li>
     *   <li>东财月 K 必须 beg=0；带具体起始日期时只回最近十几根。</li>
     *   <li>腾讯 month 的 qfq 是「加法复权」，长历史会算出负价（2002 年 -13.16），
     *       所以腾讯只能取不复权原始价，并在返回值里标注 adj=none。</li>
     * </ul>
     */
    public record MonthlyBars(List<Object[]> bars, String adj) {}

    private record MonthEntry(long ts, List<Object[]> rows, String adj) {}

    private final Map<String, MonthEntry> klineMonthCache = new ConcurrentHashMap<>();

    public List<Object[]> fetchKlineMonthly(String code) {
        return fetchMonthly(code).bars();
    }

    public MonthlyBars fetchMonthly(String code) {
        long now = System.currentTimeMillis();
        MonthEntry hit = klineMonthCache.get(code);
        if (hit != null && now - hit.ts() < 3600_000L && !hit.rows().isEmpty()) {
            return new MonthlyBars(hit.rows(), hit.adj());
        }
        List<Object[]> bars = List.of();
        String adj = "qfq";
        // 1) 东财月 K：前复权、口径与日线一致，优先
        try {
            String sid = Universe.secid(code);
            if (!FundService.isFund(code) && !sid.isEmpty()) bars = emKline(sid, 1, 0, "103");
        } catch (Exception ignore) {
        }
        // 2) 腾讯月 K：A/HK 用不复权原始价（长历史正确）；美股走 usfqkline
        if (bars.size() < 24) {
            try {
                if (code.startsWith("us")) {
                    for (String sym : Universe.usTencentCandidates(code)) {
                        List<Object[]> us = tencentUs(sym, "month", 320);
                        if (us.size() > bars.size()) { bars = us; adj = "qfq"; }
                        if (us.size() >= 24) break;
                    }
                } else if (!FundService.isFund(code)) {
                    List<Object[]> tc = tencentKline(code, "month", 400, "bfq");
                    if (tc.size() > bars.size()) { bars = tc; adj = "none"; }
                }
            } catch (Exception ignore) {
            }
        }
        // 3) 兜底：全量日线聚合（绝不能用 fetchKline(code, n)，它只有 320 根）
        if (bars.size() < 24) {
            List<Object[]> agg = aggregateBars(fetchKlineAll(code), 1);
            if (agg.size() > bars.size()) { bars = agg; adj = "qfq"; }
        }
        if (!bars.isEmpty()) klineMonthCache.put(code, new MonthEntry(now, bars, adj));
        return new MonthlyBars(bars, adj);
    }

    /**
     * 日线/月线按日历桶聚合：mode 1=月 2=季 3=年。
     * 桶内 open=首日、close=末日、high=max、low=min、volume=累加；date 取桶内最后一个交易日。
     */
    public static List<Object[]> aggregateBars(List<Object[]> bars, int mode) {
        if (mode < 1 || mode > 3 || bars == null || bars.isEmpty()) return bars;
        java.util.LinkedHashMap<String, Object[]> out = new java.util.LinkedHashMap<>();
        for (Object[] b : bars) {
            String d = String.valueOf(b[0]);
            if (d.length() < 7) continue;
            int mi;
            try {
                mi = Integer.parseInt(d.substring(5, 7));
            } catch (NumberFormatException e) {
                continue;
            }
            String y = d.substring(0, 4);
            String key = mode == 1 ? y + "-" + d.substring(5, 7)
                    : mode == 2 ? y + "Q" + ((mi - 1) / 3 + 1)
                    : y;
            Object[] acc = out.get(key);
            if (acc == null) {
                out.put(key, new Object[]{d, b[1], b[2], b[3], b[4], b[5]});
            } else {
                acc[0] = d;   // 日期取桶内最后一个交易日
                acc[2] = b[2];
                acc[3] = U.num(acc[3]) > U.num(b[3]) ? acc[3] : b[3];
                acc[4] = U.num(acc[4]) < U.num(b[4]) ? acc[4] : b[4];
                acc[5] = U.num(acc[5]) + U.num(b[5]);
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 美股 K 线「同源双序列」之一：fqt=0 不复权 / fqt=1 前复权。
     * 两条必须来自同一个源（都是东财、同样的起止区间），否则 qfq/bfq 比值没有意义。
     * 仅供「复权因子反推每股股息」使用；正常 K 线走 fetchKline。
     */
    private List<Object[]> klineEmPair(String code, int fqt) throws IOException {
        for (String sid : Universe.usSecidCandidates(code)) {
            List<Object[]> bars = emKline(sid, fqt, 900, "101");
            if (!bars.isEmpty()) return bars;
            sleep(150);
        }
        return List.of();
    }

    /** 不复权日线（东财 fqt=0）。 */
    public List<Object[]> fetchKlineRaw(String code) {
        return withCache(klineRawMem, code, c -> klineEmPair(c, 0));
    }

    /** 前复权日线（东财 fqt=1），与 {@link #fetchKlineRaw} 同源同时段。 */
    public List<Object[]> fetchKlineForward(String code) {
        return withCache(klineFqMem, code, c -> klineEmPair(c, 1));
    }

    private List<Object[]> withCache(Map<String, Bars> mem, String code, KlineSource src) {
        long now = System.currentTimeMillis();
        Bars hit = mem.get(code);
        if (hit != null && now - hit.ts < KLINE_RAW_TTL_MS) return hit.rows;
        List<Object[]> bars = new ArrayList<>();
        for (int attempt = 0; attempt < 2 && bars.isEmpty(); attempt++) {
            try {
                bars = src.fetch(code);
            } catch (Exception ignore) {
            }
            if (bars.isEmpty()) sleep(400 + 600L * attempt);
        }
        mem.put(code, new Bars(now, bars, bars.isEmpty()));
        return bars;
    }

    private List<Object[]> emKline(String sid, int fqt, int begDays, String klt) throws IOException {
        String end = java.time.LocalDate.now(CST).format(DateTimeFormatter.BASIC_ISO_DATE);
        // begDays <= 0 表示从上市日起全量（东财 beg=0 即上市首日）
        String beg = begDays > 0
                ? java.time.LocalDate.now(CST).minusDays(begDays).format(DateTimeFormatter.BASIC_ISO_DATE)
                : "0";
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?"
                + "secid=" + sid + "&klt=" + klt + "&fqt=" + fqt + "&beg=" + beg + "&end=" + end
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
