package com.quantboard.service;

import com.quantboard.feed.FeedService;
import com.quantboard.util.U;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 收益引擎：全站收益口径的唯一出处。
 *
 * 三条恒等式（必须保持，任何改动都要回归验证）：
 *   1) 浮盈 + 已实现 = 市值 − 净投入
 *   2) 总收益 = 浮盈 + 已实现 + 股息 = 市值 − 净投入 + 股息
 *   3) 净投入 = Σ买入含费 − Σ卖出净额（股息不冲减投入，它是收益）
 *
 * 净投入口径的好处：追加资金或赎回时，总收益不会跳变，所以「区间收益 = 期末总收益 − 期初总收益」
 * 天然剔除出入金影响，不需要额外的 TWR 处理。
 */
@Service
public class PerformanceService {

    private final JdbcTemplate jdbc;
    private final TradeService trades;
    private final FeedService feed;
    private final FxService fx;
    private final String defaultBenchmark;
    private final String defaultBenchmarkName;

    public PerformanceService(JdbcTemplate jdbc, TradeService trades, FeedService feed, FxService fx,
                              @Value("${quantboard.benchmark:usINX}") String defaultBenchmark,
                              @Value("${quantboard.benchmark-name:标普500}") String defaultBenchmarkName) {
        this.jdbc = jdbc;
        this.trades = trades;
        this.feed = feed;
        this.fx = fx;
        this.defaultBenchmark = defaultBenchmark;
        this.defaultBenchmarkName = defaultBenchmarkName;
    }

    // ---------------------------------------------------------------- 汇率
    /** 汇率口径：交给 FxService（按日入库，带取数日期）；这里只做币种分派。 */
    public double fxOf(String currency) {
        return fx.rate(currency);
    }

    public Map<String, Object> fxMap() {
        return fx.view();
    }

    // ---------------------------------------------------------------- 持仓估值
    /** 一只标的的估值视图（含已清仓）。 */
    public static final class Val {
        public String broker = "";
        public String code = "";
        public String name = "";
        public String currency = "CNY";
        public double qty;
        public double costTotal;   // 剩余持仓成本
        public double price;
        public double pct;
        public double mv;          // 市值（原币）
        public double realized;
        public double div;
        public boolean closed;

        public double unrealized() { return closed ? 0 : mv - costTotal; }
        public double totalPl() { return unrealized() + realized + div; }
    }

    /** 由流水 + 实时行情推导的全部标的估值。行情走 FeedService 缓存，多次调用不重复请求。 */
    public List<Val> values() {
        Map<String, TradeService.Pos> agg = trades.aggregate();
        List<String> codes = agg.values().stream().filter(p -> !p.closed()).map(p -> p.code).distinct().toList();
        Map<String, Map<String, Object>> quotes = codes.isEmpty() ? Map.of() : feed.fetchQuotes(codes);
        List<Val> out = new ArrayList<>();
        for (TradeService.Pos p : agg.values()) {
            Val v = new Val();
            v.broker = p.broker;
            v.code = p.code;
            v.name = p.name;
            v.currency = p.currency.isEmpty() ? "CNY" : p.currency;
            v.qty = p.qty;
            v.costTotal = p.costTotal;
            v.realized = p.realized;
            v.div = p.divTotal;
            v.closed = p.closed();
            Map<String, Object> q = quotes.get(p.code);
            if (q != null) {
                if (q.get("currency") != null && !String.valueOf(q.get("currency")).isEmpty()) {
                    v.currency = String.valueOf(q.get("currency"));
                }
                if (q.get("name") != null && !String.valueOf(q.get("name")).isEmpty()) {
                    v.name = String.valueOf(q.get("name"));
                }
                v.price = U.num(q.get("price"));
                v.pct = U.num(q.get("pct"));
            }
            v.mv = v.closed ? 0 : v.qty * v.price;
            out.add(v);
        }
        return out;
    }

    // ---------------------------------------------------------------- 汇总
    /** 净投入（折人民币）：Σ买入含费 − Σ卖出净额。必须逐币种折算后再相加，不能直接汇总 amount。 */
    public double investedCny() {
        double total = 0;
        for (var e : investedByCurrency().entrySet()) total += e.getValue() * fxOf(e.getKey());
        return U.round(total, 2);
    }

