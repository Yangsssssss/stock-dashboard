package com.quantboard.service;

import com.quantboard.feed.Universe;
import com.quantboard.util.U;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import java.time.LocalDate;

/**
 * 家庭资产管理：证券市值由 trade 流水 + 实时行情推导（复用收益引擎的估值与汇率），
 * 非证券科目（现金 / 存款 / 场外基金 / 其他 / 负债）存 family_asset 台账。
 * 原则：这里只做汇总视图与非证券台账，证券部分不重复记账。
 */
@Service
public class FamilyService {

    private final JdbcTemplate jdbc;
    private final PerformanceService perf;

    // ---------------------------------------------------------------- 证券估值快照（SWR）
    // /api/family 被前端每 25s 拉一次，而行情 TTL 只有 15s —— 几乎每次都要等腾讯那 0.3~2s。
    // 证券估值走 stale-while-revalidate：过期先给旧快照，后台线程补新值；台账/收支等 DB 部分照旧每次现算。
    private static final long SEC_TTL_MS = 15_000;

    private static final class SecSnap {
        final Map<String, Double> sec;
        final List<Map<String, Object>> holdings;
        final long ts;

        SecSnap(Map<String, Double> sec, List<Map<String, Object>> holdings, long ts) {
            this.sec = sec;
            this.holdings = holdings;
            this.ts = ts;
        }
    }

    private volatile SecSnap secSnap;
    private final Object secLock = new Object();
    private final AtomicBoolean secBusy = new AtomicBoolean(false);
    private final ExecutorService secPool = Executors.newSingleThreadExecutor((r) -> {
        Thread t = new Thread(r, "fam-sec-refresh");
        t.setDaemon(true);
        return t;
    });

    /** 五大类 bucket → 展示名。 */
    public static final Map<String, String> BUCKETS = new LinkedHashMap<>();
    static {
        BUCKETS.put("a", "A 股权益");
        BUCKETS.put("hk", "港股权益");
        BUCKETS.put("us", "美股权益");
        BUCKETS.put("etf_fund", "ETF · 基金");
        BUCKETS.put("cash_other", "现金 · 其他");
    }

    public FamilyService(JdbcTemplate jdbc, PerformanceService perf) {
        this.jdbc = jdbc;
        this.perf = perf;
    }

