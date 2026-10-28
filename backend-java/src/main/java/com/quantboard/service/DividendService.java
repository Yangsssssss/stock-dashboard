package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.feed.FeedService;
import com.quantboard.feed.HttpFetch;
import com.quantboard.util.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分红自动入账：每天（每晚 8 点 + 服务启动补跑）查一次当前持仓的 A 股分红方案，
 * 发现近 10 日内除权除息、且登记日实际持股 > 0 的，直接把 DIV 流水写入 trade 表（幂等：±窗口内已有分红则跳过）。
 * 金额按税前推算（登记日持股 × 每股派息），与到账不符时在「交易记录」里改那笔即可。
 * 港股 / 场外基金无稳定公开数据源，仍需手动记账；美股股息改由三级降级
 * （纳斯达克官方 → dividendhistory.org → 东财复权反推）自动估算。
 */
@Service
public class DividendService {

    private static final Logger log = LoggerFactory.getLogger(DividendService.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final long CACHE_MS = 30 * 60 * 1000L;   // 方案数据 30 分钟缓存
    private static final int MAX_RECORDS_PER_CODE = 8;       // 单只标的最多取几条历史
    private static final int RECENT_DAYS = 10;               // 只自动入账近 N 日内除权的（每日跑，窗口留冗余）

    /** 每 10 股派息（税前，元）出现在 IMPL_PLAN_PROFILE 里。 */
    private static final Pattern AFTER_TAX = Pattern.compile("扣税后([0-9.]+)元");

    private final JdbcTemplate jdbc;
    private final TradeService trades;
    private final FeedService feedService;
    private final Map<String, Cache> cache = new ConcurrentHashMap<>();

    public DividendService(JdbcTemplate jdbc, TradeService trades, FeedService feedService) {
        this.jdbc = jdbc;
        this.trades = trades;
        this.feedService = feedService;
    }

    /**
     * 启动预热：美股股息要跨 3 个外部源取（单只最坏十几秒），冷启动首屏会明显卡住。
     * 这里在应用就绪后后台并行拉一遍填进 12 小时缓存，之后用户请求直接命中。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUpOnBoot() {
        DIV_POOL.submit(() -> {
            try {
                Thread.sleep(2000);   // 让首屏先起来
                List<String> codes;
                try {
                    codes = jdbc.queryForList("SELECT DISTINCT code FROM trade WHERE code LIKE 'us%'", String.class);
                } catch (Exception e) {
                    return;
                }
                if (codes.isEmpty()) return;
                log.info("[dividend-us] 启动预热 {} 只美股", codes.size());
                ttmBatch(codes);
                log.info("[dividend-us] 预热完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignore) { }
        });
    }

    private record Cache(long ts, List<Map<String, Object>> rows, String error) {}

    /**
     * 每日自动入账：每晚 20:00 跑一次；服务（重）启动时补跑一次，兜住停机跨天的情形。
     * 规则：A股持仓 · 近 {@value RECENT_DAYS} 日内除权 · 登记日按流水重放的持股 > 0（重放不出真实持股的不入）
     * · 登记日不早于建仓日 · 同标的同期无 DIV 流水 → 写入一笔 DIV（税前推算，记账日期用股权登记日）。
     */
    @Scheduled(cron = "0 0 20 * * *")
    @EventListener(ApplicationReadyEvent.class)
    public void autoInsertDaily() {
        LocalDate today = LocalDate.now();
        LocalDate recentFrom = today.minusDays(RECENT_DAYS);
        int inserted = 0;
        try {
            for (TradeService.Pos p : trades.aggregate().values()) {
                if (p.closed()) continue;
                String emCode = emStockCode(p.code);
                if (emCode == null) continue;            // 港美股 / 场外基金无数据源，手动记
                LocalDate firstTrade = firstTradeDate(p.broker, p.code);
                for (Map<String, Object> plan : plans(emCode)) {
                    LocalDate exDate = (LocalDate) plan.get("exDate");
                    if (exDate == null || exDate.isBefore(recentFrom) || exDate.isAfter(today)) continue;
                    LocalDate refDate = (LocalDate) plan.get("recordDate");
                    if (refDate == null || refDate.isAfter(exDate)) refDate = exDate;
                    if (firstTrade != null && refDate.isBefore(firstTrade)) continue;
                    HoldAt h = holdAt(p.broker, p.code, refDate, 0);
                    if (h.estimated || h.qty <= 0) continue;
                    if (hasDiv(p.broker, p.code, exDate)) continue;
                    double perShare = U.num(plan.get("perShare"));   // 每股派息（税前）
                    if (perShare <= 0) continue;
                    double amount = U.round(h.qty * perShare, 2);
                    Map<String, Object> body = new HashMap<>();
                    body.put("side", "DIV");
                    body.put("broker", p.broker);
                    body.put("code", p.code);
                    body.put("name", p.name);
                    body.put("trade_date", refDate.toString());
                    body.put("amount", amount);
                    body.put("note", "分红自动入账 · " + String.valueOf(plan.get("plan")).trim());
                    Map<String, Object> r = trades.add(body);
                    if (Boolean.TRUE.equals(r.get("ok"))) {
                        inserted++;
                        log.info("[dividend] 自动入账 {} {} 登记日={} 数量={} 金额={}",
                                p.broker, p.code, refDate, h.qty, amount);
                    } else {
                        log.warn("[dividend] 自动入账失败 {} {}: {}", p.code, refDate, r.get("error"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[dividend] 每日分红检查异常: {}", e.getMessage());
        }
        if (inserted > 0) log.info("[dividend] 每日检查完成，自动入账 {} 笔", inserted);
    }

    /** 某券商某标的在指定日期（含）之后是否已有分红流水，容忍 ±7 天（到账日常晚于除权日）。 */
    private boolean hasDiv(String broker, String code, LocalDate date) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trade WHERE broker=? AND code=? AND side='DIV' "
                            + "AND trade_date BETWEEN ? AND ?",
                    Integer.class, broker, code, date.minusDays(7), date.plusDays(15));
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 某券商某标的的真实建仓日 = 首笔买入 / 卖出（DIV 是补记的，不算）。
     * **不纳入 INIT**：期初建仓行的 trade_date 是迁移当天，不是真实买入日，
     * 用它当起点会把所有历史分红都判成"建仓前"而全部过滤掉，自动核对等于失效。
     * 只有 INIT 时返回 null（建仓日未知），候选照常列出并标「估」，勾选与否交给用户。
     */
    private LocalDate firstTradeDate(String broker, String code) {
        try {
            return jdbc.queryForObject(
                    "SELECT MIN(trade_date) FROM trade WHERE broker=? AND code=? AND side IN ('BUY','SELL')",
                    LocalDate.class, broker, code);
        } catch (Exception e) {
            return null;
        }
    }

    /** 指定日期收盘时的持股数（按流水重放）。 */
    private HoldAt holdAt(String broker, String code, LocalDate date, double fallback) {
        HoldAt h = new HoldAt();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT side, qty, trade_date FROM trade WHERE broker=? AND code=? AND trade_date<=? "
                            + "ORDER BY trade_date, id", broker, code, date);
        } catch (Exception e) {
            h.qty = fallback;
            h.estimated = true;
            return h;
        }
        double qty = 0;
        for (Map<String, Object> r : rows) {
            String side = String.valueOf(r.get("side"));
            double q = U.num(r.get("qty"));
            if ("BUY".equals(side) || "INIT".equals(side) || "SPLIT".equals(side)) qty += q;
            else if ("SELL".equals(side)) qty -= q;
        }
        if (qty > 1e-9) {
            h.qty = qty;
            return h;
        }
        // 登记日之前没有任何建仓记录（期初建仓日期晚于分红日）：退化为按当前持仓估算
        boolean anyBefore = !rows.isEmpty();
        if (!anyBefore && fallback > 0) {
            h.qty = fallback;
            h.estimated = true;
        }
        return h;
    }

    private static final class HoldAt {
        double qty;
        boolean estimated;
    }

    /** sh600036 → 600036；非沪深 A 股（含港股美股基金）返回 null。 */
    private static String emStockCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        if (c.matches("(sh|sz|bj)\\d{6}")) return c.substring(2);
        return null;
    }

    // ---------------------------------------------------------------- 美股：复权因子反推股息
    /**
     * 美股没有稳定的公开分红接口（Nasdaq 只给纳市标的、Yahoo/雪球/stockanalysis 都有反爬），
     * 改用「复权因子断点」推算：取同源的前复权与不复权收盘价，比值在某日跳变即为除息日，
     * 跳变量 × 除息前一日不复权收盘价 = 每股股息。一年 4 次的实际值能对得上。
     */
    private static final long DIV_US_TTL_MS = 12 * 3600_000L;
    private static final double DIV_MIN_CHANGE = 0.0015;     // 复权因子跳变阈值（0.15%），滤掉数据噪声
    private static final Pattern TR = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern TD = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);

    /**
     * 批量并行取「近 12 个月每股股息」。
     * 串行拉取时 8 只美股要 8 × (多个数据源 + 重试) ≈ 几十秒，持仓页首次打开会明显卡住，
     * 所以这里并行 + 单标的软超时，总墙钟由一个 20 秒的兜底上限约束。
     */
    public Map<String, Double> ttmBatch(List<String> codes) {
        List<String> us = codes == null ? List.of()
                : codes.stream().filter(c -> c != null && c.startsWith("us")).distinct().toList();
        if (us.isEmpty()) return Map.of();
        Map<String, Double> out = new java.util.concurrent.ConcurrentHashMap<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (String c : us) {
            futures.add(DIV_POOL.submit(() -> {
                try {
                    double v = usTtmPerShare(c);
                    if (v > 0) out.put(c, v);
                } catch (Exception ignore) { }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try { f.get(20, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignore) { }
        }
        return out;
    }

    private record UsCache(long ts, List<Map<String, Object>> rows) {}

    /** 股息抓取专用线程池：daemon 线程，不阻塞 JVM 退出。 */
    private static final java.util.concurrent.ExecutorService DIV_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "div-pool");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, UsCache> usCache = new ConcurrentHashMap<>();

    /** 某标的每次除息的推算股息：[{date, per_share, src}] 升序。非美股或取不到数据返回空列表。 */
    public List<Map<String, Object>> usDividends(String code) {
        if (code == null || !code.startsWith("us")) return List.of();
        UsCache hit = usCache.get(code);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.ts < DIV_US_TTL_MS) return hit.rows;

        // 纳斯达克官方（一手 JSON）→ dividendhistory（含 NYSE / ARCA ETF）→ 东财复权反推
        List<Map<String, Object>> out = nasdaqDividends(code.substring(2).toUpperCase());
        if (out.isEmpty()) out = dividendHistory(code.substring(2).toUpperCase());
        if (out.isEmpty()) out = inferFromAdjust(code);
        log.info("[dividend-us] {} 事件={} 明细={}", code, out.size(),
                out.subList(Math.max(0, out.size() - 5), out.size()));
        usCache.put(code, new UsCache(now, out));
        return out;
    }

    /**
     * 纳斯达克官方股息接口（含 ETF，assetclass=etf）。
     * 只覆盖在纳斯达克上市的标的 —— 纽交所（KO/BRK.B）与 ARCA 的 SPY/VOO 会直接返回"不支持"。
     */
    private List<Map<String, Object>> nasdaqDividends(String sym) {
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + sym + "/dividends?assetclass=" + assetClass;
                JsonNode rows = M.readTree(HttpFetch.getUtf8(url, "https://www.nasdaq.com/", 6))
                        .path("data").path("dividends").path("rows");
                if (!rows.isArray() || rows.isEmpty()) continue;
                List<Map<String, Object>> out = new ArrayList<>();
                for (JsonNode r : rows) {
                    String[] d = r.path("exOrEffDate").asText("").trim().split("/");
                    if (d.length != 3) continue;
                    double amount = U.num(r.path("amount").asText("").replace("$", "").replace(",", "").trim());
                    if (amount <= 0) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", d[2] + "-" + d[0] + "-" + d[1]);
                    row.put("per_share", U.round(amount, 4));
                    row.put("src", "nasdaq");
                    out.add(row);
                }
                if (out.isEmpty()) continue;
                out.sort(Comparator.comparing(r -> String.valueOf(r.get("date"))));
                return out;
            } catch (Exception ignore) {
                // 换 assetclass 再试；都不行就退回复权反推
            }
        }
        return List.of();
    }

    /**
     * 东财复权因子反推（纳斯达克接口覆盖不到时兜底，如 KO 这类纽交所个股）。
     * 注意：date 用 MM/DD/YYYY 之外的格式时要对齐，这里东财返回的是 yyyy-MM-dd。
     */
    private List<Map<String, Object>> inferFromAdjust(String code) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object[]> fq = feedService.fetchKlineForward(code);
        List<Object[]> raw = feedService.fetchKlineRaw(code);
        if (fq.size() >= 60 && raw.size() >= 60) {
            Map<String, Double> rawClose = new LinkedHashMap<>();
            for (Object[] b : raw) {
                double c = U.num(b[2]);
                if (c > 0) rawClose.put(String.valueOf(b[0]), c);
            }
            double prevFactor = 0, prevRaw = 0;
            for (Object[] b : fq) {
                String day = String.valueOf(b[0]);
                double rc = rawClose.getOrDefault(day, 0.0);
                double c = U.num(b[2]);
                if (rc <= 0 || c <= 0) continue;
                double factor = c / rc;
                if (prevFactor > 0 && prevRaw > 0) {
                    // 前复权因子以最新价为锚，除息日当天因子会抬升：
                    //   f(t-1) = f(t) × (1 - D / P(t-1))  →  D = P(t-1) × (1 - f(t-1)/f(t))
                    double perShare = prevRaw * (1 - prevFactor / factor);
                    if (Math.abs(1 - prevFactor / factor) > DIV_MIN_CHANGE && perShare > 0.005) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("date", day);
                        row.put("per_share", U.round(perShare, 4));
                        row.put("src", "adj");
                        out.add(row);
                    }
                }
                prevFactor = factor;
                prevRaw = rc;
            }
        }
        return out;
    }

    /**
     * 近 12 个月每股股息合计（税前）。只统计已经除息的，
     * 来源只宣告未除息的未来分红（纳斯达克接口会返回）不算进来。
     */
    public double usTtmPerShare(String code) {
        LocalDate from = LocalDate.now().minusDays(365);
        String fromIso = from.toString();
        String todayIso = LocalDate.now().toString();
        double sum = 0;
        for (Map<String, Object> r : usDividends(code)) {
            String d = String.valueOf(r.get("date"));
            if (d.compareTo(fromIso) >= 0 && d.compareTo(todayIso) <= 0) sum += U.num(r.get("per_share"));
        }
        return sum;
    }

    /**
     * dividendhistory.org（HTML）：纳斯达克接口不覆盖的标的用它兜底，
     * 含 NYSE 个股（KO）与 ARCA 的 ETF（SPY / VOO）。已宣告未除息的行标了 unconfirmed-div，跳过。
     */
    private List<Map<String, Object>> dividendHistory(String sym) {
        try {
            String html = HttpFetch.getUtf8("https://dividendhistory.org/payout/" + sym + "/", null, 6);
            int start = html.indexOf("<table id=\"dividend-table\">");
            if (start < 0) return List.of();
            int end = html.indexOf("</table>", start);
            String table = end > start ? html.substring(start, end)
                    : html.substring(start, Math.min(start + 40000, html.length()));
            List<Map<String, Object>> out = new ArrayList<>();
            Matcher m = TR.matcher(table);
            while (m.find()) {
                if (m.group(1).contains("unconfirmed")) continue;
                List<String> cells = new ArrayList<>();
                Matcher td = TD.matcher(m.group(2));
                while (td.find()) cells.add(td.group(1).replaceAll("<[^>]+>", "").trim());
                if (cells.size() < 3 || !cells.get(0).matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                double amount = U.num(cells.get(2).replace("$", "").replace(",", "").trim());
                if (amount <= 0) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", cells.get(0));
                row.put("per_share", U.round(amount, 4));
                row.put("src", "dh");
                out.add(row);
            }
            if (out.isEmpty()) return List.of();
            out.sort(Comparator.comparing(r -> String.valueOf(r.get("date"))));
            return out;
        } catch (Exception ignore) {
            return List.of();
        }
    }
    private List<Map<String, Object>> plans(String emCode) {
        Cache hit = cache.get(emCode);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.ts < CACHE_MS) return hit.rows;

        List<Map<String, Object>> rows = new ArrayList<>();
        String url = "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_SHAREBONUS_DET"
                + "&columns=SECURITY_CODE,SECURITY_NAME_ABBR,PRETAX_BONUS_RMB,EQUITY_RECORD_DATE,"
                + "EX_DIVIDEND_DATE,REPORT_DATE,ASSIGN_PROGRESS,IMPL_PLAN_PROFILE"
                + "&filter=" + java.net.URLEncoder.encode("(SECURITY_CODE=\"" + emCode + "\")(ASSIGN_PROGRESS=\"实施分配\")",
                StandardCharsets.UTF_8)
                + "&pageNumber=1&pageSize=20&sortColumns=EX_DIVIDEND_DATE&sortTypes=-1";
        try {
            String txt = HttpFetch.getUtf8(url, "https://data.eastmoney.com/");
            JsonNode root = M.readTree(txt);
            JsonNode data = root.path("result").path("data");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    if (rows.size() >= MAX_RECORDS_PER_CODE) break;
                    double per10 = n.path("PRETAX_BONUS_RMB").asDouble(0);
                    if (per10 <= 0) continue;
                    LocalDate ex = parseDate(n.path("EX_DIVIDEND_DATE").asText(""));
                    if (ex == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("exDate", ex);
                    m.put("recordDate", parseDate(n.path("EQUITY_RECORD_DATE").asText("")));
                    m.put("perShare", per10 / 10.0);
                    m.put("report", n.path("REPORT_DATE").asText("").replace(" 00:00:00", ""));
                    String plan = n.path("IMPL_PLAN_PROFILE").asText("");
                    m.put("plan", plan);
                    Matcher mt = AFTER_TAX.matcher(plan);
                    if (mt.find()) {
                        try { m.put("afterTaxPerShare", Double.parseDouble(mt.group(1)) / 10.0); }
                        catch (NumberFormatException ignore) { }
                    }
                    rows.add(m);
                }
            }
            cache.put(emCode, new Cache(now, rows, null));
        } catch (Exception e) {
            cache.put(emCode, new Cache(now, List.of(), e.getMessage()));
            return List.of();
        }
        return rows;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
