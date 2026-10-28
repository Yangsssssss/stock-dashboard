package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.feed.HttpFetch;
import com.quantboard.util.U;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大佬持仓：跟踪 13F 机构（对冲基金 / 资管）与主权基金的美股持仓。
 * 13F 机构可从 SEC EDGAR 一键拉取最新 13F-HR（submissions JSON → infotable XML），
 * 主权基金 / 其他来源支持手动录入。ticker 通过 SEC company_tickers.json 尽力匹配。
 */
@Service
public class GuruService {

    private final JdbcTemplate jdbc;
    private final NameCnService nameCn;

    /**
     * SEC 要求申报身份的 UA，格式「名称 + 真实邮箱」。
     * 邮箱为假的（如 user@localhost）或浏览器 UA 都会被 www.sec.gov 判为未申报的自动化工具直接 403，
     * 建议在 .env 配 QUANTBOARD_SEC_UA 换成自己的。
     */
    private static String secUa() {
        String env = System.getenv("QUANTBOARD_SEC_UA");
        return env != null && !env.isBlank() ? env
                : "QuantBoard Research yangs1997@126.com";
    }

    /** SEC 限流 10 请求/秒，逐次请求之间留间隔。 */
    private static void secPause() {
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 预置跟踪名单（CIK 已逐一在 SEC EDGAR 核实，2026-10）。name_cn 为机构中文名，note 为掌门人。首访时 INSERT IGNORE 播种。 */
    private static final String[][] SEEDS = {
            {"Berkshire Hathaway", "伯克希尔·哈撒韦", "13f", "1067983", "巴菲特", "1"},
            {"Appaloosa Management", "阿帕卢萨管理", "13f", "1656456", "大卫·泰珀", "2"},
            {"Pershing Square", "潘兴广场资本", "13f", "1336528", "比尔·阿克曼", "3"},
            {"Scion Asset Management", "赛恩资产管理", "13f", "1649339", "迈克尔·伯里（《大空头》原型）", "4"},
            {"Bridgewater Associates", "桥水基金", "13f", "1350694", "瑞·达利欧", "5"},
            {"Renaissance Technologies", "文艺复兴科技", "13f", "1037389", "吉姆·西蒙斯", "6"},
            {"Tiger Global Management", "老虎环球基金", "13f", "1167483", "蔡斯·科尔曼", "7"},
            {"Norges Bank", "挪威银行投资管理", "sovereign", "1374170", "挪威主权财富基金", "8"},
            {"Mubadala Investment", "穆巴达拉投资", "sovereign", "1704268", "阿布扎比主权基金", "9"},
            // 段永平：H&H International Investment 为其家族办公室，13F-HR 连续申报
            {"H&H International Investment", "H&H 国际投资", "13f", "1759760", "段永平", "10"},
            // 但斌：东方港湾海外主基金 Oriental Harbor Investment Master Fund
            {"Oriental Harbor Investment Master Fund", "东方港湾投资", "13f", "2046333", "但斌", "11"},
    };

    /** SEC ticker 映射缓存（company_tickers.json，24 小时刷新）。sorted 版用于词序无关的兜底匹配。 */
    private static volatile Map<String, String> tickerMap = Map.of();
    private static volatile Map<String, String> tickerMapSorted = Map.of();
    private static volatile long tickerMapAt = 0;

    public GuruService(JdbcTemplate jdbc, NameCnService nameCn) {
        this.jdbc = jdbc;
        this.nameCn = nameCn;
    }

    private void ensureSeed() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM guru", Long.class);
            if (n != null && n == 0) {
                for (String[] s : SEEDS) {
                    jdbc.update("INSERT IGNORE INTO guru (name, name_cn, gtype, cik, note, sort_no) VALUES (?,?,?,?,?,?)",
                            s[0], s[1], s[2], s[3], s[4], Integer.parseInt(s[5]));
                }
            }
        } catch (Exception ignore) { /* 表还没建（迁移未跑）时由调用方兜底 */ }
    }

    // ---------------------------------------------------------------- 总览
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            ensureSeed();
            List<Map<String, Object>> investors = jdbc.queryForList(
                    // 各家最新一期的持仓数与市值走派生表（一次算完再 join）。
                    // 原先用三个相关子查询 `quarter = (SELECT MAX(...) WHERE guru_id = g.id)`，
                    // MySQL 会对 g 的每一行重算一遍，5727 行时单条 2.4 秒。
                    "SELECT g.id, g.name, g.name_cn, g.gtype, g.cik, g.note, g.sort_no, "
                            + "m.mq AS latest_quarter, "
                            + "COALESCE(s.cnt, 0) AS holdings, "
                            + "COALESCE(s.total_yi, 0) AS total_yi "
                            + "FROM guru g "
                            + "LEFT JOIN (SELECT guru_id, MAX(quarter) mq FROM guru_holding GROUP BY guru_id) m "
                            + "  ON m.guru_id = g.id "
                            + "LEFT JOIN (SELECT guru_id, quarter, COUNT(*) cnt, "
                            + "           ROUND(SUM(value_usd)/1e8, 1) total_yi FROM guru_holding "
                            + "           GROUP BY guru_id, quarter) s "
                            + "  ON s.guru_id = g.id AND s.quarter = m.mq "
                            + "ORDER BY g.sort_no, g.id");
            // 每家机构最新报告期的第一大持仓（中文名优先，未收录则用 SEC 英文原名）
            Map<Long, String> topHold = new HashMap<>();
            List<Map<String, Object>> tops = jdbc.queryForList(
                    "SELECT h.guru_id, h.quarter, h.code, h.name, c.name_cn FROM guru_holding h "
                            + "LEFT JOIN security_cn c ON c.code = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "JOIN (SELECT guru_id, MAX(quarter) q FROM guru_holding GROUP BY guru_id) m "
                            + "ON m.guru_id = h.guru_id AND m.q = h.quarter "
                            + "ORDER BY h.value_usd DESC");
            for (Map<String, Object> r : tops) {
                long gid = ((Number) r.get("guru_id")).longValue();
                String raw = String.valueOf(r.get("code"));
                String label = issuerLabel(r.get("name_cn"), r.get("name"));
                // 已匹配到美股代码的附上代码便于核对；CUSIP 那一串数字对读者没意义，不显示
                boolean isTicker = raw.toLowerCase().startsWith("us");
                topHold.putIfAbsent(gid, isTicker ? label + " · " + shortCode(raw) : label);
            }
            for (Map<String, Object> inv : investors) {
                inv.put("total_yi", ((Number) inv.getOrDefault("total_yi", 0)).doubleValue());
                inv.put("holdings", ((Number) inv.getOrDefault("holdings", 0)).longValue());
                inv.put("top", topHold.getOrDefault(((Number) inv.get("id")).longValue(), "—"));
            }
            out.put("ready", true);
            out.put("investors", investors);

            List<String> quarters = jdbc.queryForList(
                    "SELECT DISTINCT quarter FROM guru_holding ORDER BY quarter DESC LIMIT 16", String.class);
            out.put("quarters", quarters);

            // 共识榜：被 ≥2 家机构同时持有
            List<Map<String, Object>> cons = jdbc.queryForList(
                    "SELECT h.code, MAX(h.name) AS name, MAX(c.name_cn) AS name_cn, "
                            + "COUNT(DISTINCT h.guru_id) AS gurus, "
                            + "ROUND(SUM(h.value_usd)/1e8, 1) AS total_yi, "
                            + "GROUP_CONCAT(DISTINCT h.guru_id) AS ids "
                            + "FROM guru_holding h LEFT JOIN security_cn c ON c.code = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "GROUP BY h.code HAVING gurus >= 2 "
                            + "ORDER BY gurus DESC, total_yi DESC LIMIT 30");
            for (Map<String, Object> r : cons) {
                r.put("gurus", ((Number) r.get("gurus")).longValue());
                r.put("total_yi", ((Number) r.getOrDefault("total_yi", 0)).doubleValue());
            }
            out.put("consensus", cons);
            out.put("rows", countRows());
            out.put("cn_missing", countMissingCn());
            out.put("code_missing", countMissingCode());
        } catch (Exception e) {
            out.put("ready", false);
            out.put("investors", List.of());
            out.put("quarters", List.of());
            out.put("consensus", List.of());
            out.put("rows", 0);
        }
        return out;
    }

    private long countRows() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM guru_holding", Long.class);
        return c == null ? 0 : c;
    }

    /** 还没收录中文名的美股代码数（前端据此决定是否触发一次补齐）。 */
    private long countMissingCn() {
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT h.code) FROM guru_holding h "
                            + "LEFT JOIN security_cn s ON s.code = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE s.code IS NULL AND h.code LIKE 'us%'", Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 还挂在 CUSIP 上、没有美股代码的标的数（前端据此提示「代码未识别」）。 */
    private long countMissingCode() {
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT SUBSTRING_INDEX(h.code, '~', 1)) FROM guru_holding h "
                            + "LEFT JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE h.code NOT LIKE 'us%' AND (m.cusip IS NULL OR m.ticker = '')", Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 界面显示用的发行人名：有中文名用中文名，没有就用 SEC 英文原名。 */
    private static String issuerLabel(Object nameCn, Object name) {
        String cn = str(nameCn);
        return cn.isEmpty() ? str(name) : cn;
    }

    /** 代码去市场前缀（usAAPL → AAPL）；未匹配到美股代码的（CUSIP）原样返回。 */
    private static String shortCode(String code) {
        String c = str(code);
        if (c.toLowerCase().startsWith("us")) c = c.substring(2);
        int cut = c.indexOf('~');
        return cut > 0 ? c.substring(0, cut) : c;
    }

    // ---------------------------------------------------------------- 持仓明细
    public Map<String, Object> holdings(long guruId, String quarter) {
        return holdings(guruId, quarter, 1, 200);
    }

    /**
     * 持仓明细分页。
     *
     * 早先这里写死 {@code LIMIT 500}：选「全部机构 / 各机构最新」时实际有 5700+ 行，
     * 前端却只拿到市值前 500（8.7%），且没有任何"还有更多"的提示，等于静默丢数据。
     * 现在按页取，total/pages 一并返回，由前端翻页。
     */
    public Map<String, Object> holdings(long guruId, String quarter, int page, int size) {
        int sz = Math.max(20, Math.min(size, 500));
        int pg = Math.max(1, page);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String q = quarter == null ? "" : quarter.trim();
            boolean perInvestorLatest = false;
            if (q.isEmpty()) {
                if (guruId > 0) {
                    // 选中某机构 → 取它自己的最新一期（停报或转 13F-NT 的机构季报会落后于全局最新一期）
                    List<String> qs = jdbc.queryForList("SELECT quarter FROM guru_holding WHERE guru_id = ? "
                            + "ORDER BY quarter DESC LIMIT 1", String.class, guruId);
                    q = qs.isEmpty() ? "" : qs.get(0);
                } else {
                    // 全部机构 → 各取各自最新一期。若统一按全局最新一期过滤，
                    // 只要有人先申报了下一季度，其他机构就会集体消失（默认视图只剩一家）
                    perInvestorLatest = true;
                }
            }
            out.put("quarter", q);
            out.put("scope", perInvestorLatest ? "per_investor_latest" : (q.isEmpty() ? "all" : q));

            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (guruId > 0) { where.append(" AND h.guru_id = ?"); args.add(guruId); }
            if (!q.isEmpty()) { where.append(" AND h.quarter = ?"); args.add(q); }
            // 「各机构最新一期」用派生表 join，不用相关子查询：
            // 后者会让 MySQL 对每一行重算一次 MAX，5727 行下 COUNT 单条就要 2.4 秒。
            String latestJoin = perInvestorLatest
                    ? " JOIN (SELECT guru_id, MAX(quarter) mq FROM guru_holding GROUP BY guru_id) t"
                    + " ON t.guru_id = h.guru_id AND h.quarter = t.mq"
                    : "";

            long total = 0;
            double totalUsd = 0;
            try {
                Long c = jdbc.queryForObject("SELECT COUNT(*) FROM guru_holding h" + latestJoin + where,
                        Long.class, args.toArray());
                total = c == null ? 0 : c;
                Double v = jdbc.queryForObject("SELECT SUM(h.value_usd) FROM guru_holding h" + latestJoin + where,
                        Double.class, args.toArray());
                totalUsd = v == null ? 0 : v;
            } catch (Exception ignore) {
                // 统计失败不影响翻页主流程
            }
            int pages = total <= 0 ? 1 : (int) ((total + sz - 1) / sz);
            if (pg > pages) pg = pages;

            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(sz);
            pageArgs.add((pg - 1) * sz);
            String sql = "SELECT h.id, h.guru_id, g.name AS guru, g.name_cn AS guru_cn, h.quarter, h.code, h.cusip, h.name, "
                    + "c.name_cn, h.shares, h.value_usd, h.pct, h.note FROM guru_holding h "
                    + "JOIN guru g ON g.id = h.guru_id "
                    + "LEFT JOIN security_cn c ON c.code = SUBSTRING_INDEX(h.code, '~', 1)"
                    + latestJoin
                    + where + " ORDER BY h.value_usd DESC, h.id LIMIT ? OFFSET ?";
            List<Map<String, Object>> rows = jdbc.queryForList(sql, pageArgs.toArray());
            for (Map<String, Object> r : rows) {
                r.put("shares", U2(r.get("shares")));
                r.put("value_usd", U2(r.get("value_usd")));
                r.put("value_yi", round2(((Number) r.get("value_usd")).doubleValue() / 1e8));
                r.put("pct", U2(r.get("pct")));
                r.put("guru_label", issuerLabel(r.get("guru_cn"), r.get("guru")));
                r.put("label", issuerLabel(r.get("name_cn"), r.get("name")));
                r.put("short_code", shortCode(String.valueOf(r.get("code"))));
            }
            out.put("rows", rows);
            out.put("total", total);
            out.put("total_yi", round2(totalUsd / 1e8));
            out.put("page", pg);
            out.put("size", sz);
            out.put("pages", pages);
            out.put("from", total == 0 ? 0 : (long) (pg - 1) * sz + 1);
            out.put("to", Math.min((long) pg * sz, total));
            out.put("ready", true);
        } catch (Exception e) {
            out.put("ready", false);
            out.put("rows", List.of());
            out.put("total", 0);
            out.put("page", 1);
            out.put("size", sz);
            out.put("pages", 1);
        }
        return out;
    }

    private static double U2(Object o) {
        return o == null ? 0 : Math.round(((Number) o).doubleValue() * 100.0) / 100.0;
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ---------------------------------------------------------------- 环比调仓
    /**
     * 本期 vs 上期的持仓变化：新建 / 加仓 / 减仓 / 清仓 / 不变。
     *
     * 13F 是季度快照，单看一期只能知道"现在持有什么"；真正有信息量的是"这一季动了什么"。
     * 按 code 汇总（同一发行人的多行、期权行各自成键），以股数变化定方向、以市值变化定权重。
     *
     * quarter 为空时取该机构最新一期；上一期取该机构 quarter 小于本期里最大的一个
     * （机构停报会跳季，不能简单减 1）。
     */
    public Map<String, Object> changes(long guruId, String quarter) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (guruId <= 0) {
            out.put("ready", false);
            out.put("note", "请先选择一家机构 —— 环比只在同一家机构自己的相邻两期之间比较才有意义");
            out.put("rows", List.of());
            return out;
        }
        try {
            String cur = quarter == null ? "" : quarter.trim();
            if (cur.isEmpty()) {
                List<String> qs = jdbc.queryForList("SELECT quarter FROM guru_holding WHERE guru_id = ? "
                        + "ORDER BY quarter DESC LIMIT 1", String.class, guruId);
                cur = qs.isEmpty() ? "" : qs.get(0);
            }
            if (cur.isEmpty()) {
                out.put("ready", true);
                out.put("note", "该机构还没有持仓数据");
                out.put("rows", List.of());
                return out;
            }
            String prev = "";
            List<String> ps = jdbc.queryForList("SELECT quarter FROM guru_holding WHERE guru_id = ? AND quarter < ? "
                    + "ORDER BY quarter DESC LIMIT 1", String.class, guruId, cur);
            if (!ps.isEmpty()) prev = ps.get(0);

            Map<String, double[]> curMap = snap(guruId, cur);      // code → [shares, value]
            Map<String, double[]> prevMap = prev.isEmpty() ? Map.of() : snap(guruId, prev);
            Map<String, String[]> meta = new HashMap<>();           // code → [name, name_cn, cusip]
            fillMeta(guruId, cur, prev, meta);

            List<Map<String, Object>> rows = new ArrayList<>();
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            codes.addAll(curMap.keySet());
            codes.addAll(prevMap.keySet());
            int nNew = 0, nExit = 0, nAdd = 0, nTrim = 0, nHold = 0;
            for (String code : codes) {
                double[] c = curMap.getOrDefault(code, new double[]{0, 0});
                double[] p = prevMap.getOrDefault(code, new double[]{0, 0});
                double dSh = c[0] - p[0];
                double dVal = c[1] - p[1];
                String action;
                if (p[0] <= 0 && c[0] > 0) { action = "NEW"; nNew++; }
                else if (c[0] <= 0 && p[0] > 0) { action = "EXIT"; nExit++; }
                else if (dSh > 1e-6) { action = "ADD"; nAdd++; }
                else if (dSh < -1e-6) { action = "TRIM"; nTrim++; }
                else { action = "HOLD"; nHold++; }
                // 不变的行不返回：文艺复兴这类机构单期两千多行，全给前端没有信息量，
                // 变化情况用 summary 里的计数表达即可
                if ("HOLD".equals(action)) continue;

                String[] m = meta.getOrDefault(code, new String[]{"", "", ""});
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("code", code);
                r.put("short_code", shortCode(code));
                r.put("name", m[0]);
                r.put("name_cn", m[1]);
                r.put("label", issuerLabel(m[1], m[0]));
                r.put("action", action);
                r.put("shares", round2(c[0]));
                r.put("prev_shares", round2(p[0]));
                r.put("delta_shares", round2(dSh));
                r.put("delta_pct", p[0] > 0 ? round2(dSh / p[0] * 100) : null);
                r.put("value_yi", round2(c[1] / 1e8));
                r.put("prev_value_yi", round2(p[1] / 1e8));
                r.put("delta_value_yi", round2(dVal / 1e8));
                rows.add(r);
            }
            // 变化大的排前面：新建/清仓优先，其次按市值变化绝对值
            rows.sort((a, b) -> {
                double wa = rank(String.valueOf(a.get("action"))), wb = rank(String.valueOf(b.get("action")));
                if (wa != wb) return Double.compare(wb, wa);
                return Double.compare(Math.abs(U.num(b.get("delta_value_yi"))), Math.abs(U.num(a.get("delta_value_yi"))));
            });
            out.put("ready", true);
            out.put("guru_id", guruId);
            out.put("quarter", cur);
            out.put("prev_quarter", prev);
            out.put("comparable", !prev.isEmpty());
            out.put("note", prev.isEmpty() ? "该机构只有一期数据，暂时无法做环比" : "");
            out.put("summary", Map.of("new", nNew, "add", nAdd, "trim", nTrim, "exit", nExit, "hold", nHold));
            out.put("total_changes", rows.size());
            // 单期调仓超过 200 条的（文艺复兴这类量化机构），只返回变化最大的 200 条
            if (rows.size() > 200) rows = new ArrayList<>(rows.subList(0, 200));
            out.put("rows", rows);
        } catch (Exception e) {
            out.put("ready", false);
            out.put("rows", List.of());
            out.put("note", "环比计算失败：" + e.getMessage());
        }
        return out;
    }

    /** NEW/EXIT 先看，ADD/TRIM 次之，HOLD 最后。 */
    private static double rank(String action) {
        return switch (action) {
            case "NEW" -> 4;
            case "EXIT" -> 3;
            case "ADD" -> 2;
            case "TRIM" -> 1;
            default -> 0;
        };
    }

    /** 某机构某期的 code → [合计股数, 合计市值]，同一 code 的多行合并。 */
    private Map<String, double[]> snap(long guruId, String quarter) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code, SUM(shares) sh, SUM(value_usd) v FROM guru_holding "
                        + "WHERE guru_id=? AND quarter=? GROUP BY code", guruId, quarter)) {
            m.put(String.valueOf(r.get("code")),
                    new double[]{U.num(r.get("sh")), U.num(r.get("v"))});
        }
        return m;
    }

    /** 名称/CUSIP 优先取本期，本期没有（清仓）取上期。 */
    private void fillMeta(long guruId, String cur, String prev, Map<String, String[]> meta) {
        List<String> quarters = new ArrayList<>();
        quarters.add(cur);
        if (prev != null && !prev.isEmpty()) quarters.add(prev);
        for (String q : quarters) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT h.code, h.name, h.cusip, c.name_cn FROM guru_holding h "
                            + "LEFT JOIN security_cn c ON c.code = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE h.guru_id=? AND h.quarter=?", guruId, q)) {
                String code = String.valueOf(r.get("code"));
                if (meta.containsKey(code)) continue;
                meta.put(code, new String[]{str(r.get("name")), str(r.get("name_cn")), str(r.get("cusip"))});
            }
        }
    }

    // ---------------------------------------------------------------- 机构 CRUD
    public Map<String, Object> addInvestor(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = str(body.get("name"));
        String gtype = str(body.getOrDefault("gtype", "13f"));
        String cik = str(body.getOrDefault("cik", "")).replaceAll("\\D", "");
        if (name.isEmpty()) { out.put("ok", false); out.put("error", "机构名不能为空"); return out; }
        if (!gtype.matches("13f|sovereign")) gtype = "13f";
        if ("13f".equals(gtype) && (cik.isEmpty() || cik.length() > 10)) {
            out.put("ok", false); out.put("error", "13F 机构需填 SEC CIK（纯数字，10 位以内）"); return out;
        }
        if (!cik.isEmpty()) cik = String.format("%010d", Long.parseLong(cik));
        try {
            Long maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no),0) FROM guru", Long.class);
            jdbc.update("INSERT INTO guru (name, name_cn, gtype, cik, note, sort_no) VALUES (?,?,?,?,?,?)",
                    name, str(body.getOrDefault("name_cn", "")), gtype, cik,
                    str(body.getOrDefault("note", "")), maxSort == null ? 1 : maxSort + 1);
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage().contains("Duplicate") ? "同名机构已存在" : e.getMessage());
        }
        return out;
    }

    public Map<String, Object> updateInvestor(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        long id;
        try { id = Long.parseLong(String.valueOf(body.get("id"))); }
        catch (Exception e) { out.put("ok", false); out.put("error", "无效 id"); return out; }
        String cik = str(body.getOrDefault("cik", "")).replaceAll("\\D", "");
        if (!cik.isEmpty()) cik = String.format("%010d", Long.parseLong(cik));
        try {
            int n = jdbc.update("UPDATE guru SET name=?, name_cn=?, gtype=?, cik=?, note=? WHERE id=?",
                    str(body.getOrDefault("name", "")),
                    str(body.getOrDefault("name_cn", "")),
                    str(body.getOrDefault("gtype", "13f")),
                    cik, str(body.getOrDefault("note", "")), id);
            out.put("ok", n > 0);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    public boolean removeInvestor(long id) {
        try {
            jdbc.update("DELETE FROM guru_holding WHERE guru_id=?", id);
            jdbc.update("DELETE FROM guru WHERE id=?", id);
            return true;
        } catch (Exception e) { return false; }
    }

    // ---------------------------------------------------------------- 持仓 CRUD
    public Map<String, Object> addHolding(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        long guruId = num(body.get("guru_id"));
        String quarter = str(body.getOrDefault("quarter", "")).toUpperCase();
        String code = str(body.getOrDefault("code", "")).toUpperCase();
        double shares = num(body.get("shares"));
        double value = num(body.get("value_usd"));
        if (guruId <= 0) { out.put("ok", false); out.put("error", "缺少机构"); return out; }
        if (!quarter.matches("\\d{4}Q[1-4]")) { out.put("ok", false); out.put("error", "报告期格式如 2026Q2"); return out; }
        if (code.isEmpty()) { out.put("ok", false); out.put("error", "代码不能为空"); return out; }
        if (value <= 0) { out.put("ok", false); out.put("error", "市值需大于 0（美元）"); return out; }
        try {
            jdbc.update("INSERT INTO guru_holding (guru_id, quarter, code, cusip, name, shares, value_usd, note) "
                            + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                            + "name=VALUES(name), shares=VALUES(shares), value_usd=VALUES(value_usd), note=VALUES(note)",
                    guruId, quarter, code, str(body.getOrDefault("cusip", "")),
                    str(body.getOrDefault("name", code)), shares, value, str(body.getOrDefault("note", "")));
            recomputePct(guruId, quarter);
            out.put("ok", true);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    public boolean removeHolding(long id) {
        try { jdbc.update("DELETE FROM guru_holding WHERE id=?", id); return true; }
        catch (Exception e) { return false; }
    }

    /** 重算某机构某报告期各标的占比。 */
    private void recomputePct(long guruId, String quarter) {
        try {
            Double total = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(value_usd),0) FROM guru_holding WHERE guru_id=? AND quarter=?",
                    Double.class, guruId, quarter);
            if (total == null || total <= 0) return;
            jdbc.update("UPDATE guru_holding SET pct=ROUND(value_usd/?*100, 4) WHERE guru_id=? AND quarter=?",
                    total, guruId, quarter);
        } catch (Exception ignore) { }
    }

    /**
     * 把存量持仓里「代码 = CUSIP」的行改写成正常美股代码。
     * 历史数据是在 cusip_ticker 表建立之前入库的，靠发行名匹配失败就落成了 874039100 这种裸 CUSIP；
     * 有了权威映射后统一回填，并把因此产生的重复行合并（同一机构同一期的同一标的）。
     * 可重复调用：没有挂上映射的行原样不动。
     */
    public Map<String, Object> remapCusip() {
        Map<String, Object> out = new LinkedHashMap<>();
        cusipMap = Map.of();
        cusipMapAt = 0;                    // 强制重新加载映射表
        Map<String, String> map = cusipMap();
        if (map.isEmpty()) {
            out.put("ok", false);
            out.put("error", "cusip_ticker 映射表为空，先跑一次 POST /api/guru/cusip-map 生成映射");
            return out;
        }
        try {
            // 0) 先把这个 CUSIP 上已有的中文名挪到新代码名下。
            //    手工补过的 CUSIP 词条（应用材料、台积电…）质量比回源取名更准，
            //    若等代码改完再补，security_cn 只会按新 ticker 去腾讯重新查一遍。
            jdbc.update("INSERT INTO security_cn (code, name_cn, src) "
                    + "SELECT CONCAT('us', m.ticker), MAX(s.name_cn), 'manual' FROM guru_holding h "
                    + "JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                    + "JOIN security_cn s ON s.code = SUBSTRING_INDEX(h.code, '~', 1) "
                    + "WHERE h.code NOT LIKE 'us%' AND m.ticker <> '' AND s.name_cn <> '' "
                    + "GROUP BY m.ticker "
                    + "ON DUPLICATE KEY UPDATE security_cn.name_cn = security_cn.name_cn");

            // 1) 改写代码。不能直接 UPDATE：唯一键 uk_gq 是 (guru_id, quarter, code)，不含 note，
            //    两个 CUSIP 映射到同一个 ticker 时后一条会撞键、整条 UPDATE 中止。
            //    所以走「落临时表 → 删旧行 → 合并插入」，顺手把同标的的持仓合并成一行。
            //    ticker='' 是「查过确实没有美股代码」的负缓存，必须排除，否则 CONCAT 出来是一片空代码 us 互相撞键。
            jdbc.execute("DROP TABLE IF EXISTS guru_holding_remap");
            // 内层先把新代码算出来，外层再按它聚合 —— 直接在 GROUP BY 里写别名 code 会和原表 h.code 撞名
            jdbc.execute("CREATE TABLE guru_holding_remap AS "
                    + "SELECT guru_id, quarter, code, MAX(cusip) AS cusip, MAX(name) AS name, "
                    + "  SUM(shares) AS shares, SUM(value_usd) AS value_usd, MAX(note) AS note FROM ( "
                    + "  SELECT h.guru_id, h.quarter, "
                    + "    CONCAT('us', m.ticker, IF(LOCATE('~', h.code) > 0, SUBSTRING(h.code, LOCATE('~', h.code)), '')) AS code, "
                    + "    h.cusip, h.name, h.shares, h.value_usd, h.note "
                    + "  FROM guru_holding h "
                    + "  JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                    + "  WHERE h.code NOT LIKE 'us%' AND m.ticker <> '' "
                    + ") t GROUP BY guru_id, quarter, code");
            Long planned = jdbc.queryForObject("SELECT COUNT(*) FROM guru_holding_remap", Long.class);
            int removed = jdbc.update("DELETE h FROM guru_holding h "
                    + "JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                    + "WHERE h.code NOT LIKE 'us%' AND m.ticker <> ''");
            // 撞到已存在的同名代码（另一条 CUSIP 已先映射过）时累加持股与市值，不覆盖
            int changed = jdbc.update("INSERT INTO guru_holding "
                    + "(guru_id, quarter, code, cusip, name, shares, value_usd, pct, note) "
                    + "SELECT guru_id, quarter, code, cusip, name, shares, value_usd, 0, note "
                    + "FROM guru_holding_remap "
                    + "ON DUPLICATE KEY UPDATE "
                    + "shares = guru_holding.shares + VALUES(shares), "
                    + "value_usd = guru_holding.value_usd + VALUES(value_usd)");
            jdbc.execute("DROP TABLE IF EXISTS guru_holding_remap");

            // 2) 兜底合并：历史上可能已有重复行（同一机构同期同代码多条）
            List<Map<String, Object>> dup = jdbc.queryForList(
                    "SELECT guru_id, quarter, code, note, MIN(id) keep_id, SUM(shares) s, SUM(value_usd) v, COUNT(*) c "
                            + "FROM guru_holding GROUP BY guru_id, quarter, code, note HAVING c > 1");
            int merged = 0;
            for (Map<String, Object> d : dup) {
                jdbc.update("UPDATE guru_holding SET shares=?, value_usd=? WHERE id=?",
                        U2(d.get("s")), U2(d.get("v")), d.get("keep_id"));
                int n = jdbc.update(
                        "DELETE FROM guru_holding WHERE guru_id=? AND quarter=? AND code=? AND note=? AND id<>?",
                        d.get("guru_id"), d.get("quarter"), d.get("code"), d.get("note"), d.get("keep_id"));
                merged += n;
            }

            // 3) 每个机构的每一期都重算占比
            List<Map<String, Object>> qs = jdbc.queryForList(
                    "SELECT DISTINCT guru_id, quarter FROM guru_holding");
            for (Map<String, Object> r : qs) {
                recomputePct(((Number) r.get("guru_id")).longValue(), str(r.get("quarter")));
            }

            // 4) 新代码补中文名（原来补的是 CUSIP 那条，ticker 未必有）
            try {
                nameCn.fillCodes(new ArrayList<>(jdbc.queryForList(
                        "SELECT DISTINCT h.code FROM guru_holding h LEFT JOIN security_cn s "
                                + "ON s.code = SUBSTRING_INDEX(h.code, '~', 1) "
                                + "WHERE s.code IS NULL AND h.code LIKE 'us%'", String.class)));
            } catch (Exception ignore) { }

            out.put("ok", true);
            out.put("planned", planned == null ? 0 : planned);
            out.put("removed", removed);
            out.put("inserted", changed);
            out.put("merged", merged);
            out.put("rows", countRows());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }


    /** 拉取指定机构最新的 13F-HR 并入库。返回 {ok, quarter, rows, skipped}。 */
    public Map<String, Object> import13f(long id) {
        return import13f(id, 0);
    }

    /**
     * @param skip 跳过最近几份 13F-HR。0 = 最新一期（常规拉取）；1 = 上一期。
     *             环比调仓需要相邻两期，而首次导入只会拿到最新一期，
     *             所以要靠 skip=1 把上一期补进来，否则「本期调仓」永远是空的。
     */
    public Map<String, Object> import13f(long id, int skip) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> inv = jdbc.queryForMap(
                    "SELECT id, name, gtype, cik FROM guru WHERE id=?", id);
            String cik = String.valueOf(inv.get("cik")).replaceAll("\\D", "");
            if (cik.isEmpty()) {
                out.put("ok", false); out.put("error", "该机构未配置 SEC CIK，无法自动拉取"); return out;
            }
            String cik10 = String.format("%010d", Long.parseLong(cik));

            // 1) 找最近一份 13F-HR（跳过修订 13F-HR/A）
            ObjectMapper om = new ObjectMapper();
            secPause();
            JsonNode sub = om.readTree(HttpFetch.get(
                    "https://data.sec.gov/submissions/CIK" + cik10 + ".json",
                    30, "User-Agent: " + secUa()));
            JsonNode recent = sub.path("filings").path("recent");
            // 按申报时间倒序收集所有 13F-HR（跳过 13F-HR/A 修订件），支持按 skip 取更早的一期
            List<Integer> hr = new ArrayList<>();
            JsonNode forms = recent.path("form");
            for (int i = 0; i < forms.size(); i++) {
                if ("13F-HR".equals(forms.get(i).asText())) hr.add(i);
            }
            if (hr.isEmpty()) { out.put("ok", false); out.put("error", "SEC 上没有找到 13F-HR 申报"); return out; }
            if (skip > 0 && skip >= hr.size()) {
                out.put("ok", false);
                out.put("error", "SEC 上只有 " + hr.size() + " 份 13F-HR，没有更早的一期可补");
                return out;
            }
            int idx = hr.get(Math.max(0, Math.min(skip, hr.size() - 1)));
            String acc = recent.path("accessionNumber").get(idx).asText();
            String period = recent.path("reportDate").get(idx).asText();   // 如 2026-06-30
            String dir = "https://www.sec.gov/Archives/edgar/data/"
                    + Integer.parseInt(cik) + "/" + acc.replace("-", "");

            // 2) 找 information table XML。部分 filing 用纯数字命名（如 56757.xml），
            //    只靠文件名认不出来，所以再按内容根元素兜底；封面页 primary_doc.xml 必须排除。
            secPause();
            JsonNode index = om.readTree(HttpFetch.get(dir + "/index.json", 20, "User-Agent: " + secUa()));
            List<String> xmls = new ArrayList<>();
            for (JsonNode it : index.path("directory").path("item")) {
                String n = it.path("name").asText();
                String lo = n.toLowerCase();
                if (lo.endsWith(".xml") && !lo.contains("primary_doc")) xmls.add(n);
            }
            if (xmls.isEmpty()) {
                out.put("ok", false); out.put("error", "filing 目录里没有可用的 XML"); return out;
            }
            String infoXml = null;
            for (String n : xmls) {
                String lo = n.toLowerCase();
                if (lo.contains("infotable") || lo.contains("informationtable") || lo.contains("info_table")) {
                    infoXml = n; break;
                }
            }
            byte[] xmlBytes = null;
            if (infoXml != null) {
                secPause();
                xmlBytes = HttpFetch.get(dir + "/" + infoXml, 60, "User-Agent: " + secUa());
            } else {
                for (String n : xmls) {
                    secPause();
                    byte[] b = HttpFetch.get(dir + "/" + n, 60, "User-Agent: " + secUa());
                    // 根元素是 informationTable / infoTable 才是持仓表（带命名空间前缀时也能命中）
                    String head = new String(b, 0, Math.min(b.length, 8192), StandardCharsets.UTF_8).toLowerCase();
                    if (head.contains("infotable") || head.contains("informationtable")) {
                        infoXml = n; xmlBytes = b; break;
                    }
                }
            }
            if (infoXml == null) {
                out.put("ok", false);
                out.put("error", "filing 目录里未找到 information table（候选：" + String.join(", ", xmls) + "）");
                return out;
            }
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // 必须开命名空间感知：部分机构（如桥水）用 ns1:infoTable 这种带前缀的写法，
            // 不开的话 getLocalName() 为 null、标签名带前缀，取不到任何一行
            dbf.setNamespaceAware(true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));

            Map<String, double[]> agg = new LinkedHashMap<>();   // 最终代码 → {shares, valueUsd}
            Map<String, String[]> meta = new HashMap<>();        // 最终代码 → {name, cusip, putCall}
            Map<String, String> codeCache = new HashMap<>();     // 发行人|CUSIP → 代码（大机构多管理人重复申报，避免反复归一化）
            // 本库其它期已经把这个 CUSIP 认成了 ticker → 直接沿用。
            // 否则同一笔持仓在两个季度会落成两种代码（Q1 裸 CUSIP、Q2 usBAC），
            // 环比就会同时出现一条「新建」和一条「清仓」，看着像换仓，其实是代码没对齐。
            Map<String, String> sameCusip = new HashMap<>();
            try {
                for (Map<String, Object> r : jdbc.queryForList(
                        "SELECT cusip, MAX(code) AS c FROM guru_holding WHERE cusip<>'' GROUP BY cusip")) {
                    String c = String.valueOf(r.get("c"));
                    if (c.startsWith("us")) sameCusip.put(String.valueOf(r.get("cusip")), c);
                }
            } catch (Exception ignore) { }
            // 从 Document 起递归，根元素本身就是 infoTable 的极简 filing 也能命中
            collectInfoTables(doc, row -> {
                String name = child(row, "nameOfIssuer");
                String cusip = child(row, "cusip");
                String putCall = child(row, "putCall");
                // SEC 自 2009 年起要求 13F 的 value 以「美元」申报（不是千美元），直接取用
                double valueUsd = parseD(child(row, "value"));
                // sshPrnamt 在 <shrsOrPrnAmt> 容器里（旧版直接挂在 infoTable 下），需递归找
                double shares = parseD(descendant(row, "sshPrnamt"));
                if (name.isEmpty() || valueUsd <= 0) return;
                // 按最终代码聚合：同一发行人的不同 CUSIP（股份类别 / 多管理人分别申报）
                // 会映射到同一个 ticker，若不先合并，入库时会被唯一键覆盖而丢仓
                String key = codeCache.computeIfAbsent(name + "|" + cusip, k -> {
                    String mapped = holdingCode(name, cusip);
                    // 名称匹配不出来（同一发行人在不同季报里写法不一）时，沿用已有期次的代码
                    if (!mapped.startsWith("us") && sameCusip.containsKey(cusip)) return sameCusip.get(cusip);
                    return mapped;
                });
                if (!putCall.isEmpty()) key = key + "~" + putCall;   // 期权单独一行
                agg.computeIfAbsent(key, k -> new double[2]);
                agg.get(key)[0] += shares;
                agg.get(key)[1] += valueUsd;
                meta.putIfAbsent(key, new String[]{name, cusip, putCall});
            });

            if (agg.isEmpty()) { out.put("ok", false); out.put("error", "information table 解析结果为空"); return out; }

            String quarter = toQuarter(period);
            // 4) 入库：13F 是该报告期的完整快照，先清后插，
            //    避免标的代码从 CUSIP 变成 ticker 时旧行残留、重复计数。
            List<Object[]> rows = new ArrayList<>();
            for (var e : agg.entrySet()) {
                String[] m = meta.get(e.getKey());
                rows.add(new Object[]{id, quarter, e.getKey(), m[1], m[0], e.getValue()[0], e.getValue()[1],
                        m[2].isEmpty() ? "" : m[2]});
            }
            jdbc.update("DELETE FROM guru_holding WHERE guru_id=? AND quarter=?", id, quarter);
            jdbc.batchUpdate("INSERT INTO guru_holding (guru_id, quarter, code, cusip, name, shares, value_usd, note) "
                    + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                    + "name=VALUES(name), shares=VALUES(shares), value_usd=VALUES(value_usd), note=VALUES(note)", rows);
            recomputePct(id, quarter);

            // 新出现的代码顺手补中文名（腾讯批量取名，失败不影响导入）
            try {
                List<String> codes = new ArrayList<>();
                for (String k : agg.keySet()) codes.add(k.split("~")[0]);
                nameCn.fillCodes(codes);
            } catch (Exception ignore) { }

            out.put("ok", true);
            out.put("quarter", quarter);
            out.put("rows", rows.size());
            out.put("guru", String.valueOf(inv.get("name")));
        } catch (Exception e) {
            out.put("ok", false);
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            out.put("error", msg.contains("403")
                    ? "SEC 拒绝访问（403）：UA 需为「名称 + 真实邮箱」。在 .env 配 QUANTBOARD_SEC_UA=ShenYang Research your@mail.com 后重启"
                    : msg.contains("rc=7")
                        ? "SEC 连接失败（网络直连不通，自动走系统代理重试仍失败）。请确认本机代理（如 Clash 7890 端口）已启动，或在 .env 配 QUANTBOARD_HTTP_PROXY=http://127.0.0.1:端口 后重启"
                        : "拉取失败：" + msg);
        }
        return out;
    }

    private interface RowConsumer { void accept(Element e); }

    /** 递归收集所有 infoTable 节点（兼容带命名空间前缀的 filing）。 */
    private static void collectInfoTables(Node node, RowConsumer fn) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            String ln = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
            if (ln.equalsIgnoreCase("infoTable")) { fn.accept((Element) c); }
            else collectInfoTables(c, fn);
        }
    }

    private static String child(Element row, String tag) {
        for (Node c = row.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            String ln = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
            if (ln.equalsIgnoreCase(tag)) {
                String t = c.getTextContent();
                return t == null ? "" : t.trim();
            }
        }
        return "";
    }

    /** 深度优先找第一个匹配 localName 的后代节点文本（child 只查直接子级，sshPrnamt 这类套在容器里）。 */
    private static String descendant(Element row, String tag) {
        for (Node c = row.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) continue;
            String ln = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
            if (ln.equalsIgnoreCase(tag)) {
                String t = c.getTextContent();
                return t == null ? "" : t.trim();
            }
            String deep = descendant((Element) c, tag);
            if (!deep.isEmpty()) return deep;
        }
        return "";
    }

    private static double parseD(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return 0; }
    }

    /** 2026-06-30 → 2026Q2 */
    static String toQuarter(String date) {
        if (date == null || date.length() < 7) return "2026Q1";
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        return y + "Q" + ((m - 1) / 3 + 1);
    }

    /** 发行名 → 美股 ticker（SEC 官方映射，尽力匹配；失败返回 null，落 CUSIP）。 */
    private static String resolveTicker(String issuer) {
        try {
            if (System.currentTimeMillis() - tickerMapAt > 24 * 3600_000L) {
                ObjectMapper om = new ObjectMapper();
                secPause();
                JsonNode root = om.readTree(HttpFetch.get(
                        "https://www.sec.gov/files/company_tickers.json", 30, "User-Agent: " + secUa()));
                Map<String, String> m = new HashMap<>();
                Map<String, String> ms = new HashMap<>();
                for (JsonNode it : root) {
                    String t = it.path("ticker").asText().toUpperCase();
                    String title = it.path("title").asText();
                    String k = nameKey(title);
                    if (!k.isEmpty() && !t.isEmpty()) {
                        m.putIfAbsent(k, t);
                        ms.putIfAbsent(sortKey(title), t);
                    }
                }
                tickerMap = m;
                tickerMapSorted = ms;
                tickerMapAt = System.currentTimeMillis();
            }
            String hit = tickerMap.get(nameKey(issuer));
            if (hit != null) return hit;
            // 逐个去掉尾部 token 再试（如 HOLDINGS / TECHNOLOGIES / ADVISORS 这类后缀差异）
            String[] toks = norm(issuer).split(" ");
            for (int cut = toks.length - 1; cut >= 2; cut--) {
                hit = tickerMap.get(nameKey(String.join(" ", Arrays.copyOfRange(toks, 0, cut))));
                if (hit != null) return hit;
            }
            // 词序不同（13F 的 D R HORTON 对 SEC 的 HORTON D R）：token 排序后兜底，短名不试以免误配
            String sk = sortKey(issuer);
            if (sk.length() >= 6) {
                hit = tickerMapSorted.get(sk);
                if (hit != null) return hit;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** cusip_ticker 表的内存缓存（CUSIP → ticker），导入时逐个查表，避免每行回表。 */
    private static volatile Map<String, String> cusipMap = Map.of();
    private static volatile long cusipMapAt = 0;

    private Map<String, String> cusipMap() {
        if (!cusipMap.isEmpty() && System.currentTimeMillis() - cusipMapAt < 300_000) return cusipMap;
        try {
            Map<String, String> m = new HashMap<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT cusip, ticker FROM cusip_ticker")) {
                m.put(str(r.get("cusip")), str(r.get("ticker")));
            }
            cusipMap = m;
            cusipMapAt = System.currentTimeMillis();
        } catch (Exception ignore) { /* 表还没建时退回按名称匹配 */ }
        return cusipMap;
    }

    /**
     * 13F 行 → 持仓代码：CUSIP 直查映射表最准，其次按发行人名称反查，都失败才退回 CUSIP。
     * 13F 本身只申报 CUSIP，名称匹配是不可靠的兜底（发行名会被截断到 28 字符）。
     */
    private String holdingCode(String issuer, String cusip) {
        String byCusip = cusipMap().get(cusip);
        if (byCusip != null && !byCusip.isEmpty()) return "us" + byCusip;
        String ticker = resolveTicker(issuer);
        if (ticker != null) return "us" + ticker;
        return cusip.isEmpty() ? issuer : cusip;
    }

    /** 13F 里发行名常用压缩写法，配 SEC 官方全称时需要展开。 */
    private static final Map<String, String> ABBR = Map.ofEntries(
            Map.entry("AMER", "AMERICA"), Map.entry("PETE", "PETROLEUM"),
            Map.entry("FINL", "FINANCIAL"), Map.entry("INTL", "INTERNATIONAL"),
            Map.entry("SVGS", "SAVINGS"), Map.entry("NATL", "NATIONAL"),
            Map.entry("RES", "RESOURCES"), Map.entry("SYS", "SYSTEMS"),
            Map.entry("SVCS", "SERVICES"), Map.entry("INDS", "INDUSTRIES"),
            Map.entry("MFG", "MANUFACTURING"), Map.entry("COMMUN", "COMMUNICATIONS"),
            Map.entry("ENTMT", "ENTERTAINMENT"), Map.entry("PHARMA", "PHARMACEUTICAL"),
            Map.entry("TECH", "TECHNOLOGY"), Map.entry("BANC", "BANCORP"),
            Map.entry("MTG", "MORTGAGE"), Map.entry("ELEC", "ELECTRIC"),
            Map.entry("GEN", "GENERAL"), Map.entry("MGMT", "MANAGEMENT"),
            Map.entry("PAC", "PACIFIC"),
            Map.entry("INDL", "INDUSTRIAL"), Map.entry("PRODS", "PRODUCTS"),
            Map.entry("TRANSP", "TRANSPORTATION"), Map.entry("MED", "MEDICAL"),
            // 13F 名称常把单词截断（APPLIED MATLS / TEXAS INSTRS / DIGITAL RLTY TR），
            // 展开后与 SEC 全称对齐；两侧都过同一套规则，所以对匹配是双边的，不会误配
            Map.entry("MATLS", "MATERIALS"), Map.entry("INSTRS", "INSTRUMENTS"),
            Map.entry("HLDNGS", "HOLDINGS"), Map.entry("HLDG", "HOLDING"),
            Map.entry("RLTY", "REALTY"), Map.entry("CTRS", "CENTERS"),
            Map.entry("CMNTYS", "COMMUNITIES"), Map.entry("CMNTY", "COMMUNITY"),
            Map.entry("BK", "BANK"), Map.entry("TR", "TRUST"),
            Map.entry("CHEMS", "CHEMICALS"), Map.entry("PHRMACEUTICALS", "PHARMACEUTICALS"));

    /**
     * 名称归一化键：在 norm() 基础上展开缩写、去掉全部空格再比对。
     * MOODY'S → "MOODYS" 与 MOODYS 对齐；BANK OF AMER → BANK OF AMERICA 对齐。
     */
    private static String nameKey(String s) {
        String n = norm(s);
        if (n.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : n.split(" ")) {
            String w = ABBR.getOrDefault(t, t);
            if (!w.isEmpty()) sb.append(w);
        }
        return sb.toString();
    }

    /** 词序无关键：token 展开缩写后排序拼接（D R HORTON 与 HORTON D R 视为同一家）。 */
    private static String sortKey(String s) {
        String n = norm(s);
        if (n.isEmpty()) return "";
        List<String> toks = new ArrayList<>();
        for (String t : n.split(" ")) {
            String w = ABBR.getOrDefault(t, t);
            if (!w.isEmpty()) toks.add(w);
        }
        java.util.Collections.sort(toks);
        return String.join("", toks);
    }

    private static String norm(String s) {
        String u = s == null ? "" : s.toUpperCase();
        // SEC 的名称常带注册州标记，写法还两种：BANK OF AMERICA CORP /DE/、VERISIGN INC/CA
        u = u.replaceAll("/[A-Z]{2,4}", " ");
        u = u.replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        List<String> toks = new ArrayList<>(Arrays.asList(u.split(" ")));
        List<String> suffix = List.of("INC", "CORP", "CORPORATION", "CO", "COMPANY", "LTD",
                "LIMITED", "LLC", "LP", "PLC", "COM", "INCORPORATED");
        while (!toks.isEmpty() && suffix.contains(toks.get(toks.size() - 1))) toks.remove(toks.size() - 1);
        return String.join(" ", toks);
    }

    // ---------------------------------------------------------------- util
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static long num(Object o) {
        try { return (long) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
