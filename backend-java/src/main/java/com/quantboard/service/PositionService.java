package com.quantboard.service;

import com.quantboard.feed.FeedService;
import com.quantboard.util.U;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持仓：按券商分组的静态持仓（数量 + 成本价），展示时合并实时行情算市值与盈亏。
 * OCR 识别券商 App 截图 → 生成候选行 → 前端确认后入库（不静默写入）。
 */
@Service
public class PositionService {

    private final JdbcTemplate jdbc;
    private final FeedService feed;
    private final OcrService ocr;
    private final TradeService trades;
    private final PerformanceService perf;
    private final DividendService dividends;

    /** 名称列出现的词视为"文本"，避免把名称里的数字误当数量。 */
    private static final Pattern P_CODE_A = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern P_CODE_HK = Pattern.compile("(?<!\\d)0?\\d{4,5}(?!\\d)");
    private static final Pattern P_TICKER = Pattern.compile("(?<![A-Za-z])([A-Za-z]{1,6}(?:\\.[A-Za-z]{1,3})?)(?![A-Za-z])");
    private static final Pattern P_NUM = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern P_PCT = Pattern.compile("[+-]?\\d[\\d,]*(?:\\.\\d+)?\\s*%");
    private static final Pattern P_CN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final List<String> SKIP_WORDS = List.of(
            "总资产", "总市值", "总盈亏", "可用", "冻结", "在途", "待入账", "预计", "今日参考",
            "持仓盈亏", "当日盈亏", "证券账户", "人民币", "美股账户", "港股账户", "收益率", "净资产");

    /** 表头字段映射：词 → 列语义。 */
    private static final Map<String, String> HEAD_COLS = Map.ofEntries(
            Map.entry("名称", "name"), Map.entry("证券名称", "name"), Map.entry("标的", "name"),
            Map.entry("代码", "code"), Map.entry("证券代码", "code"),
            Map.entry("数量", "qty"), Map.entry("持仓", "qty"), Map.entry("持有", "qty"),
            Map.entry("持股", "qty"), Map.entry("股数", "qty"), Map.entry("份额", "qty"),
            Map.entry("成本", "cost"), Map.entry("摊薄", "cost"), Map.entry("买入", "cost"),
            Map.entry("现价", "price"), Map.entry("最新", "price"), Map.entry("市价", "price"),
            Map.entry("价格", "price"), Map.entry("净值", "price"),
            Map.entry("市值", "mv"), Map.entry("金额", "mv"),
            Map.entry("盈亏", "pnl"), Map.entry("收益", "pnl"), Map.entry("浮动", "pnl"));

    public PositionService(JdbcTemplate jdbc, FeedService feed, OcrService ocr, TradeService trades,
                           PerformanceService perf, DividendService dividends) {
        this.jdbc = jdbc;
        this.feed = feed;
        this.ocr = ocr;
        this.trades = trades;
        this.perf = perf;
        this.dividends = dividends;
    }

    // ---------------------------------------------------------------- 归一化
    /** 任意来源的代码 → 内部格式（sh600036 / hk09988 / usNVDA）。返回 [code, currency]，非法返回 null。 */
    public static String[] normalizeCode(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace("，", "").replace(" ", "");
        // 用户显式写的 us 前缀（小写/混合大小写，如 usNVDA / UsAAPL）：直接剥掉再归一化。
        // 全大写 USNVDA 无法与「以 US 开头的真实 ticker」区分，交由 upsert 的行情回退处理。
        String rawTrim = raw.trim();
        if (rawTrim.length() > 3 && (rawTrim.startsWith("us") || rawTrim.startsWith("Us"))) {
            String[] r2 = doNormalize(rawTrim.substring(2).toUpperCase().replace(" ", ""));
            if (r2 != null && r2[0].startsWith("us")) return r2;
        }
        String[] r = doNormalize(s);
        if (r == null) {
            // 带交易所前缀：SH600036 / SZ000651 / HK00700 / USNVDA（裸 USNVDA 也是合法
            // ticker，所以前缀规则必须比兜底更精确：SH/SZ 定长 6 位、HK 4-5 位、US ≥2 字母）
            java.util.regex.Matcher m;
            if ((m = java.util.regex.Pattern.compile("^SH(\\d{6})$").matcher(s)).matches())
                r = doNormalize(m.group(1));
            else if ((m = java.util.regex.Pattern.compile("^SZ(\\d{6})$").matcher(s)).matches())
                r = doNormalize(m.group(1));
            else if ((m = java.util.regex.Pattern.compile("^HK(\\d{4,5})$").matcher(s)).matches())
                r = doNormalize(m.group(1));
            else if ((m = java.util.regex.Pattern.compile("^US([A-Z]{2,6}(?:\\.[A-Z]{1,3})?)$").matcher(s)).matches())
                r = doNormalize(m.group(1));
        }
        return r;
    }

    private static String[] doNormalize(String s) {
        if (s.matches("\\d{6}")) {                       // A 股 / ETF
            char c = s.charAt(0);
            if (c == '4' || c == '8') return null;       // 北交所暂不支持
            return new String[]{(c == '0' || c == '1' || c == '2' || c == '3' ? "sz" : "sh") + s,
                    (c == '5' || c == '1') ? "CNY" : "CNY"};
        }
        if (s.matches("\\d{5}")) {                       // 港股 00700 / 09988
            return new String[]{"hk" + s, "HKD"};
        }
        if (s.matches("\\d{4}")) {                       // 港股简写 0700 / 9988
            return new String[]{"hk0" + s, "HKD"};
        }
        if (s.matches("[A-Z]{1,6}(\\.[A-Z]{1,3})?")) {   // 美股 ticker
            return new String[]{"us" + s, "USD"};
        }
        return null;
    }