    // ---------------------------------------------------------------- 台账
    public List<Map<String, Object>> list() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, atype, member_id, name, amount, currency, note, updated_at FROM family_asset "
                            + "ORDER BY FIELD(atype,'liability','cash','deposit','fund','other'), id");
            for (Map<String, Object> r : rows) {
                r.put("amount", U.num(r.get("amount")));
                r.put("updated_at", String.valueOf(r.get("updated_at")));
            }
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long memberId(Map<String, Object> body) {
        try { return Math.max(0, Long.parseLong(String.valueOf(body.get("member_id")))); }
        catch (Exception e) { return 0; }
    }

    public Map<String, Object> add(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String atype = String.valueOf(body.getOrDefault("atype", "")).trim();
        if (!atype.matches("cash|deposit|fund|other|liability")) {
            out.put("ok", false);
            out.put("error", "类型必须是 cash / deposit / fund / other / liability");
            return out;
        }
        double amount = U.num(body.get("amount"));
        if (amount <= 0) {
            out.put("ok", false);
            out.put("error", "金额需大于 0（负债也记正数）");
            return out;
        }
        String currency = String.valueOf(body.getOrDefault("currency", "CNY")).trim().toUpperCase();
        if (!currency.matches("CNY|HKD|USD")) currency = "CNY";
        try {
            jdbc.update("INSERT INTO family_asset (atype, member_id, name, amount, currency, note) VALUES (?,?,?,?,?,?)",
                    atype, memberId(body), str(body.get("name")), amount, currency, str(body.get("note")));
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> update(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        long id;
        try { id = Long.parseLong(String.valueOf(body.get("id"))); }
        catch (Exception e) { out.put("ok", false); out.put("error", "无效 id"); return out; }
        String atype = String.valueOf(body.getOrDefault("atype", "")).trim();
        if (!atype.matches("cash|deposit|fund|other|liability")) {
            out.put("ok", false);
            out.put("error", "类型必须是 cash / deposit / fund / other / liability");
            return out;
        }
        double amount = U.num(body.get("amount"));
        if (amount <= 0) {
            out.put("ok", false);
            out.put("error", "金额需大于 0（负债也记正数）");
            return out;
        }
        String currency = String.valueOf(body.getOrDefault("currency", "CNY")).trim().toUpperCase();
        if (!currency.matches("CNY|HKD|USD")) currency = "CNY";
        try {
            int n = jdbc.update("UPDATE family_asset SET atype=?, member_id=?, name=?, amount=?, currency=?, note=? WHERE id=?",
                    atype, memberId(body), str(body.get("name")), amount, currency, str(body.get("note")), id);
            out.put("ok", n > 0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    public boolean remove(long id) {
        try {
            return jdbc.update("DELETE FROM family_asset WHERE id=?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 目标配置
    public Map<String, Double> targets() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String b : BUCKETS.keySet()) out.put(b, 0.0);
        try {
            jdbc.query("SELECT bucket, pct FROM family_target", rs -> {
                String b = rs.getString(1);
                if (out.containsKey(b)) out.put(b, U.num(rs.getObject(2)));
            });
        } catch (Exception ignore) {}
        return out;
    }

    /** 保存目标占比（缺的 bucket 记 0）。返回校验信息：总和是否等于 100。 */
    public Map<String, Object> saveTargets(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Double> cur = targets();
            Object t = body.get("targets");
            if (t instanceof Map<?, ?> tm) {
                for (var e : tm.entrySet()) {
                    String b = String.valueOf(e.getKey());
                    if (!cur.containsKey(b)) continue;
                    double v = U.num(e.getValue());
                    cur.put(b, Math.max(0, Math.min(100, v)));
                }
            }
            for (var e : cur.entrySet()) {
                jdbc.update("INSERT INTO family_target (bucket, pct) VALUES (?,?) "
                                + "ON DUPLICATE KEY UPDATE pct=VALUES(pct)",
                        e.getKey(), e.getValue());
            }
            double sum = cur.values().stream().mapToDouble(Double::doubleValue).sum();
            out.put("ok", true);
            out.put("sum", U.round(sum, 2));
            if (Math.abs(sum - 100) > 0.01) out.put("warn", "目标占比合计 " + U.round(sum, 2) + "%，不等于 100%");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- 汇总
    /** 粗算一次证券估值：会打一次行情（FeedService 缓存命中时是纯内存操作）。 */
    private SecSnap computeSec() {
        Map<String, Double> sec = new LinkedHashMap<>();
        for (String b : BUCKETS.keySet()) sec.put(b, 0.0);
        List<Map<String, Object>> holdings = new ArrayList<>();
        for (PerformanceService.Val v : perf.values()) {
            if (v.closed || v.mv <= 0) continue;
            String grp = Universe.classifyCode(v.code)[0];
            String bucket = switch (grp) {
                case "a" -> "a";
                case "hk" -> "hk";
                case "us" -> "us";
                default -> "etf_fund";
            };
            double mvCny = v.mv * perf.fxOf(v.currency);
            sec.merge(bucket, mvCny, Double::sum);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("code", v.code);
            h.put("name", v.name == null || v.name.isEmpty() ? v.code : v.name);
            h.put("bucket", bucket);
            h.put("currency", v.currency);
            h.put("mv", U.round(v.mv, 2));
            h.put("mv_cny", U.round(mvCny, 2));
            holdings.add(h);
        }
        SecSnap s = new SecSnap(sec, holdings, System.currentTimeMillis());
        secSnap = s;
        return s;
    }

    /** 后台补算（同一时刻只有一个在跑）；失败静默保留旧快照。 */
    private void refreshSecAsync() {
        if (!secBusy.compareAndSet(false, true)) return;
        try {
            secPool.submit(() -> {
                try {
                    computeSec();
                } catch (Exception ignore) {
                    // 行情失败：保留旧快照，下一轮再试
                } finally {
                    secBusy.set(false);
                }
            });
        } catch (Exception e) {
            secBusy.set(false);
        }
    }

    /**
     * SWR 取证券估值：TTL 内直接命中；过期且有旧值时立刻返回旧值并触发后台刷新；
     * 冷启动（或刚被 invalidate）才同步算一次，且并发只算一次。
     */
    private SecSnap secValuation() {
        SecSnap s = secSnap;
        if (s != null && System.currentTimeMillis() - s.ts <= SEC_TTL_MS) return s;
        if (s != null) {
            refreshSecAsync();
            return s;
        }
        synchronized (secLock) {
            s = secSnap;
            if (s == null || System.currentTimeMillis() - s.ts > SEC_TTL_MS) s = computeSec();
            return s;
        }
    }

    /** 交易流水 / 持仓变动后调用：下次请求同步重算，避免继续显示旧持仓。 */
    public void invalidateSec() {
        secSnap = null;
    }

    /**
     * 家庭总览：
     *   证券部分  —— perf.values() 按标的分类折人民币（a / hk / us / etf+fund）；
     *   非证券    —— family_asset 按类型归桶（cash/deposit/other→cash_other，fund→etf_fund）；
     *   负债单列  —— 净值 = 总资产 − 总负债；目标占比与偏离度按总资产口径。
     */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 证券市值（折人民币）按 bucket —— 走 SWR 快照，不把请求线程卡在行情上
        boolean dbOk = true;
        Map<String, Double> sec;
        List<Map<String, Object>> holdings;
        long secTs;
        try {
            SecSnap snap = secValuation();
            sec = snap.sec;
            holdings = snap.holdings;
            secTs = snap.ts;
        } catch (Exception e) {
            dbOk = false;
            sec = new LinkedHashMap<>();
            for (String b : BUCKETS.keySet()) sec.put(b, 0.0);
            holdings = new ArrayList<>();
            secTs = 0L;
        }

        // 非证券台账按 bucket 归集
        Map<String, Double> manual = new LinkedHashMap<>();
        for (String b : BUCKETS.keySet()) manual.put(b, 0.0);
        double liabilityCny = 0;
        List<Map<String, Object>> rows = List.of();
        try {
            rows = list();
        } catch (Exception e) {
            dbOk = false;
        }
        Map<Long, String> memberNames = new LinkedHashMap<>();
        for (Map<String, Object> m : members()) memberNames.put((Long) m.get("id"), String.valueOf(m.get("name")));
        String month = java.time.LocalDate.now().toString().substring(0, 7);
        for (Map<String, Object> r : rows) {
            String atype = String.valueOf(r.get("atype"));
            double cny = U.num(r.get("amount")) * perf.fxOf(String.valueOf(r.get("currency")));
            if ("liability".equals(atype)) {
                liabilityCny += cny;
            } else {
                manual.merge("fund".equals(atype) ? "etf_fund" : "cash_other", cny, Double::sum);
            }
            long mid = ((Number) r.getOrDefault("member_id", 0)).longValue();
            r.put("member", memberNames.getOrDefault(mid, mid == 0 ? "共有" : "已删除成员"));
        }

        // 成员维度：台账余额 + 本月收支（全部折人民币）
        List<Map<String, Object>> monthFlows = List.of();
        try {
            monthFlows = jdbc.queryForList(
                    "SELECT member_id, ftype, amount, currency FROM family_flow "
                            + "WHERE DATE_FORMAT(flow_date,'%Y-%m') = ?", month);
        } catch (Exception ignore) {}
        Map<Long, double[]> byMember = new LinkedHashMap<>();   // memberId → [balance, income, expense]
        for (Map<String, Object> r : rows) {
            if ("liability".equals(r.get("atype"))) continue;
            long mid = ((Number) r.getOrDefault("member_id", 0)).longValue();
            byMember.computeIfAbsent(mid, k -> new double[3])[0]
                    += U.num(r.get("amount")) * perf.fxOf(String.valueOf(r.get("currency")));
        }
        for (Map<String, Object> f : monthFlows) {
            long mid = ((Number) f.get("member_id")).longValue();
            double v = U.num(f.get("amount")) * perf.fxOf(String.valueOf(f.get("currency")));
            if ("income".equals(f.get("ftype"))) byMember.computeIfAbsent(mid, k -> new double[3])[1] += v;
            else byMember.computeIfAbsent(mid, k -> new double[3])[2] += v;
        }
        List<Map<String, Object>> memberStats = new ArrayList<>();
        for (Map<String, Object> m : members()) {
            long mid = (Long) m.get("id");
            double[] v = byMember.getOrDefault(mid, new double[3]);
            Map<String, Object> ms = new LinkedHashMap<>(m);
            ms.put("balance", U.round(v[0], 2));
            ms.put("month_income", U.round(v[1], 2));
            ms.put("month_expense", U.round(v[2], 2));
            ms.put("month_net", U.round(v[1] - v[2], 2));
            memberStats.add(ms);
        }
        {   // 共有账户（member_id=0）
            double[] v = byMember.getOrDefault(0L, new double[3]);
            Map<String, Object> ms = new LinkedHashMap<>();
            ms.put("id", 0L);
            ms.put("name", "共有");
            ms.put("sort_no", -1);
            ms.put("balance", U.round(v[0], 2));
            ms.put("month_income", U.round(v[1], 2));
            ms.put("month_expense", U.round(v[2], 2));
            ms.put("month_net", U.round(v[1] - v[2], 2));
            memberStats.add(0, ms);
        }

        // 大类汇总 + 占比 + 目标偏离
        Map<String, Double> tg = targets();
        double assets = 0;
        for (String b : BUCKETS.keySet()) assets += sec.get(b) + manual.get(b);
        List<Map<String, Object>> cats = new ArrayList<>();
        for (var e : BUCKETS.entrySet()) {
            String b = e.getKey();
            double mv = sec.get(b) + manual.get(b);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("bucket", b);
            c.put("label", e.getValue());
            c.put("mv_cny", U.round(mv, 2));
            c.put("share", assets > 0 ? U.round(mv / assets * 100, 2) : 0);
            c.put("target", tg.get(b));
            double t = tg.get(b);
            c.put("diff", t > 0 ? U.round(mv / (assets > 0 ? assets : 1) * 100 - t, 2) : null);
            cats.add(c);
        }

        out.put("ready", dbOk);
        out.put("sec_ts", secTs);
        out.put("buckets", BUCKETS);
        out.put("categories", cats);
        out.put("holdings", holdings);
        out.put("assets", U.round(assets, 2));
        out.put("liabilities", U.round(liabilityCny, 2));
        out.put("net", U.round(assets - liabilityCny, 2));
        out.put("manual", rows);
        out.put("members", memberStats);
        List<Map<String, Object>> flowRows = flows(60);
        for (Map<String, Object> f : flowRows) {
            long mid = ((Number) f.getOrDefault("member_id", 0)).longValue();
            f.put("member", memberNames.getOrDefault(mid, mid == 0 ? "共有" : "已删除成员"));
        }
        out.put("flows", flowRows);
        out.put("flow_totals", flowTotals());
        out.put("monthly", monthlyFlows(12));
        out.put("flow_categories", flowCategories(null));
        out.put("nav", navHistory(180));
        if (dbOk) snapshotNav(assets, liabilityCny, assets - liabilityCny);
        out.put("fx", perf.fxMap());
        out.put("target_sum", U.round(tg.values().stream().mapToDouble(Double::doubleValue).sum(), 2));
        return out;
    }

    // ---------------------------------------------------------------- 成员
    public List<Map<String, Object>> members() {
        try {
            return jdbc.queryForList(
                    "SELECT id, name, sort_no FROM family_member ORDER BY sort_no, id");
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> memberAdd(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = str(body.get("name"));
        if (name.isEmpty()) { out.put("ok", false); out.put("error", "成员名不能为空"); return out; }
        try {
            jdbc.update("INSERT INTO family_member (name, sort_no) VALUES (?,?)",
                    name, (int) U.num(body.getOrDefault("sort_no", 99)));
            out.put("ok", true);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    public Map<String, Object> memberUpdate(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        long id;
        try { id = Long.parseLong(String.valueOf(body.get("id"))); }
        catch (Exception e) { out.put("ok", false); out.put("error", "无效 id"); return out; }
        String name = str(body.get("name"));
        if (name.isEmpty()) { out.put("ok", false); out.put("error", "成员名不能为空"); return out; }
        try {
            out.put("ok", jdbc.update("UPDATE family_member SET name=? WHERE id=?", name, id) > 0);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    /** 删除成员：名下科目与收支归入「共有」（member_id=0），不丢数据。 */
    public boolean memberRemove(long id) {
        try {
            jdbc.update("UPDATE family_asset SET member_id=0 WHERE member_id=?", id);
            jdbc.update("UPDATE family_flow SET member_id=0 WHERE member_id=?", id);
            return jdbc.update("DELETE FROM family_member WHERE id=?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 收支流水
    public List<Map<String, Object>> flows(int limit) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, member_id, ftype, category, amount, currency, flow_date, note "
                            + "FROM family_flow ORDER BY flow_date DESC, id DESC LIMIT " + Math.max(1, limit));
            for (Map<String, Object> r : rows) {
                r.put("amount", U.num(r.get("amount")));
                r.put("flow_date", String.valueOf(r.get("flow_date")));
            }
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 校验并提取收支流水参数；失败时 out.ok=false。 */
    private Map<String, Object> flowParams(Map<String, Object> body, Map<String, Object> out) {
        String ftype = String.valueOf(body.getOrDefault("ftype", "")).trim();
        if (!ftype.matches("income|expense")) {
            out.put("error", "类型必须是 income / expense");
            out.put("ok", false);
            return null;
        }
        double amount = U.num(body.get("amount"));
        if (amount <= 0) { out.put("error", "金额需大于 0"); out.put("ok", false); return null; }
        String currency = String.valueOf(body.getOrDefault("currency", "CNY")).trim().toUpperCase();
        if (!currency.matches("CNY|HKD|USD")) currency = "CNY";
        String date = str(body.get("flow_date"));
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}.*")) date = java.time.LocalDate.now().toString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("member_id", memberId(body));
        p.put("ftype", ftype);
        p.put("category", str(body.get("category")));
        p.put("amount", amount);
        p.put("currency", currency);
        p.put("flow_date", date.substring(0, 10));
        p.put("note", str(body.get("note")));
        return p;
    }

    public Map<String, Object> flowAdd(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> p = flowParams(body, out);
        if (p == null) return out;
        try {
            jdbc.update("INSERT INTO family_flow (member_id, ftype, category, amount, currency, flow_date, note) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    p.get("member_id"), p.get("ftype"), p.get("category"), p.get("amount"),
                    p.get("currency"), p.get("flow_date"), p.get("note"));
            out.put("ok", true);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    public Map<String, Object> flowUpdate(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        long id;
        try { id = Long.parseLong(String.valueOf(body.get("id"))); }
        catch (Exception e) { out.put("ok", false); out.put("error", "无效 id"); return out; }
        Map<String, Object> p = flowParams(body, out);
        if (p == null) return out;
        try {
            int n = jdbc.update("UPDATE family_flow SET member_id=?, ftype=?, category=?, amount=?, "
                            + "currency=?, flow_date=?, note=? WHERE id=?",
                    p.get("member_id"), p.get("ftype"), p.get("category"), p.get("amount"),
                    p.get("currency"), p.get("flow_date"), p.get("note"), id);
            out.put("ok", n > 0);
        } catch (Exception e) { out.put("ok", false); out.put("error", e.getMessage()); }
        return out;
    }

    public boolean flowRemove(long id) {
        try {
            return jdbc.update("DELETE FROM family_flow WHERE id=?", id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 本月 / 本年 / 累计 收入与支出（折人民币）。 */
    private Map<String, Object> flowTotals() {
        Map<String, Object> out = new LinkedHashMap<>();
        String month = java.time.LocalDate.now().toString().substring(0, 7);
        String year = month.substring(0, 4);
        for (String k : List.of("month", "year", "all")) out.put(k + "_income", 0.0);
        for (String k : List.of("month", "year", "all")) out.put(k + "_expense", 0.0);
        try {
            jdbc.query("SELECT ftype, currency, amount, flow_date FROM family_flow", rs -> {
                double fx = perf.fxOf(rs.getString(2));
                double v = U.num(rs.getObject(3)) * fx;
                String d = String.valueOf(rs.getDate(4));
                String f = "income".equals(rs.getString(1)) ? "income" : "expense";
                // 递进累加：本月 → 本月+本年+累计；本年 → 本年+累计
                if (d.startsWith(month)) {
                    out.merge("month_" + f, v, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
                }
                if (d.startsWith(year)) {
                    out.merge("year_" + f, v, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
                }
                out.merge("all_" + f, v, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
            });
        } catch (Exception ignore) {}
        return out;
    }

    // ---------------------------------------------------------------- 净值快照
    /** 落一行当日家庭净值快照（同日反复调用走 upsert，取当天最新值）。 */
    private void snapshotNav(double assets, double liabilities, double net) {
        try {
            jdbc.update("INSERT INTO family_nav_daily (trade_date, assets, liabilities, net) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE assets=VALUES(assets), liabilities=VALUES(liabilities), net=VALUES(net)",
                    LocalDate.now().toString(), U.round(assets, 2), U.round(liabilities, 2), U.round(net, 2));
        } catch (Exception ignore) {}
    }

    /** 近 N 天净值曲线（升序）。 */
    public List<Map<String, Object>> navHistory(int days) {
        try {
            List<Map<String, Object>> raw = jdbc.queryForList(
                    "SELECT trade_date, assets, liabilities, net FROM family_nav_daily "
                            + "ORDER BY trade_date DESC LIMIT " + Math.max(1, days));
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = raw.size() - 1; i >= 0; i--) {
                Map<String, Object> r = raw.get(i);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("date", String.valueOf(r.get("trade_date")));
                o.put("assets", U.num(r.get("assets")));
                o.put("liabilities", U.num(r.get("liabilities")));
                o.put("net", U.num(r.get("net")));
                out.add(o);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- 收支统计
    /** 近 N 个月逐月收支（折人民币），缺的月份补 0。 */
    public List<Map<String, Object>> monthlyFlows(int months) {
        months = Math.max(1, Math.min(24, months));
        Map<String, double[]> byMonth = new LinkedHashMap<>();
        LocalDate cur = LocalDate.now().withDayOfMonth(1);
        for (int i = months - 1; i >= 0; i--) {
            byMonth.put(cur.minusMonths(i).toString().substring(0, 7), new double[2]);
        }
        String from = cur.minusMonths(months - 1).toString().substring(0, 7);
        try {
            jdbc.query("SELECT ftype, currency, amount, DATE_FORMAT(flow_date,'%Y-%m') FROM family_flow "
                            + "WHERE DATE_FORMAT(flow_date,'%Y-%m') >= ?", rs -> {
                double v = U.num(rs.getObject(3)) * perf.fxOf(rs.getString(2));
                double[] arr = byMonth.get(rs.getString(4));
                if (arr == null) return;
                if ("income".equals(rs.getString(1))) arr[0] += v; else arr[1] += v;
            }, from);
        } catch (Exception ignore) {}
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : byMonth.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("month", e.getKey());
            o.put("income", U.round(e.getValue()[0], 2));
            o.put("expense", U.round(e.getValue()[1], 2));
            o.put("net", U.round(e.getValue()[0] - e.getValue()[1], 2));
            out.add(o);
        }
        return out;
    }

    /** 指定月份（默认当月，yyyy-MM）收支分类构成，各取 Top 8（折人民币）。 */
    public Map<String, Object> flowCategories(String month) {
        String m = (month != null && month.matches("\\d{4}-\\d{2}.*")) ? month.substring(0, 7)
                : LocalDate.now().toString().substring(0, 7);
        Map<String, Double> income = new LinkedHashMap<>();
        Map<String, Double> expense = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT ftype, category, currency, amount FROM family_flow "
                            + "WHERE DATE_FORMAT(flow_date,'%Y-%m') = ?", rs -> {
                double v = U.num(rs.getObject(4)) * perf.fxOf(rs.getString(3));
                Map<String, Double> tgt = "income".equals(rs.getString(1)) ? income : expense;
                String cat = rs.getString(2);
                tgt.merge(cat == null || cat.isBlank() ? "未分类" : cat, v,
                        (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
            }, m);
        } catch (Exception ignore) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", m);
        out.put("income", topCats(income));
        out.put("expense", topCats(expense));
        return out;
    }

    private static List<Map<String, Object>> topCats(Map<String, Double> m) {
        List<Map<String, Object>> out = new ArrayList<>();
        m.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("category", e.getKey());
                    o.put("amount_cny", U.round(e.getValue(), 2));
                    out.add(o);
                });
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
