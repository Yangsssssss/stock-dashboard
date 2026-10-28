package com.quantboard.service;

import com.quantboard.feed.FeedService;
import com.quantboard.util.U;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交易流水：买入 / 卖出 / 分红 / 期初建仓，逐笔单独记录。
 * 持仓数量、移动加权成本、已实现盈亏、累计股息全部由流水聚合推导（流水是唯一真相）。
 */
@Service
public class TradeService {

    public static final Set<String> SIDES = Set.of("BUY", "SELL", "DIV", "INIT", "SPLIT");
    private static final String COLS =
            "id, broker, code, name, side, trade_date, qty, price, amount, fee, currency, note, created_at";

    private final JdbcTemplate jdbc;
    private final FeedService feed;

    public TradeService(JdbcTemplate jdbc, FeedService feed) {
        this.jdbc = jdbc;
        this.feed = feed;
    }

    /** 流水列表（可按代码 / 券商过滤），时间倒序。 */
    public List<Map<String, Object>> list(String code, String broker, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM trade WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (code != null && !code.isBlank()) { sql.append(" AND code=?"); args.add(code.trim()); }
        if (broker != null && !broker.isBlank()) { sql.append(" AND broker=?"); args.add(broker.trim()); }
        sql.append(" ORDER BY trade_date DESC, id DESC LIMIT ").append(Math.max(1, Math.min(limit, 1000)));
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("trade_date", String.valueOf(r.get("trade_date")));
        }
        return rows;
    }

    /**
     * 新增一笔流水。BUY/INIT 需 qty>0 与 price>=0；SELL 需 qty>0 与 price>0；DIV 只需 amount>0。
     * 现金净额 amount 由后端统一计算：买入 = qty*price + fee；卖出 = qty*price - fee；分红按传入 amount（税后到账）。
     */
    public Map<String, Object> add(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String side = String.valueOf(body.getOrDefault("side", "")).trim().toUpperCase();
        if (!SIDES.contains(side)) {
            out.put("ok", false);
            out.put("error", "side 必须是 BUY / SELL / DIV / INIT");
            return out;
        }
        String rawCode = String.valueOf(body.getOrDefault("code", "")).trim();
        String[] norm = PositionService.normalizeCode(rawCode);
        if (norm == null) {
            out.put("ok", false);
            out.put("error", "无法识别代码：" + rawCode);
            return out;
        }
        String code = norm[0];
        String currency = norm[1];
        String broker = String.valueOf(body.getOrDefault("broker", "")).trim();
        if (broker.isEmpty()) broker = "默认账户";

        double qty = U.num(body.get("qty"));
        double price = U.num(body.get("price"));
        double fee = U.num(body.get("fee"));
        double amountIn = U.num(body.get("amount"));
        if ("SPLIT".equals(side)) {
            // 送转股 / 拆股：只改股数，不动成本总额（成本自动摊薄），也没有现金进出
            if (qty == 0) {
                out.put("ok", false);
                out.put("error", "送转股需填股数：送股 / 拆股填正数，并股填负数");
                return out;
            }
        } else if (!"DIV".equals(side) && qty <= 0) {
            out.put("ok", false);
            out.put("error", "买入 / 卖出数量需大于 0");
            return out;
        }
        if ("DIV".equals(side) && amountIn <= 0) {
            out.put("ok", false);
            out.put("error", "分红金额（税后到账）需大于 0");
            return out;
        }
        double amount = switch (side) {
            case "BUY", "INIT" -> U.round(qty * price + fee, 2);
            case "SELL" -> U.round(qty * price - fee, 2);
            case "SPLIT" -> 0;                       // 无现金流：不影响净投入与收益口径
            default -> U.round(amountIn, 2);
        };

        LocalDate date;
        try {
            Object d = body.get("trade_date");
            date = d == null || String.valueOf(d).isBlank() ? LocalDate.now() : LocalDate.parse(String.valueOf(d).substring(0, 10));
        } catch (Exception e) {
            date = LocalDate.now();
        }

        // 行情补全名称与币种
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        Map<String, Map<String, Object>> quotes = feed.fetchQuotes(List.of(code));
        Map<String, Object> q = quotes.get(code);
        if (q == null && code.matches("usUS[A-Z]{2,5}")) {
            String alt = "us" + code.substring(4);
            Map<String, Object> qa = feed.fetchQuotes(List.of(alt)).get(alt);
            if (qa != null) { code = alt; q = qa; }
        }
        if (q != null) {
            currency = q.get("currency") != null ? String.valueOf(q.get("currency")) : currency;
            String qn = q.get("name") != null ? String.valueOf(q.get("name")) : "";
            if (!qn.isEmpty()) name = qn;
        }
        if (name.isEmpty()) name = code;

        String note = String.valueOf(body.getOrDefault("note", "")).trim();
        try {
            jdbc.update("INSERT INTO trade (broker, code, name, side, trade_date, qty, price, amount, fee, currency, note) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    broker, code, name, side, date, qty, price, amount, fee, currency, note);
            out.put("ok", true);
            out.put("code", code);
            out.put("name", name);
            out.put("currency", currency);
            out.put("amount", amount);
            out.put("quote_ok", q != null);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 删除单笔流水。 */
    public boolean remove(long id) {
        try { return jdbc.update("DELETE FROM trade WHERE id=?", id) > 0; }
        catch (Exception e) { return false; }
    }

    /** 删除某券商某标的的全部流水（含已实现与分红记录，慎用）。 */
    public int removePosition(String broker, String code) {
        try { return jdbc.update("DELETE FROM trade WHERE broker=? AND code=?", broker, code); }
        catch (Exception e) { return 0; }
    }

    /** 单只标的的聚合结果。 */
    public static final class Pos {
        public String broker = "";
        public String code = "";
        public String name = "";
        public String currency = "";
        public double qty;          // 当前持仓数量
        public double costTotal;    // 当前持仓成本总额（含买入费用）
        public double realized;     // 已实现盈亏（已扣卖出费用）
        public double divTotal;     // 累计股息（税后）
        public double divTtm;       // 近 12 个月股息
        public int trades;          // 流水笔数

        public boolean closed() { return qty <= 1e-9; }

        public double avgCost() { return qty > 0 ? costTotal / qty : 0; }
    }

    /** 全部流水的聚合结果，key = broker|code，按券商与首次出现顺序排列。 */
    public Map<String, Pos> aggregate() {
        Map<String, Pos> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT broker, code, name, side, trade_date, qty, price, amount, fee, currency "
                    + "FROM trade ORDER BY broker, code, trade_date, id");
        } catch (Exception e) {
            return out;
        }
        LocalDate ttmStart = LocalDate.now().minusDays(365);
        for (Map<String, Object> r : rows) {
            String broker = String.valueOf(r.get("broker"));
            String code = String.valueOf(r.get("code"));
            Pos p = out.computeIfAbsent(broker + "|" + code, k -> {
                Pos np = new Pos();
                np.broker = broker;
                np.code = code;
                return np;
            });
            String nm = String.valueOf(r.get("name"));
            if (!nm.isEmpty()) p.name = nm;
            String cur = String.valueOf(r.get("currency"));
            if (!cur.isEmpty()) p.currency = cur;

            String side = String.valueOf(r.get("side"));
            double qty = U.num(r.get("qty"));
            double price = U.num(r.get("price"));
            double fee = U.num(r.get("fee"));
            double amount = U.num(r.get("amount"));
            p.trades++;

            switch (side) {
                case "BUY", "INIT" -> {
                    if (qty > 0) {
                        p.qty += qty;
                        // 成本一律以 amount（真实现金流出）为准：手动流水 amount=qty*price+fee 两者恒等，
                        // 导入流水可能只有 amount 可信（price 误写为市价），以 amount 兜底防止口径分裂。
                        double buyCost = amount > 0 ? amount : qty * price + fee;
                        p.costTotal += buyCost;
                    }
                }
                case "SELL" -> {
                    double sold = Math.min(qty, p.qty);
                    if (sold > 0) {
                        double avg = p.qty > 0 ? p.costTotal / p.qty : 0;
                        p.realized += sold * (price - avg) - fee;
                        p.costTotal -= avg * sold;
                        p.qty -= sold;
                    }
                    if (p.qty < 1e-9) { p.qty = 0; p.costTotal = 0; }
                }
                case "DIV" -> {
                    p.divTotal += amount;
                    try {
                        String d = String.valueOf(r.get("trade_date"));
                        if (!LocalDate.parse(d.substring(0, 10)).isBefore(ttmStart)) p.divTtm += amount;
                    } catch (Exception ignore) { p.divTtm += amount; }
                }
                // 送转股 / 拆股：只调股数，成本总额原样保留 —— 每股成本因此被摊薄，
                // 后续卖出时按新的平均成本结算已实现盈亏，等于把送股收益如实计入。
                case "SPLIT" -> {
                    p.qty += qty;
                    if (p.qty < 1e-9) { p.qty = 0; p.costTotal = 0; }
                }
                default -> { }
            }
        }
        return out;
    }
}