    /** 净投入（原币，按币种）。 */
    private Map<String, Double> investedByCurrency() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT currency, SUM(CASE WHEN side IN ('BUY','INIT') THEN amount "
                        + "WHEN side='SELL' THEN -amount ELSE 0 END) AS amt FROM trade GROUP BY currency")) {
            out.put(String.valueOf(r.get("currency")), U.num(r.get("amt")));
        }
        return out;
    }

    /**
     * 组合总览（统一人民币口径）。
     * 调用方若已经算过 values()（例如 report() 一次请求内要多处复用），务必传 vals 进来；
     * 否则每次调用都会重新拉一次实时行情，7 次就是 7 个 HTTP 往返。
     */
    public Map<String, Object> summary() {
        return summary(values());
    }

    /** @param vals 外部已算好的估值；传 null 表示内部现算。 */
    public Map<String, Object> summary(List<Val> vals) {
        if (vals == null) vals = values();
        double mv = 0, cost = 0, realized = 0, div = 0;
        Map<String, double[]> byCur = new LinkedHashMap<>();   // currency → [mv, cost, realized, div]
        for (Val v : vals) {
            double fx = fxOf(v.currency);
            mv += v.mv * fx;
            cost += v.costTotal * fx;
            realized += v.realized * fx;
            div += v.div * fx;
            double[] a = byCur.computeIfAbsent(v.currency, k -> new double[4]);
            a[0] += v.mv; a[1] += v.costTotal; a[2] += v.realized; a[3] += v.div;
        }
        double invested = investedCny();
        double totalPl = mv - invested + div;
        double simpleRet = invested > 0 ? totalPl / invested * 100 : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mv", U.round(mv, 2));
        out.put("cost", U.round(cost, 2));
        out.put("unrealized", U.round(mv - cost, 2));
        out.put("realized", U.round(realized, 2));
        out.put("div", U.round(div, 2));
        out.put("invested", U.round(invested, 2));
        out.put("total_pl", U.round(totalPl, 2));
        out.put("simple_ret", U.round(simpleRet, 2));
        Map<String, Object> mw = moneyWeighted(vals);
        out.put("mw", mw);
        out.put("mw_annual", mw.get("annual"));
        out.put("fx", fxMap());
        out.put("holdings", vals.stream().filter(v -> !v.closed).count());
        out.put("closed", vals.stream().filter(v -> v.closed).count());
        List<Map<String, Object>> cur = new ArrayList<>();
        for (var e : byCur.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", e.getKey());
            m.put("mv", U.round(e.getValue()[0], 2));
            m.put("mv_cny", U.round(e.getValue()[0] * fxOf(e.getKey()), 2));
            m.put("cost", U.round(e.getValue()[1], 2));
            m.put("realized", U.round(e.getValue()[2], 2));
            m.put("div", U.round(e.getValue()[3], 2));
            m.put("share", mv > 0 ? U.round(e.getValue()[0] * fxOf(e.getKey()) / mv * 100, 2) : 0);
            cur.add(m);
        }
        out.put("by_currency", cur);
        return out;
    }

    /** 按标的的收益贡献（折人民币），倒序。 */
    public List<Map<String, Object>> contributors() {
        return contributors(values());
    }

    private List<Map<String, Object>> contributors(List<Val> vals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Val v : vals) {
            double fx = fxOf(v.currency);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("broker", v.broker);
            m.put("code", v.code);
            m.put("name", v.name);
            m.put("currency", v.currency);
            m.put("qty", U.round(v.qty, 4));
            m.put("cost", U.round(v.costTotal, 2));
            m.put("mv", U.round(v.mv, 2));
            m.put("unrealized_cny", U.round(v.unrealized() * fx, 2));
            m.put("realized_cny", U.round(v.realized * fx, 2));
            m.put("div_cny", U.round(v.div * fx, 2));
            m.put("total_cny", U.round(v.totalPl() * fx, 2));
            m.put("closed", v.closed);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare(U.num(b.get("total_cny")), U.num(a.get("total_cny"))));
        return out;
    }

    // ---------------------------------------------------------------- 资金加权收益
    /**
     * 资金加权收益率（Modified Dietz）：按每笔资金的实际占用天数加权，而不是按日历平均。
     * 相比 XIRR 的优势：资金几乎全部在最近才投入时，XIRR 的同日反向现金流会解出 −90% 这种
     * 无经济含义的根；Modified Dietz 直接以「资金占用天数」为权重，分母趋零时如实报告数据不足。
     *
     * 口径：买入/期初 = 资金流入组合（正），卖出 = 资金流出（负），股息视为留在组合内不单独计流量；
     *      V_start = 0（组合从空仓开始），V_end = 当前市值。
     */
    public Map<String, Object> moneyWeighted() {
        return moneyWeighted(values());
    }

    private Map<String, Object> moneyWeighted(List<Val> vals) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT trade_date, side, amount, currency FROM trade WHERE amount>0 "
                    + "ORDER BY trade_date");
        } catch (Exception e) {
            out.put("ok", false);
            return out;
        }
        LocalDate today = LocalDate.now();
        TreeMap<LocalDate, Double> flows = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            LocalDate d;
            try { d = LocalDate.parse(String.valueOf(r.get("trade_date")).substring(0, 10)); }
            catch (Exception e) { continue; }
            String side = String.valueOf(r.get("side"));
            double amt = U.num(r.get("amount")) * fxOf(String.valueOf(r.get("currency")));
            double signed = switch (side) {
                case "BUY", "INIT" -> amt;      // 投入
                case "SELL" -> -amt;            // 收回
                default -> 0;                   // 股息留在组合内
            };
            if (signed == 0) continue;
            flows.merge(d, signed, Double::sum);
        }
        if (flows.isEmpty()) {
            out.put("ok", false);
            out.put("note", "还没有资金投入记录");
            return out;
        }
        LocalDate first = flows.firstKey();
        int totalDays = (int) ChronoUnit.DAYS.between(first, today);
        double netFlow = flows.values().stream().mapToDouble(Double::doubleValue).sum();
        double mv = 0;
        for (Val v : vals) mv += v.mv * fxOf(v.currency);
        double gain = mv - netFlow;

        double weighted = 0;   // Σ 资金 × 占用天数
        for (var e : flows.entrySet()) {
            int held = (int) ChronoUnit.DAYS.between(e.getKey(), today);
            if (held <= 0) continue;                 // 当日投入不产生占用
            weighted += e.getValue() * held;
        }
        double capitalDays = netFlow > 0 ? weighted / netFlow : 0;   // 加权平均占用天数

        out.put("ok", true);
        out.put("gain", U.round(gain, 2));
        out.put("net_flow", U.round(netFlow, 2));
        out.put("days", totalDays);
        out.put("capital_days", U.round(capitalDays, 1));
        // 门槛：资金加权占用不足 30 天，或平均占用资本远小于当前市值（说明大部分钱是刚进的），
        // 此时加权口径会被新入金稀释，算出来的年化没有参考意义，宁可不给。
        double coverage = mv > 0 ? (capitalDays > 0 ? (weighted / Math.max(1, totalDays)) / mv : 0) : 0;
        if (totalDays < 30 || capitalDays < 30 || coverage < 0.5) {
            out.put("rate", U.round(netFlow > 0 ? gain / netFlow * 100 : 0, 2));
            out.put("annual", null);
            out.put("note", "大部分资金投入不足 30 天，暂不年化（加权口径会被新入金稀释）");
            return out;
        }
        // Modified Dietz：分母 = Σ(资金 × 占用天数) / 期间总天数 = 期间内的平均占用资金
        double avgCapital = weighted / Math.max(1, totalDays);
        if (avgCapital <= 0) {
            out.put("rate", 0.0);
            out.put("annual", null);
            out.put("note", "期间内无资金占用，无法计算加权收益率");
            return out;
        }
        double rate = gain / avgCapital;
        out.put("avg_capital", U.round(avgCapital, 2));
        out.put("rate", U.round(rate * 100, 2));
        double annual = Math.pow(1 + rate, 365.0 / totalDays) - 1;
        out.put("annual", U.round(annual * 100, 2));
        out.put("note", "");
        return out;
    }

    // ---------------------------------------------------------------- 每日快照
    /** 写入当日真实快照（幂等），返回总览。 */
    public Map<String, Object> snapshot() {
        return snapshot(summary(values()));
    }

    private Map<String, Object> snapshot(Map<String, Object> s) {
        LocalDate d = LocalDate.now();
        try {
            jdbc.update("INSERT INTO nav_daily (trade_date, mv, cost, realized, div_total, invested, total_pl, estimated) "
                            + "VALUES (?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE mv=VALUES(mv), cost=VALUES(cost), "
                            + "realized=VALUES(realized), div_total=VALUES(div_total), invested=VALUES(invested), "
                            + "total_pl=VALUES(total_pl), estimated=0",
                    d, U.num(s.get("mv")), U.num(s.get("cost")), U.num(s.get("realized")), U.num(s.get("div")),
                    U.num(s.get("invested")), U.num(s.get("total_pl")));
        } catch (Exception ignore) { }
        return s;
    }

    /**
     * 历史曲线。窗口内缺失的日期用 K 线回溯估算补齐（按当前持仓、当前汇率、当前股息），
     * 标记 estimated=1；当日真实快照 estimate=0，会覆盖估算值。
     */
    public List<Map<String, Object>> history(int days) {
        return history(days, values());
    }

    private List<Map<String, Object>> history(int days, List<Val> vals) {
        int n = Math.max(7, Math.min(days, 730));
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(n);
        Map<LocalDate, Map<String, Object>> have = new TreeMap<>();
        LocalDate first = firstTradeDate();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT trade_date, mv, cost, realized, div_total, invested, total_pl, estimated FROM nav_daily "
                            + "WHERE trade_date>=?", start)) {
                LocalDate d = LocalDate.parse(String.valueOf(r.get("trade_date")).substring(0, 10));
                if (d.isBefore(first)) continue;   // 建仓前没有组合，残留的旧估算点不返回
                have.put(d, r);
            }
        } catch (Exception e) {
            return List.of();
        }
        backfill(start, today, have, vals);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : have.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey().toString());
            m.put("mv", U.round(U.num(e.getValue().get("mv")), 2));
            m.put("invested", U.round(U.num(e.getValue().get("invested")), 2));
            m.put("total_pl", U.round(U.num(e.getValue().get("total_pl")), 2));
            m.put("estimated", truthy(e.getValue().get("estimated")));
            double inv = U.num(e.getValue().get("invested"));
            m.put("ret", inv > 0 ? U.round(U.num(e.getValue().get("total_pl")) / inv * 100, 2) : 0);
            out.add(m);
        }
        return out;
    }

    /**
     * 用 K 线回溯补齐窗口内的缺失日期。规则：
     * 0) 只回溯到建仓日（首笔流水日）为止——之前的日期不存在这个组合，补点全是假数据；
     * 1) 某只标的 K 线覆盖不足（少于 5 根 或 起点前无数据）时，用当前价水平外推，不剔除标的，
     *    否则曲线会在该标的数据起点处出现假跳变；
     * 2) 非交易日（所有持仓当日都没有 K 线）不补点；
     * 3) 某标的当日停牌时按最近一个收盘价前值填充。
     */
    private void backfill(LocalDate start, LocalDate today, Map<LocalDate, Map<String, Object>> have,
                          List<Val> all) {
        if (all == null) all = values();
        List<Val> vals = all.stream().filter(v -> !v.closed && v.qty > 0 && v.price > 0).toList();
        if (vals.isEmpty()) return;
        // 建仓前的日期不回溯：当前持仓 × 历史价格在那时不是真实市值
        LocalDate first = firstTradeDate();
        LocalDate from = start.isBefore(first) ? first : start;
        boolean missing = false;
        for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
            if (!have.containsKey(d) && d.getDayOfWeek().getValue() <= 5) { missing = true; break; }
        }
        if (!missing) return;

        Map<String, TreeMap<LocalDate, Double>> series = new HashMap<>();
        List<String> flat = new ArrayList<>();
        for (Val v : vals) {
            TreeMap<LocalDate, Double> m = new TreeMap<>();
            try {
                for (Object[] k : feed.fetchKline(v.code, 520)) {
                    LocalDate d = LocalDate.parse(String.valueOf(k[0]).substring(0, 10));
                    double c = U.num(k[2]);
                    if (c > 0) m.put(d, c);
                }
            } catch (Exception ignore) { }
            if (m.size() < 5 || m.firstKey().isAfter(start)) flat.add(v.code);
            series.put(v.code, m);
        }

        Map<String, Object> sum;
        try { sum = summary(vals); } catch (Exception e) { return; }
        double invested = investedCny();
        double div = U.num(sum.get("div"));
        double realized = U.num(sum.get("realized"));
        double costCny = U.num(sum.get("cost"));

        List<Object[]> rows = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
            if (have.containsKey(d)) continue;
            boolean trading = false;
            for (Val v : vals) {
                if (!flat.contains(v.code) && series.getOrDefault(v.code, new TreeMap<>()).containsKey(d)) {
                    trading = true;
                    break;
                }
            }
            if (!trading) continue;   // 非交易日
            double mvCny = 0;
            for (Val v : vals) {
                double c;
                if (flat.contains(v.code)) {
                    c = v.price;                       // 覆盖不足：按当前价水平外推
                } else {
                    var e = series.getOrDefault(v.code, new TreeMap<>()).floorEntry(d);
                    c = e != null ? e.getValue() : v.price;
                }
                mvCny += v.qty * c * fxOf(v.currency);
            }
            double pl = mvCny - invested + div;
            rows.add(new Object[]{d, U.round(mvCny, 2), U.round(costCny, 2), U.round(realized, 2),
                    U.round(div, 2), U.round(invested, 2), U.round(pl, 2)});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mv", mvCny); m.put("invested", invested); m.put("total_pl", pl);
            m.put("cost", costCny); m.put("realized", realized); m.put("div_total", div); m.put("estimated", 1);
            have.put(d, m);
        }
        if (!rows.isEmpty()) {
            try {
                // 批量写：缺失点可能有几百行，逐行 update 会产生几百次网络往返
                jdbc.batchUpdate("INSERT IGNORE INTO nav_daily (trade_date, mv, cost, realized, div_total, invested, total_pl, estimated) "
                        + "VALUES (?,?,?,?,?,?,?,1)", rows);
            } catch (Exception ignore) { }
        }
    }

    // ---------------------------------------------------------------- 区间收益
    /** 区间收益：总收益的增量天然剔除出入金。累计一栏用流水口径，不依赖快照。 */
    public List<Map<String, Object>> intervals(int days) {
        return intervals(days, summary(values()), history(days, values()));
    }

    private List<Map<String, Object>> intervals(int days, Map<String, Object> summary,
                                                List<Map<String, Object>> hist) {
        Map<String, Object> s = summary;
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        double lastPl = hist.isEmpty() ? U.num(s.get("total_pl")) : U.num(hist.get(hist.size() - 1).get("total_pl"));

        out.add(todayPeriod(hist, lastPl, s));
        out.add(period("近 1 周", hist, today, lastPl, 7, s));
        out.add(period("近 1 月", hist, today, lastPl, 30, s));
        out.add(period("近 3 月", hist, today, lastPl, 90, s));
        out.add(period("近 6 月", hist, today, lastPl, 180, s));
        int doy = today.getDayOfYear();
        out.add(period("今年", hist, today, lastPl, doy - 1, s));
        out.add(period("近 1 年", hist, today, lastPl, 365, s));

        Map<String, Object> all = new LinkedHashMap<>();
        all.put("label", "累计");
        all.put("pl", U.round(U.num(s.get("total_pl")), 2));
        all.put("ret", U.round(U.num(s.get("simple_ret")), 2));
        all.put("days", today.toEpochDay() - firstTradeDate().toEpochDay());
        all.put("estimated", false);
        out.add(all);
        return out;
    }

    /** TINYINT 经 JDBC 可能返回 Boolean / 数字 / 字符串，统一判真。 */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() > 0;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("1") || s.equals("true");
    }

    /** 组合的建仓日 = 首笔买入 / 期初流水（DIV 不算——建仓前手动补记的股息不代表当时有持仓）。 */
    private LocalDate firstTradeDate() {
        try {
            java.sql.Date d = jdbc.queryForObject(
                    "SELECT MIN(trade_date) FROM trade WHERE side IN ('BUY','INIT')", java.sql.Date.class);
            if (d != null) return d.toLocalDate();
        } catch (Exception ignore) { }
        return LocalDate.now();
    }

    /** 今日：与上一个交易日比较（不能拿今天和今天比）。 */
    private Map<String, Object> todayPeriod(List<Map<String, Object>> hist, double lastPl, Map<String, Object> s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", "今日");
        if (hist.size() < 2) {
            m.put("pl", 0.0);
            m.put("ret", 0.0);
            m.put("days", 0);
            m.put("estimated", false);
            return m;
        }
        Map<String, Object> base = hist.get(hist.size() - 2);
        double pl = lastPl - U.num(base.get("total_pl"));
        double inv = U.num(base.get("invested"));
        m.put("pl", U.round(pl, 2));
        m.put("ret", inv > 0 ? U.round(pl / inv * 100, 3) : 0);
        m.put("days", 0);
        m.put("estimated", truthy(base.get("estimated")));
        return m;
    }

    private Map<String, Object> period(String label, List<Map<String, Object>> hist, LocalDate today,
                                       double lastPl, int agoDays, Map<String, Object> s) {
        LocalDate target = today.minusDays(agoDays);
        Map<String, Object> base = null;
        for (Map<String, Object> h : hist) {
            LocalDate d = LocalDate.parse(String.valueOf(h.get("date")));
            if (!d.isAfter(target)) base = h;
            else break;
        }
        double pl;
        double ret;
        boolean estimated;
        if (base == null) {
            pl = lastPl;
            ret = U.num(s.get("simple_ret"));
            estimated = true;
        } else {
            double startPl = U.num(base.get("total_pl"));
            pl = lastPl - startPl;
            double inv = U.num(base.get("invested"));
            ret = inv > 0 ? pl / inv * 100 : 0;
            estimated = Boolean.TRUE.equals(base.get("estimated"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("pl", U.round(pl, 2));
        m.put("ret", U.round(ret, 2));
        m.put("days", agoDays);
        m.put("estimated", estimated);
        return m;
    }

    /** 最大回撤：以净值（净投入 + 总收益，即市值 + 累计股息）为基准，不能用收益额，否则早期收益额小会放大回撤。 */
    public Map<String, Object> drawdown(int days) {
        return drawdown(history(days, values()));
    }

    private Map<String, Object> drawdown(List<Map<String, Object>> hist) {
        double peak = Double.NEGATIVE_INFINITY, maxDd = 0;
        String peakDate = null, troughDate = null, curPeakDate = null;
        for (Map<String, Object> h : hist) {
            double equity = U.num(h.get("invested")) + U.num(h.get("total_pl"));
            if (equity <= 0) continue;
            if (equity > peak) { peak = equity; curPeakDate = String.valueOf(h.get("date")); }
            double dd = (equity - peak) / peak * 100;
            if (dd < maxDd) { maxDd = dd; peakDate = curPeakDate; troughDate = String.valueOf(h.get("date")); }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_dd", U.round(maxDd, 2));
        m.put("peak_date", peakDate);
        m.put("trough_date", troughDate);
        return m;
    }

    // ---------------------------------------------------------------- 基准对比
    /**
     * 组合 vs 基准：两条曲线都按「时间加权」处理，同以 100 起步，才具备可比性。
     *
     * 组合侧用日度 TWR：r_t = (equity_t − flow_t) / equity_{t−1} − 1，
     * 其中 equity = 净投入 + 总收益，flow = 净投入的日增量（追加资金不算业绩）。
     * 直接拿「总收益 ÷ 净投入」去和指数比是错的：中途加仓会把分母抬高、收益率被稀释。
     *
     * nav_daily 里 estimated=1 的点是 K 线回溯来的，仍参与计算（否则曲线会有缺口），
     * 由上层在图上标注。
     */
    public Map<String, Object> benchmark(List<Map<String, Object>> hist, String code) {
        String bmCode = code == null || code.isBlank() ? defaultBenchmark : code.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", bmCode);
        out.put("name", bmCode.equalsIgnoreCase(defaultBenchmark) ? defaultBenchmarkName : bmCode.toUpperCase());
        // 基准自身的计价币种：组合净值是人民币，美股/港股基准与组合同币种才严格可比
        String ccy = bmCode.startsWith("us") ? "USD" : bmCode.startsWith("hk") ? "HKD" : "CNY";
        out.put("ccy", ccy);
        out.put("rows", List.of());
        if (hist == null || hist.size() < 2) {
            out.put("ready", false);
            out.put("note", "历史点不足两个，先积累几天快照");
            return out;
        }

        // 1) 组合时间加权指数
        List<Double> port = new ArrayList<>();
        double idx = 100;
        port.add(idx);
        for (int i = 1; i < hist.size(); i++) {
            double eqPrev = U.num(hist.get(i - 1).get("invested")) + U.num(hist.get(i - 1).get("total_pl"));
            double eqNow = U.num(hist.get(i).get("invested")) + U.num(hist.get(i).get("total_pl"));
            double flow = U.num(hist.get(i).get("invested")) - U.num(hist.get(i - 1).get("invested"));
            if (eqPrev > 0) {
                double r = (eqNow - flow) / eqPrev - 1;
                // 快照断层（停机多日）会产生 ±100% 这类脏日收益，跳过而不是让它污染整条曲线
                if (r > -0.5 && r < 1.0) idx *= (1 + r);
            }
            port.add(idx);
        }

        // 2) 基准收盘价，按组合的日期取「向前最近一个交易日」的值（应对港美股休市不一致）
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        try {
            for (Object[] k : feed.fetchKline(bmCode, 700)) {
                try {
                    double c = U.num(k[2]);
                    if (c > 0) closes.put(LocalDate.parse(String.valueOf(k[0]).substring(0, 10)), c);
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
        if (closes.isEmpty()) {
            out.put("ready", false);
            out.put("note", "基准 " + bmCode + " 的 K 线取不到，暂时无法对比");
            return out;
        }
        Double base = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < hist.size(); i++) {
            LocalDate d;
            try { d = LocalDate.parse(String.valueOf(hist.get(i).get("date"))); }
            catch (Exception e) { continue; }
            var e = closes.floorEntry(d);
            if (e == null) continue;
            if (base == null) base = e.getValue();
            double bmIdx = base > 0 ? e.getValue() / base * 100 : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("port", U.round(port.get(i), 2));
            row.put("bm", U.round(bmIdx, 2));
            row.put("estimated", truthy(hist.get(i).get("estimated")));
            rows.add(row);
        }
        if (rows.size() < 2) {
            out.put("ready", false);
            out.put("note", "组合与基准没有重叠的交易日");
            return out;
        }
        double portRet = U.num(rows.get(rows.size() - 1).get("port")) - 100;
        double bmRet = U.num(rows.get(rows.size() - 1).get("bm")) - 100;
        out.put("ready", true);
        out.put("rows", rows);
        out.put("port_ret", U.round(portRet, 2));
        out.put("bm_ret", U.round(bmRet, 2));
        out.put("excess", U.round(portRet - bmRet, 2));
        out.put("days", rows.size());
        out.put("note", "");
        return out;
    }

    /**
     * 收益分析页整包。
     * 关键：整份报告只算一次 values()（内部一次实时行情请求），其余部件全部复用。
     * 早先各部件各自调 values()，一次 report 要打 7 次腾讯行情，页面因此卡 ~2.5s。
     */
    public Map<String, Object> report(int days) {
        return report(days, null);
    }

    public Map<String, Object> report(int days, String benchmarkCode) {
        List<Val> vals = values();
        Map<String, Object> summary = summary(vals);
        snapshot(summary);                       // 先落当日快照，history 才读得到今天的点
        List<Map<String, Object>> hist = history(days, vals);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("intervals", intervals(days, summary, hist));
        out.put("history", hist);
        out.put("contributors", contributors(vals));
        out.put("drawdown", drawdown(hist));
        out.put("benchmark", benchmark(hist, benchmarkCode));
        return out;
    }

    /** 供 PositionService 复用：按 items 汇总出券商合计（带人民币折算与占比）。 */
    public Map<String, Object> totalsOf(String broker, List<Map<String, Object>> items, double grandMvCny) {
        Map<String, double[]> tot = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String cur = String.valueOf(it.get("currency"));
            double mv = U.num(it.get("mv"));
            double ct = U.num(it.get("cost_total"));
            double dp = U.num(it.get("day_pl"));
            double[] t = tot.computeIfAbsent(cur, k -> new double[3]);
            t[0] += mv; t[1] += ct; t[2] += dp;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        double cny = 0;
        for (var e : tot.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            double fx = fxOf(e.getKey());
            m.put("currency", e.getKey());
            m.put("mv", U.round(e.getValue()[0], 2));
            m.put("cost", U.round(e.getValue()[1], 2));
            m.put("pl", U.round(e.getValue()[0] - e.getValue()[1], 2));
            m.put("pl_pct", e.getValue()[1] > 0 ? U.round(U.pct(e.getValue()[0], e.getValue()[1]), 2) : 0);
            m.put("day_pl", U.round(e.getValue()[2], 2));
            m.put("fx", fx);
            m.put("mv_cny", U.round(e.getValue()[0] * fx, 2));
            list.add(m);
            cny += e.getValue()[0] * fx;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("broker", broker);
        out.put("totals", list);
        out.put("mv_cny", U.round(cny, 2));
        out.put("share", grandMvCny > 0 ? U.round(cny / grandMvCny * 100, 2) : 0);
        return out;
    }
}