    // ---------------------------------------------------------------- 列表
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, TradeService.Pos> agg;
        Map<String, Object[]> scores = new HashMap<>();
        Map<String, Double> estDivPerShare = new HashMap<>();   // 同一标的只反推一次
        try {
            agg = trades.aggregate();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT d.code, d.score, d.sig FROM daily_score d WHERE d.trade_date = "
                            + "(SELECT MAX(trade_date) FROM daily_score s2 WHERE s2.code = d.code)")) {
                scores.put(String.valueOf(r.get("code")), new Object[]{r.get("score"), r.get("sig")});
            }
        } catch (Exception e) {
            out.put("ready", false);
            out.put("brokers", List.of());
            out.put("error", e.getMessage());
            return out;
        }

        // 一次性取全部实时行情
        List<String> codes = agg.values().stream().map(p -> p.code).distinct().toList();
        Map<String, Map<String, Object>> quotes = codes.isEmpty() ? Map.of() : feed.fetchQuotes(codes);
        // 美股股息估算：并行拉。串行的话冷启动要 8 只 × 多次 HTTP ≈ 几十秒，持仓页会卡死
        estDivPerShare.putAll(dividends.ttmBatch(codes));

        Map<String, List<Map<String, Object>>> byBroker = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> closedByBroker = new LinkedHashMap<>();
        // currency → [mv, cost, dayPl, realized, divTotal, divTtm]（realized/div 含已清仓标的）
        Map<String, double[]> grand = new LinkedHashMap<>();

        for (TradeService.Pos p : agg.values()) {
            Map<String, Object> q = quotes.get(p.code);
            double price = q != null ? U.num(q.get("price")) : 0;
            double pct = q != null ? U.num(q.get("pct")) : 0;
            String currency = q != null && q.get("currency") != null && !String.valueOf(q.get("currency")).isEmpty()
                    ? String.valueOf(q.get("currency"))
                    : (p.currency == null || p.currency.isEmpty() ? "CNY" : p.currency);

            double mv = p.qty * price;
            double pl = mv - p.costTotal;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("pos_key", p.broker + "|" + p.code);
            it.put("broker", p.broker);
            it.put("code", p.code);
            it.put("name", p.name == null || p.name.isEmpty() ? p.code : p.name);
            it.put("qty", U.round(p.qty, 4));
            it.put("cost", U.round(p.avgCost(), 4));
            it.put("currency", currency);
            it.put("price", price);
            it.put("pct", pct);
            it.put("live", price > 0);
            it.put("mv", U.round(mv, 2));
            it.put("cost_total", U.round(p.costTotal, 2));
            it.put("pl", U.round(pl, 2));
            it.put("pl_pct", p.costTotal > 0 ? U.round(U.pct(mv, p.costTotal), 2) : 0);
            it.put("day_pl", price > 0 && p.qty > 0
                    ? U.round(p.qty * (price - price / (1 + pct / 100.0)), 2) : 0);
            it.put("realized", U.round(p.realized, 2));
            it.put("div_total", U.round(p.divTotal, 2));
            it.put("div_ttm", U.round(p.divTtm, 2));
            it.put("div_rate_cost", p.costTotal > 0 ? U.round(p.divTtm / p.costTotal * 100, 2) : 0);
            it.put("div_rate_mv", mv > 0 ? U.round(p.divTtm / mv * 100, 2) : 0);
            // 美股：没有 DIV 流水就用「复权因子反推」的近 12 个月每股股息估一个参考股息率，
            // 只用于展示（带 ~ 标记），不写进任何现金流/收益统计。
            double ttmPerShare = estDivPerShare.computeIfAbsent(p.code,
                    k -> k.startsWith("us") ? dividends.usTtmPerShare(k) : 0);
            double divEst = ttmPerShare > 0 && p.qty > 0 ? U.round(ttmPerShare * p.qty, 2) : 0;
            it.put("div_ttm_est", divEst);
            it.put("div_rate_mv_est", mv > 0 && divEst > 0 ? U.round(divEst / mv * 100, 2) : 0);
            it.put("div_est", divEst > 0 && p.divTtm <= 0);
            it.put("trades", p.trades);
            it.put("closed", p.closed());
            Object[] sc = scores.get(p.code);
            it.put("score", sc != null && sc[0] != null ? U.num(sc[0]) : null);
            it.put("signal", sc != null ? sc[1] : null);

            double[] g = grand.computeIfAbsent(currency, k -> new double[6]);
            g[3] += p.realized;
            g[4] += p.divTotal;
            g[5] += p.divTtm;
            if (p.closed()) {
                closedByBroker.computeIfAbsent(p.broker, k -> new ArrayList<>()).add(it);
            } else {
                byBroker.computeIfAbsent(p.broker, k -> new ArrayList<>()).add(it);
                g[0] += mv;
                g[1] += p.costTotal;
                g[2] += U.num(it.get("day_pl"));
            }
        }

        List<Map<String, Object>> brokers = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.addAll(byBroker.keySet());
        names.addAll(closedByBroker.keySet());
        for (String brokerName : names) {
            List<Map<String, Object>> items = byBroker.getOrDefault(brokerName, List.of());
            List<Map<String, Object>> closedItems = closedByBroker.getOrDefault(brokerName, List.of());
            Map<String, double[]> tot = new LinkedHashMap<>();
            for (Map<String, Object> it : items) {
                String cur = String.valueOf(it.get("currency"));
                double[] t = tot.computeIfAbsent(cur, k -> new double[6]);
                t[0] += U.num(it.get("mv"));
                t[1] += U.num(it.get("cost_total"));
                t[2] += U.num(it.get("day_pl"));
                t[3] += U.num(it.get("realized"));
                t[4] += U.num(it.get("div_total"));
                t[5] += U.num(it.get("div_ttm"));
            }
            for (Map<String, Object> it : closedItems) {   // 清仓标的只贡献已实现与股息
                String cur = String.valueOf(it.get("currency"));
                double[] t = tot.computeIfAbsent(cur, k -> new double[6]);
                t[3] += U.num(it.get("realized"));
                t[4] += U.num(it.get("div_total"));
                t[5] += U.num(it.get("div_ttm"));
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("broker", brokerName);
            b.put("items", items);
            b.put("closed", closedItems);
            b.put("totals", totalsList(tot));
            double bcny = 0;
            for (var e : tot.entrySet()) bcny += e.getValue()[0] * fxOf(e.getKey());
            b.put("mv_cny", U.round(bcny, 2));   // 折人民币市值，供前端直接展示，避免前端二次折算
            brokers.add(b);
        }

        out.put("ready", true);
        out.put("brokers", brokers);
        out.put("grand", totalsList(grand));

        // 约合人民币（静态近似汇率，仅用于跨币种量级参考）：市值/成本/今日/已实现/股息统一折算
        double mvCny = 0, costCny = 0, dayCny = 0, realCny = 0, divCny = 0, divTtmCny = 0;
        for (var e : grand.entrySet()) {
            double fx = fxOf(e.getKey());
            mvCny += e.getValue()[0] * fx;
            costCny += e.getValue()[1] * fx;
            dayCny += e.getValue()[2] * fx;
            realCny += e.getValue()[3] * fx;
            divCny += e.getValue()[4] * fx;
            divTtmCny += e.getValue()[5] * fx;
        }
        Map<String, Object> cnyDetail = new LinkedHashMap<>();
        cnyDetail.put("mv", U.round(mvCny, 0));
        cnyDetail.put("cost", U.round(costCny, 2));
        cnyDetail.put("pl", U.round(mvCny - costCny, 2));
        cnyDetail.put("pl_pct", costCny > 0 ? U.round((mvCny - costCny) / costCny * 100, 2) : 0);
        cnyDetail.put("day_pl", U.round(dayCny, 2));
        cnyDetail.put("realized", U.round(realCny, 2));
        cnyDetail.put("div_total", U.round(divCny, 2));
        cnyDetail.put("div_ttm", U.round(divTtmCny, 2));
        cnyDetail.put("total", U.round(mvCny - costCny + realCny + divCny, 2));
        out.put("grand_cny_detail", cnyDetail);
        out.put("grand_cny", U.round(mvCny, 0));
        out.put("fx", perf.fxMap());
        for (Map<String, Object> b : brokers) {   // 占比也由后端算，前端只渲染
            b.put("share", mvCny > 0 ? U.round(U.num(b.get("mv_cny")) / mvCny * 100, 2) : 0);
        }
        return out;
    }

    /** 汇率口径统一由收益引擎提供（全站唯一出处）。 */
    private double fxOf(String currency) {
        return perf.fxOf(currency);
    }

    private List<Map<String, Object>> totalsList(Map<String, double[]> tot) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : tot.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", e.getKey());
            m.put("mv", U.round(e.getValue()[0], 2));
            m.put("cost", U.round(e.getValue()[1], 2));
            m.put("pl", U.round(e.getValue()[0] - e.getValue()[1], 2));
            m.put("pl_pct", e.getValue()[1] > 0 ? U.round(U.pct(e.getValue()[0], e.getValue()[1]), 2) : 0);
            m.put("day_pl", U.round(e.getValue()[2], 2));
            m.put("realized", U.round(e.getValue()[3], 2));
            m.put("div_total", U.round(e.getValue()[4], 2));
            m.put("div_ttm", U.round(e.getValue()[5], 2));
            m.put("fx", fxOf(e.getKey()));                       // 前端直接乘，不再自己判断币种
            m.put("mv_cny", U.round(e.getValue()[0] * fxOf(e.getKey()), 2));
            list.add(m);
        }
        return list;
    }

    // ---------------------------------------------------------------- 增删
    /** 新增 / 更新（broker+code 唯一）。行情能取到时用行情的名称与币种。 */
    public Map<String, Object> upsert(String broker, String codeRaw, String name, double qty, double cost) {
        return upsert(broker, codeRaw, name, qty, cost, null, "INIT");
    }

    /**
     * 手动录入一笔交易。
     * 首次录入（该 broker+code 无流水）：按期初建仓（INIT）入库，日期即建仓日；
     * 已有持仓时：side=BUY 记一笔买入（再买入 / 加仓），side=SELL 记一笔卖出（校验持股足够）。
     * 成本价字段双向复用：买入即买入价（计入成本），卖出即卖出价（算已实现盈亏）。
     */
    public Map<String, Object> upsert(String broker, String codeRaw, String name, double qty, double cost,
                                      String dateStr, String side) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] norm = normalizeCode(codeRaw);
        if (norm == null) {
            out.put("ok", false);
            out.put("error", "无法识别代码：" + codeRaw + "（支持 A 股 6 位 / 港股 5 位 / 美股 ticker）");
            return out;
        }
        String code = norm[0];
        String currency = norm[1];
        String finalName = name == null ? "" : name.trim();
        Map<String, Map<String, Object>> quotes = feed.fetchQuotes(List.of(code));
        Map<String, Object> q = quotes.get(code);
        // US 前缀歧义回退：裸解析成 usUSxxxx 但行情取不到时，尝试剥掉误吞的 US 前缀
        // （如 USNVDA → NVDA）。真以 US 开头的 ticker（USO/USB/USFD）行情能取到，不受影响。
        if (q == null && code.matches("usUS[A-Z]{2,5}")) {
            String alt = "us" + code.substring(4);
            Map<String, Map<String, Object>> q2 = feed.fetchQuotes(List.of(alt));
            Map<String, Object> qa = q2.get(alt);
            if (qa != null) {
                code = alt;
                q = qa;
            }
        }
        if (q != null) {
            currency = (String) q.get("currency");
            String qn = (String) q.get("name");
            if (qn != null && !qn.isEmpty()) finalName = qn;
        } else if (finalName.isEmpty()) {
            finalName = code;
        }
        if (broker == null || broker.isBlank()) broker = "默认账户";
        String s = side == null || side.isBlank() ? "INIT" : side.trim().toUpperCase();
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trade WHERE broker=? AND code=?", Integer.class, broker.trim(), code);
            boolean first = exists == null || exists == 0;
            java.time.LocalDate d;
            try {
                d = dateStr == null || dateStr.isBlank() ? java.time.LocalDate.now()
                        : java.time.LocalDate.parse(dateStr.substring(0, 10));
            } catch (Exception ignore) {
                d = java.time.LocalDate.now();
            }
            if (first) {
                if ("SELL".equals(s)) {
                    out.put("ok", false);
                    out.put("error", "「" + broker.trim() + "」还没有 " + finalName + " 的持仓，卖出请先记买入");
                    return out;
                }
                double amt = Math.round(qty * cost * 100) / 100.0;
                jdbc.update("INSERT INTO trade (broker, code, name, side, trade_date, qty, price, amount, fee, currency, note) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        broker.trim(), code, finalName, "INIT", d, qty, cost, amt, 0, currency,
                        "期初建仓（手动录入 / 截图识别）");
            } else if ("BUY".equals(s) || "SELL".equals(s)) {
                if ("SELL".equals(s)) {
                    TradeService.Pos p = trades.aggregate().get(broker.trim() + "|" + code);
                    double held = p == null ? 0 : p.qty;
                    if (qty > held + 1e-9) {
                        out.put("ok", false);
                        out.put("error", "卖出数量超过当前持仓（现有 " + U.round(held, 4) + "）");
                        out.put("code", code);
                        return out;
                    }
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("side", s);
                body.put("broker", broker.trim());
                body.put("code", code);
                body.put("name", finalName);
                body.put("qty", qty);
                body.put("price", cost);
                body.put("fee", 0);
                body.put("trade_date", d.toString());
                body.put("note", "手动录入");
                return trades.add(body);
            } else {
                out.put("ok", false);
                out.put("error", "side 必须是 BUY / SELL");
                return out;
            }
            out.put("ok", true);
            out.put("code", code);
            out.put("name", finalName);
            out.put("currency", currency);
            out.put("quote_ok", q != null);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 删除某账户下某标的的全部流水（前端 ✕）。 */
    public boolean removePosition(String broker, String code) {
        return trades.removePosition(broker, code) > 0;
    }

    // ---------------------------------------------------------------- OCR 解析
    /** 截图 → 候选持仓行（不入库，前端确认后再提交）。 */
    public Map<String, Object> parseScreenshot(byte[] bytes, String ext) {
        List<String> lines = ocr.recognize(bytes, ext);
        List<Map<String, Object> > items = parseLines(lines);
        // 拉行情补全名称 / 现价 / 币种，并给置信度
        List<String> codes = items.stream().map(i -> (String) i.get("code")).distinct().toList();
        Map<String, Map<String, Object>> quotes = codes.isEmpty() ? Map.of() : feed.fetchQuotes(codes);
        for (Map<String, Object> it : items) {
            Map<String, Object> q = quotes.get(it.get("code"));
            if (q == null) {
                // 老式股票类别代码（BRK B / BRK.B 被 OCR 读成 BRKB）：补点后重查行情，取到才改写代码
                String alt = shareClassCode((String) it.get("code"));
                if (alt != null) {
                    Map<String, Object> qa = feed.fetchQuotes(List.of(alt)).get(alt);
                    if (qa != null) { it.put("code", alt); q = qa; }
                }
            }
            if (q != null) {
                it.put("quote_name", q.get("name"));
                it.put("price", U.num(q.get("price")));
                it.put("currency", q.get("currency"));
                String n = String.valueOf(it.get("name"));
                String qn = String.valueOf(q.get("name"));
                it.put("confidence", n.isEmpty() || qn.contains(n) || n.contains(qn) ? "high" : "check");
            } else {
                it.put("confidence", "noquote");
                it.put("price", 0);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !items.isEmpty());
        out.put("items", items);
        out.put("lines", lines.size());
        return out;
    }

    /** 解析 OCR 行：① 严格表头列对齐（表格布局）→ ② 卡片分块 label-value（券商 App 卡片布局）→ ③ 逐行启发式。 */
    static List<Map<String, Object>> parseLines(List<String> lines) {
        List<List<String>> tabbed = new ArrayList<>();
        for (String l : lines) {
            if (l == null || l.isBlank()) continue;
            tabbed.add(new ArrayList<>(Arrays.asList(l.split("\t", -1))));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        // 1) 找表头行：命中 ≥2 个列关键词，且不含任何数字（真实的表头是纯标签行，
        //    避免把「市价 52.270 持仓数 300」这类卡片数据行误判成表头）
        int headIdx = -1;
        Map<Integer, String> colField = null;
        for (int i = 0; i < tabbed.size(); i++) {
            Map<Integer, String> m = mapHeader(tabbed.get(i));
            if (m.size() < 2 || hasNumericToken(tabbed.get(i))) continue;
            headIdx = i; colField = m; break;
        }
        if (headIdx >= 0) {
            if (isStackedHeader(tabbed.get(headIdx))) {
                out.addAll(parseStackedBody(tabbed, headIdx));
            } else {
                for (int i = headIdx + 1; i < tabbed.size(); i++) {
                    Map<String, Object> row = parseByColumns(tabbed.get(i), colField);
                    if (row != null) out.add(row);
                }
            }
        }
        // 2) IBKR（盈透）移动端行式布局：`COST NASDAQ.NMS 1005.00 5.0313 -0.81 346.13`
        if (out.isEmpty()) {
            for (List<String> words : tabbed) {
                Map<String, Object> row = parseIbkrLine(words);
                if (row != null) out.add(row);
            }
        }
        // 3) 卡片式布局：名称+代码行开块，块内按 label→value 配对
        if (out.isEmpty()) out = parseBlocks(tabbed);
        // 3) 无表头：整行启发式（含 tab 行中未命中的）
        if (out.isEmpty()) {
            for (List<String> words : tabbed) {
                Map<String, Object> row = parseHeuristic(words);
                if (row != null) out.add(row);
            }
        }
        // 去重（同代码保留第一行）
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> r : out) {
            seen.putIfAbsent((String) r.get("code"), r);
        }
        return new ArrayList<>(seen.values());
    }

    // ------------------------------------------------------------- 叠排布局解析
    /**
     * 叠排表头：同一表头词内含两组语义（如「市值/数量」「现价/成本」），
     * 富途 / 长桥等 App 的持仓列表常见此布局。
     */
    private static boolean isStackedHeader(List<String> words) {
        for (String w : words) {
            String t = w == null ? "" : w.replaceAll("[\\s:：|/／]", "");
            boolean hasQty = t.contains("数量") || t.contains("持仓") || t.contains("股数") || t.contains("份额");
            boolean hasMv = t.contains("市值") || t.contains("金额");
            boolean hasCost = t.contains("成本");
            boolean hasPrice = t.contains("现价") || t.contains("市价") || t.contains("最新") || t.contains("价格");
            if ((hasMv && hasQty) || (hasCost && hasPrice)) return true;
        }
        return false;
    }

    /**
     * 叠排布局解析：每只标的的 市值/数量/成本/现价 四个数可能被 OCR 合并进一行，
     * 也可能拆成「名称+市值+现价 / 代码+数量+成本」两行，且列内上下值顺序不稳定。
     * 策略：逐行提取 名称/代码/纯数字；名称行(2 个数)与代码行(2 个数)跨行配对；
     * 凑齐 4 个数后按 市值≈数量×现价 枚举排列消歧，杜绝列错位。
     */
    static List<Map<String, Object>> parseStackedBody(List<List<String>> tabbed, int headIdx) {
        List<Map<String, Object>> out = new ArrayList<>();
        String pName = null;
        List<Double> pNums = null;

        for (int i = headIdx + 1; i < tabbed.size(); i++) {
            String code = null;
            StringBuilder name = new StringBuilder();
            List<Double> nums = new ArrayList<>();
            for (String w : tabbed.get(i)) {
                String raw = w == null ? "" : w.trim();
                if (raw.isEmpty() || raw.contains("%") || raw.matches("^[+\\-].*")) continue;
                String t = raw.replaceAll("[\\s,，]", "");
                t = t.replaceAll("[.,]+$", "");                          // 「482.00.」尾点
                if (t.isEmpty()) continue;
                if (t.matches("\\d[\\d.]*")) {                           // 纯数字（千分位逗号可能被误识成点）
                    int last = t.lastIndexOf('.');
                    if (last >= 0 && last != t.indexOf('.'))
                        t = t.substring(0, last).replace(".", "") + t.substring(last);
                    try { nums.add(Double.parseDouble(t)); } catch (NumberFormatException ignore) {}
                    continue;
                }
                if (t.matches("T[+-]?\\d*")) continue;                   // T+1 / T-1 标记
                if (!t.matches("^[A-Za-z].*") || t.matches("\\d+[A-Za-z].*")) {
                    // 首字符非字母（中文名称）或「数字+字母」混杂 token 不当代码
                    if (P_CN.matcher(t).find()) {
                        if (name.length() > 0) name.append(' ');
                        name.append(t);
                    }
                    continue;
                }
                if (code == null) {
                    String[] n = tryCode(t);
                    if (n != null) { code = n[0]; continue; }
                }
            }
            if (code == null && name.length() == 0) continue;            // 页眉页脚等噪音行

            // 与上一行（只有名称 + 2 个数、无代码）跨行配对
            if (pName != null) {
                if (name.length() == 0) name.append(pName);
                if (pNums != null && nums.size() == 2) {
                    List<Double> merged = new ArrayList<>(pNums);
                    merged.addAll(nums);
                    nums = merged;
                } else if (pNums != null && nums.isEmpty()) {
                    nums = pNums;
                }
                pName = null; pNums = null;
            }

            if (code != null || nums.size() >= 2) {
                Map<String, Object> row = buildStackedRow(name.toString(), code, nums);
                if (row != null) out.add(row);
            } else if (nums.size() == 2) {
                pName = name.toString();                                 // 可能是拆分行的上半段，等代码行
                pNums = nums;
            }
        }
        return out;
    }

    /** 叠排行组装：4 个数按 {市值,数量,成本,现价} 全排列取 |数量×现价−市值| 最小者。 */
    private static Map<String, Object> buildStackedRow(String name, String code, List<Double> nums) {
        double qty = 0, cost = 0;
        if (nums.size() >= 4) {
            double bestErr = Double.MAX_VALUE;
            int[][] perms = {{0,1,2,3},{0,1,3,2},{0,2,1,3},{0,2,3,1},{0,3,1,2},{0,3,2,1},
                             {1,0,2,3},{1,0,3,2},{1,2,0,3},{1,2,3,0},{1,3,0,2},{1,3,2,0},
                             {2,0,1,3},{2,0,3,1},{2,1,0,3},{2,1,3,0},{2,3,0,1},{2,3,1,0},
                             {3,0,1,2},{3,0,2,1},{3,1,0,2},{3,1,2,0},{3,2,0,1},{3,2,1,0}};
            for (int[] p : perms) {
                double mv = nums.get(p[0]), q = nums.get(p[1]), c = nums.get(p[2]), pr = nums.get(p[3]);
                if (q <= 0 || pr <= 0 || mv <= 0) continue;
                double err = Math.abs(q * pr - mv) / mv;
                if (err < bestErr) { bestErr = err; qty = q; cost = c; }
            }
        } else if (nums.size() == 2) {
            qty = nums.get(0); cost = nums.get(1);
        } else if (nums.size() == 1) {
            qty = nums.get(0);
        }
        return buildRow(name, code, qty > 0 ? qty : null, cost > 0 ? cost : null);
    }

    private static boolean hasNumericToken(List<String> words) {
        for (String w : words) {
            String t = w.replaceAll("[+%,￥$¥\\s]", "");
            if (!t.isEmpty() && t.matches("-?\\d[\\d,]*(?:\\.\\d+)?")) return true;
        }
        return false;
    }

    /**
     * 老式股票类别代码：BRK B / BRK.B 常被 OCR 读成 BRKB，行情取不到。
     * 在末位字母前补点（usBRKB → usBRK.B），由调用方回查行情确认后才采用，避免误改真实代码。
     */
    static String shareClassCode(String code) {
        if (code == null || !code.startsWith("us")) return null;
        String t = code.substring(2);
        if (!t.matches("[A-Z]{3,6}")) return null;
        return "us" + t.substring(0, t.length() - 1) + "." + t.substring(t.length() - 1);
    }

    // ------------------------------------------------------------- IBKR 行式解析
    /** 交易所后缀（IBKR 在代码后标注上市地），按长度降序尝试剥离。 */
    private static final String[] EXCH_WORDS = {
            "NASDAQ.NMS", "NASDAQ.GS", "NASDAQ", "NYSEARCA", "NYSE", "ARCA", "AMEX",
            "SEHK", "HKEX", "LSE", "TSE", "OTC"};

    private static boolean isExch(String t) {
        String s = t.replace(".", "");   // cleanToken 会剥掉点：NASDAQ.NMS → NASDAQNMS
        for (String e : EXCH_WORDS) if (e.replace(".", "").equals(s)) return true;
        return false;
    }

    /**
     * IBKR 移动端持仓行：列序固定为 代码[+交易所] · 最后价格 · 持仓 · 当日盈亏 · 未实现盈亏。
     * 例：COST NASDAQ.NMS 1005.00 5.0313 -0.81 346.13 / BRK B NYSE 548.85 12 -8.76 933.65
     *     KONYSE 92.50 102.4536 ...（OCR 粘连）/ 700 SEHK 40.20 1000 ...（港股）。
     * 只取前两个数字 = 价格、数量（当日/未实现盈亏忽略）；市值、净值、现金等汇总行没有
     * 交易所后缀，自然被跳过。返回候选行，不匹配返回 null。
     */
    static Map<String, Object> parseIbkrLine(List<String> words) {
        if (words == null || words.isEmpty()) return null;
        int exchIdx = -1;
        String codeOverride = null;
        for (int i = 0; i < Math.min(words.size(), 4); i++) {
            String raw = cleanToken(words.get(i)).toUpperCase();
            if (raw.isEmpty()) continue;
            if (isExch(raw)) { exchIdx = i; break; }
            // OCR 粘连：KONYSE / COSTNASDAQNMS → 剥出交易所后缀
            for (String suf : EXCH_WORDS) {
                String s2 = suf.replace(".", "");
                if (raw.length() > s2.length() && raw.endsWith(s2)) {
                    String prefix = raw.substring(0, raw.length() - s2.length());
                    if (prefix.matches("[A-Z]{1,6}") || prefix.matches("\\d{4,5}")) {
                        codeOverride = prefix;
                        exchIdx = i;
                    }
                    break;
                }
            }
            if (exchIdx >= 0) break;
        }
        if (exchIdx < 0 || (exchIdx == 0 && codeOverride == null)) return null;

        String code = codeOverride;
        if (code == null) {
            String raw0 = String.valueOf(words.get(0)).trim().toUpperCase();
            if (raw0.matches("[A-Z]{1,6}[.\\s][A-Z]")) {
                code = raw0.replaceFirst("[.\\s]", ".");      // "BRK B" / "BRK.B" → BRK.B（与 BRKB 是两个标的，不能丢点）
            } else if (exchIdx == 2) {
                String t1 = cleanToken(words.get(1)).toUpperCase();
                if (t1.matches("[A-Z]")) code = cleanToken(words.get(0)).toUpperCase() + "." + t1;
            }
            if (code == null) code = cleanToken(words.get(0)).toUpperCase();
        }
        String[] norm = normalizeCode(code);
        if (norm == null) return null;

        List<Double> nums = new ArrayList<>();
        for (int i = exchIdx + 1; i < words.size(); i++) {
            Matcher m = P_NUM.matcher(words.get(i).replace(",", ""));
            while (m.find()) {
                double v = parseNum(m.group());
                if (!Double.isNaN(v)) nums.add(v);
            }
        }
        if (nums.size() < 2) return null;
        double price = nums.get(0), qty = nums.get(1);
        if (qty <= 0 || price <= 0) return null;
        return buildRow("", norm[0], qty, price);
    }

    // ------------------------------------------------------------- 卡片分块解析
    /** 字段标签，按长度降序匹配（长词优先，避免「成本价」被「成本」截胡）。 */
    private static final Object[][] LABELS = {
            {"摊薄成本价", "cost"}, {"摊薄成本", "cost"}, {"买入均价", "cost"}, {"买入价", "cost"},
            {"持仓数量", "qty"}, {"可用数量", "qty"}, {"持仓可用", "qty"}, {"成本价", "cost"},
            {"持仓数", "qty"}, {"持仓量", "qty"}, {"可用数", "qty"}, {"持股数", "qty"},
            {"市价", "price"}, {"现价", "price"}, {"最新价", "price"}, {"当前价", "price"},
            {"数量", "qty"}, {"股数", "qty"}, {"份额", "qty"}, {"可用", "qty"}, {"成本", "cost"},
            {"持仓", "qty"}, {"持有", "qty"}, {"最新", "price"}, {"价格", "price"}, {"净值", "price"},
            {"市值", "mv"}, {"金额", "mv"}, {"盈亏", "pnl"}, {"浮盈", "pnl"}, {"浮亏", "pnl"}, {"收益", "pnl"}};
    private static final Pattern P_VALUE = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    /**
     * 卡片式截图：形如「中国平安 601318」的行开启一个块，块内
     * 「市价 52.270 持仓数 300」「成本 48.837 可用数 300」「买入均价 49.817 市值 15681.00」
     * 按 label 配对取值。返回候选行列表。
     */
    static List<Map<String, Object>> parseBlocks(List<List<String>> tabbed) {
        List<Map<String, Object>> out = new ArrayList<>();
        String code = null, name = "", pendingName = "";
        List<List<String>> kv = new ArrayList<>();

        for (List<String> words : tabbed) {
            String joined = String.join("", words);
            if (joined.isBlank()) continue;

            boolean hasLabel = false;
            for (String w : words) if (matchLabel(w) != null) { hasLabel = true; break; }

            if (!hasLabel) {
                String lineCode = null, lineName = null;
                for (String w : words) {
                    String raw = w.trim();
                    String t = cleanToken(w);
                    if (t.isEmpty()) continue;
                    // 涨跌幅/盈亏金额（带 % 或 +/- 前缀）绝不当代码，防止「+1029.85」剥成 102985
                    if (raw.contains("%") || raw.matches("^[+\\-].*")) continue;
                    String[] n = tryCode(t);
                    if (n != null && lineCode == null) { lineCode = n[0]; continue; }
                    if (P_CN.matcher(t).find()) lineName = lineName == null ? t : lineName + " " + t;
                }
                if (lineCode != null) {
                    // 新块开始：先结算上一个块
                    Map<String, Object> row = buildBlockRow(code, name, kv);
                    if (row != null) out.add(row);
                    code = lineCode;
                    name = lineName == null || lineName.isEmpty() ? pendingName : lineName;
                    kv = new ArrayList<>();
                    pendingName = "";
                    continue;
                }
                // 无代码无标签的纯中文行：可能是下一块的名称（代码在下一行）
                if (lineName != null && !hasNumericToken(words)) { pendingName = lineName; continue; }
            }
            if (code != null) kv.add(words);
        }
        Map<String, Object> row = buildBlockRow(code, name, kv);
        if (row != null) out.add(row);
        return out;
    }

    private static String cleanToken(String w) {
        return w == null ? "" : w.replaceAll("[\\s,，、()（）:：|.|]", "").trim();
    }

    /** token → 字段名；含盈/亏/率的 token 只能匹配 pnl，防止「持仓盈亏」误配 qty。 */
    private static String matchLabel(String token) {
        if (token == null) return null;
        String t = token.replaceAll("[\\s:：]", "");
        if (t.isEmpty()) return null;
        boolean pnlOnly = t.chars().anyMatch(c -> "盈亏率比涨跌%".indexOf(c) >= 0);
        for (Object[] e : LABELS) {
            String label = (String) e[0];
            if (!t.contains(label)) continue;
            if (pnlOnly && !"pnl".equals(e[1])) continue;
            return (String) e[1];
        }
        return null;
    }

    /** 结算一个块：块内每个 label 词向后找最近的纯数字词配对，组装候选行。 */
    private static Map<String, Object> buildBlockRow(String code, String name, List<List<String>> kv) {
        if (code == null || name == null || name.isBlank()) return null;
        Map<String, Double> f = new LinkedHashMap<>();
        for (List<String> words : kv) {
            for (int i = 0; i < words.size(); i++) {
                String field = matchLabel(words.get(i));
                if (field == null || f.containsKey(field)) continue;
                // 同 token 内 label 后缀直接带数字（如「成本48.837」）
                String rest = words.get(i).replaceFirst(".*?(摊薄成本价|摊薄成本|买入均价|买入价|成本价|持仓数量|可用数量|持仓可用|持仓数|持仓量|可用数|持股数|市价|现价|最新价|当前价|数量|股数|份额|可用|成本|持仓|持有|最新|价格|净值|市值|金额|盈亏|浮盈|浮亏|收益)", "");
                Double v = parseCleanNum(rest);
                // 或取后续最近的数字 token（跳过百分比），遇到下一个 label 词即停
                for (int j = i + 1; v == null && j < words.size(); j++) {
                    String raw = words.get(j).trim();
                    if (matchLabel(raw) != null) break;
                    if (raw.isEmpty()) continue;
                    if (raw.endsWith("%")) break;
                    String t = raw.replaceAll("[+%,￥$¥\\s]", "");
                    if (t.matches("-?\\d[\\d,]*(?:\\.\\d+)?")) v = parseNum(t);
                    else break;
                }
                if (v != null) f.put(field, v);
            }
        }
        double qty  = f.getOrDefault("qty", 0.0);
        double cost = f.getOrDefault("cost", 0.0);
        double price= f.getOrDefault("price", 0.0);
        double mv   = f.getOrDefault("mv", 0.0);
        double pnl  = f.getOrDefault("pnl", 0.0);
        if (qty <= 0 && mv > 0 && price > 0) qty = mv / price;                        // 只有市值+现价 → 反推数量
        if (cost <= 0 && price > 0 && qty > 0 && pnl != 0) cost = price - pnl / qty;  // 用盈亏反推成本
        if (cost <= 0 && mv > 0 && qty > 0 && price <= 0) cost = mv / qty;
        return buildRow(name, code, qty, cost);
    }

    private static Double parseCleanNum(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[+%,￥$¥\\s]", "");
        return t.matches("\\d[\\d,]*(?:\\.\\d+)?") ? parseNum(t) : null;
    }

    private static Map<Integer, String> mapHeader(List<String> words) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).replaceAll("[\\s:：|]", "");
            for (var e : HEAD_COLS.entrySet()) {
                if (w.contains(e.getKey())) { m.put(i, e.getValue()); break; }
            }
        }
        return m;
    }

    /** 按最近表头列归位每个词。 */
    private static Map<String, Object> parseByColumns(List<String> words, Map<Integer, String> colField) {
        Map<String, StringBuilder> texts = new HashMap<>();
        Map<String, Double> nums = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).trim();
            if (w.isEmpty()) continue;
            String field = nearestField(i, colField);
            if (field == null) continue;
            if (field.equals("name") || field.equals("code")) {
                texts.computeIfAbsent(field, k -> new StringBuilder());
                if (texts.get(field).length() > 0) texts.get(field).append(' ');
                texts.get(field).append(w);
            } else {
                Matcher mm = P_NUM.matcher(w.replace("%", ""));
                while (mm.find()) {
                    double v = parseNum(mm.group());
                    if (!Double.isNaN(v)) nums.put(field, v);   // 同列多数字取最后一个
                }
            }
        }
        return buildRow(str(texts.get("name")), str(texts.get("code")), nums.get("qty"), nums.get("cost"));
    }

    private static String str(StringBuilder sb) { return sb == null ? null : sb.toString(); }

    private static String nearestField(int idx, Map<Integer, String> colField) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (var e : colField.entrySet()) {
            int d = Math.abs(e.getKey() - idx);
            if (d < bestDist) { bestDist = d; best = e.getValue(); }
        }
        return bestDist <= 1 ? best : null;
    }

    /** 无表头启发式：识别行内代码 + 名称 + 数字序列（数量, 成本, …）。 */
    private static Map<String, Object> parseHeuristic(List<String> words) {
        String joined = String.join(" ", words);
        for (String k : SKIP_WORDS) if (joined.contains(k)) return null;

        String code = null, name = "";
        List<String> rest = new ArrayList<>();
        for (String w : words) {
            String t = w.trim();
            if (t.isEmpty()) continue;
            if (code == null) {
                String[] n = tryCode(t);
                if (n != null) { code = n[0]; continue; }
            }
            rest.add(t);
        }
        if (code == null) return null;

        List<Double> nums = new ArrayList<>();
        StringBuilder nameBuf = new StringBuilder();
        for (String t : rest) {
            if (P_PCT.matcher(t).matches()) continue;                 // 跳过百分比
            Matcher mm = P_NUM.matcher(t);
            String cleaned = mm.replaceAll(" ").trim();
            if (!cleaned.isEmpty() && P_CN.matcher(cleaned).find()) {
                if (nameBuf.length() > 0) nameBuf.append(' ');
                nameBuf.append(cleaned);
            }
            mm.reset();
            while (mm.find()) {
                double v = parseNum(mm.group());
                if (!Double.isNaN(v)) nums.add(v);
            }
        }
        name = nameBuf.toString().trim();
        double qty = nums.size() > 0 ? nums.get(0) : 0;
        double cost = nums.size() > 1 ? nums.get(1) : 0;
        return buildRow(name, code, qty, cost);
    }

    private static String[] tryCode(String t) {
        String s = t.replaceAll("[\\s,，]", "");
        String[] n = normalizeCode(s);
        if (n != null && (s.matches("\\d{6}") || s.matches("0?\\d{4,5}")
                || s.matches("[A-Za-z]{1,6}(\\.[A-Za-z]{1,3})?"))) return n;
        // 从更长 token 中提取（如 "00700.HK" / "SH600036"）
        Matcher m6 = P_CODE_A.matcher(s);
        if (m6.find()) { String[] r = normalizeCode(m6.group()); if (r != null) return r; }
        Matcher mh = P_CODE_HK.matcher(s);
        if (mh.find()) { String[] r = normalizeCode(mh.group()); if (r != null && mh.group().startsWith("0")) return r; }
        Matcher mt = P_TICKER.matcher(s);
        if (mt.find() && s.length() <= 8) { String[] r = normalizeCode(mt.group(1)); if (r != null) return r; }
        return null;
    }

    private static double parseNum(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", ""));
            if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1e12) return Double.NaN;
            return v;
        } catch (Exception e) { return Double.NaN; }
    }

    private static Map<String, Object> buildRow(String name, String code, Double qty, Double cost) {
        if (code == null || qty == null || qty <= 0) return null;
        String c = code.trim();
        if (!c.matches("^(sh|sz|hk|us).*")) {           // OCR 表格列拿到的是原始代码，需归一化
            String[] n = normalizeCode(c);
            if (n == null) return null;
            c = n[0];
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", c);
        r.put("name", name == null ? "" : name.trim());
        r.put("qty", qty);
        r.put("cost", cost != null && cost > 0 ? cost : 0);
        return r;
    }
}
