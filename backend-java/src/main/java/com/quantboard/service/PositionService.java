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
    private final double fxHkd;
    private final double fxUsd;

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

    public PositionService(JdbcTemplate jdbc, FeedService feed, OcrService ocr,
                           @Value("${quantboard.fx-hkd:0.92}") double fxHkd,
                           @Value("${quantboard.fx-usd:7.15}") double fxUsd) {
        this.jdbc = jdbc;
        this.feed = feed;
        this.ocr = ocr;
        this.fxHkd = fxHkd;
        this.fxUsd = fxUsd;
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
    public boolean dbReady() {
        try { jdbc.execute("SELECT 1 FROM holding LIMIT 1"); return true; }
        catch (Exception e) { return false; }
    }

    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT h.id, h.broker, h.code, h.name, h.qty, h.cost, h.currency, h.note, "
                            + "h.updated_at, d.score, d.sig "
                            + "FROM holding h LEFT JOIN daily_score d ON d.code = h.code "
                            + "AND d.trade_date = (SELECT MAX(trade_date) FROM daily_score s2 WHERE s2.code = h.code) "
                            + "ORDER BY h.broker, h.created_at, h.id");
        } catch (Exception e) {
            out.put("ready", false);
            out.put("brokers", List.of());
            return out;
        }

        // 一次性取全部实时行情
        List<String> codes = rows.stream().map(r -> (String) r.get("code")).distinct().toList();
        Map<String, Map<String, Object>> quotes = codes.isEmpty()
                ? Map.of() : feed.fetchQuotes(codes);

        Map<String, List<Map<String, Object>>> byBroker = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("code");
            Map<String, Object> q = quotes.get(code);
            double qty = U.num(r.get("qty"));
            double cost = U.num(r.get("cost"));
            double price = q != null ? U.num(q.get("price")) : 0;
            double pct = q != null ? U.num(q.get("pct")) : 0;
            String currency = q != null && q.get("currency") != null && !String.valueOf(q.get("currency")).isEmpty()
                    ? (String) q.get("currency") : String.valueOf(r.get("currency"));

            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", r.get("id"));
            it.put("broker", r.get("broker"));
            it.put("code", code);
            it.put("name", q != null && q.get("name") != null && !String.valueOf(q.get("name")).isEmpty()
                    ? q.get("name") : r.get("name"));
            it.put("qty", qty);
            it.put("cost", cost);
            it.put("currency", currency);
            it.put("price", price);
            it.put("pct", pct);
            it.put("live", price > 0);
            double mv = qty * price;
            it.put("mv", U.round(mv, 2));
            it.put("cost_total", U.round(qty * cost, 2));
            it.put("pl", U.round(mv - qty * cost, 2));
            it.put("pl_pct", cost > 0 ? U.round(U.pct(price, cost), 2) : 0);
            it.put("day_pl", price > 0 && qty > 0
                    ? U.round(qty * (price - price / (1 + pct / 100.0)), 2) : 0);
            it.put("score", r.get("score") != null ? U.num(r.get("score")) : null);
            it.put("signal", r.get("sig"));
            it.put("updated_at", String.valueOf(r.get("updated_at")));
            byBroker.computeIfAbsent((String) r.get("broker"), k -> new ArrayList<>()).add(it);
        }

        List<Map<String, Object>> brokers = new ArrayList<>();
        Map<String, double[]> grand = new LinkedHashMap<>();  // currency → [mv, cost, day_pl]
        for (var e : byBroker.entrySet()) {
            List<Map<String, Object>> items = e.getValue();
            Map<String, double[]> tot = new LinkedHashMap<>();
            for (Map<String, Object> it : items) {
                String cur = (String) it.get("currency");
                double mv = U.num(it.get("mv"));
                double ct = U.num(it.get("cost_total"));
                double dp = U.num(it.get("day_pl"));
                double[] t = tot.computeIfAbsent(cur, k -> new double[3]);
                t[0] += mv; t[1] += ct; t[2] += dp;
                double[] g = grand.computeIfAbsent(cur, k -> new double[3]);
                g[0] += mv; g[1] += ct; g[2] += dp;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("broker", e.getKey());
            b.put("items", items);
            b.put("totals", totalsList(tot));
            brokers.add(b);
        }

        out.put("ready", true);
        out.put("brokers", brokers);
        out.put("grand", totalsList(grand));
        // 约合人民币（静态近似汇率，仅用于跨币种量级参考）
        double cny = 0;
        for (var e : grand.entrySet()) {
            cny += e.getValue()[0] * (e.getKey().equals("HKD") ? fxHkd : e.getKey().equals("USD") ? fxUsd : 1);
        }
        out.put("grand_cny", U.round(cny, 0));
        out.put("fx", Map.of("HKD", fxHkd, "USD", fxUsd));
        return out;
    }

    private static List<Map<String, Object>> totalsList(Map<String, double[]> tot) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : tot.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", e.getKey());
            m.put("mv", U.round(e.getValue()[0], 2));
            m.put("cost", U.round(e.getValue()[1], 2));
            m.put("pl", U.round(e.getValue()[0] - e.getValue()[1], 2));
            m.put("pl_pct", e.getValue()[1] > 0 ? U.round(U.pct(e.getValue()[0], e.getValue()[1]), 2) : 0);
            m.put("day_pl", U.round(e.getValue()[2], 2));
            list.add(m);
        }
        return list;
    }

    // ---------------------------------------------------------------- 增删
    /** 新增 / 更新（broker+code 唯一）。行情能取到时用行情的名称与币种。 */
    public Map<String, Object> upsert(String broker, String codeRaw, String name, double qty, double cost) {
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
        try {
            jdbc.update("INSERT INTO holding (broker, code, name, qty, cost, currency) VALUES (?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE qty=VALUES(qty), cost=VALUES(cost), name=VALUES(name), "
                            + "currency=VALUES(currency)",
                    broker.trim(), code, finalName, qty, cost, currency);
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

    public boolean remove(long id) {
        try { return jdbc.update("DELETE FROM holding WHERE id=?", id) > 0; }
        catch (Exception e) { return false; }
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
            for (int i = headIdx + 1; i < tabbed.size(); i++) {
                Map<String, Object> row = parseByColumns(tabbed.get(i), colField);
                if (row != null) out.add(row);
            }
        }
        // 2) 卡片式布局：名称+代码行开块，块内按 label→value 配对
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

    private static boolean hasNumericToken(List<String> words) {
        for (String w : words) {
            String t = w.replaceAll("[+%,￥$¥\\s]", "");
            if (!t.isEmpty() && t.matches("-?\\d[\\d,]*(?:\\.\\d+)?")) return true;
        }
        return false;
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
