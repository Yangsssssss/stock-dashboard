package com.quantboard.db;

import com.quantboard.util.U;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL 持久层。数据库是可降级的旁路：任何 DB 异常都不影响看板取数与渲染。
 * 表结构与迁移脚本与 Python 版共用（db/migration/V1、V2 已应用）。
 */
@Service
public class DbService {
    private final JdbcTemplate jdbc;
    private final String dbName;
    private final String migrationDir;

    private volatile boolean ready = false;
    /** 快照节流：code → 上次写入时间戳（秒）。 */
    private final Map<String, Double> lastSnapshot = new ConcurrentHashMap<>();

    private final String dsUrl;
    private final String dsUser;
    private final String dsPassword;

    public DbService(JdbcTemplate jdbc,
                     @Value("${spring.datasource.url}") String dsUrl,
                     @Value("${spring.datasource.username}") String dsUser,
                     @Value("${spring.datasource.password}") String dsPassword,
                     @Value("${quantboard.db-name}") String dbName,
                     @Value("${quantboard.migration-dir}") String migrationDir) {
        this.jdbc = jdbc;
        this.dsUrl = dsUrl;
        this.dsUser = dsUser;
        this.dsPassword = dsPassword;
        this.dbName = dbName;
        this.migrationDir = migrationDir;
        this.ready = init();
    }

    public boolean ready() {
        return ready;
    }

    /** 建库（无库名连接）→ 校验连接 → 跑迁移。失败返回 false，看板继续跑。 */
    private boolean init() {
        try {
            // 从 datasource url 解析 host:port，用同一套凭据建无库名连接
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("jdbc:mysql://([^/:]+):(\\d+)/").matcher(dsUrl);
            if (!mm.find()) throw new IllegalStateException("无法解析 datasource url: " + dsUrl);
            String host = mm.group(1);
            int port = Integer.parseInt(mm.group(2));
            String base = "jdbc:mysql://" + host + ":" + port + "/?useUnicode=true&characterEncoding=UTF-8"
                    + "&user=" + dsUser + "&password=" + dsPassword + "&connectTimeout=4000";
            try (var conn = java.sql.DriverManager.getConnection(base)) {
                try (var st = conn.createStatement()) {
                    st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName
                            + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                }
            }
            jdbc.execute("SELECT 1");
            migrate();
            System.out.println("[db] 已连接 " + host + ":" + port + "/" + dbName);
            return true;
        } catch (Exception e) {
            System.out.println("[db] 初始化失败：" + e.getMessage());
            return false;
        }
    }

    /** Flyway 风格迁移（与 Python 版共用 schema_version 表）。 */
    private void migrate() throws Exception {
        Path dir = Path.of(migrationDir);
        if (!Files.isDirectory(dir)) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version VARCHAR(32) NOT NULL, script VARCHAR(128) NOT NULL, checksum VARCHAR(64) NOT NULL, "
                + "applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (version)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        List<String> done = jdbc.queryForList("SELECT version FROM schema_version", String.class);
        try (var files = Files.list(dir)) {
            List<Path> sorted = files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(java.util.Comparator.comparingInt(p -> Integer.parseInt(
                            p.getFileName().toString().replaceAll("^V(\\d+)__.*", "$1"))))
                    .toList();
            for (Path f : sorted) {
                String name = f.getFileName().toString();
                String ver = name.split("__", 2)[0];
                if (done.contains(ver)) continue;
                String sql = Files.readString(f);
                for (String stmt : splitSql(sql)) {
                    jdbc.execute(stmt);
                }
                jdbc.update("INSERT INTO schema_version (version, script, checksum) VALUES (?,?,?)",
                        ver, name, md5(sql));
                System.out.println("[db] 迁移 " + name + " 完成");
            }
        }
    }

    private static List<String> splitSql(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("--")) continue;
            buf.append(line).append('\n');
            if (s.endsWith(";")) {
                String stmt = buf.toString().strip().replaceAll(";$", "").strip();
                if (!stmt.isEmpty()) out.add(stmt);
                buf.setLength(0);
            }
        }
        String rest = buf.toString().strip();
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes()));
    }

    // ---------------------------------------------------------------- 标的
    public boolean upsertInstruments(List<Map<String, Object>> items) {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> i : items) {
            String code = (String) i.get("code");
            String market = (String) i.get("market");
            if (market == null || market.isEmpty()) {
                market = code.startsWith("us") ? "US" : code.startsWith("hk") ? "HK" : "CN";
            }
            String currency = i.get("currency") != null ? (String) i.get("currency") : "CNY";
            rows.add(new Object[]{code, i.get("name"), market, i.get("kind"), currency});
        }
        if (rows.isEmpty()) return true;
        try {
            jdbc.batchUpdate(
                    "INSERT INTO instrument (code, name, market, kind, currency) VALUES (?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), "
                            + "kind=VALUES(kind), currency=VALUES(currency)", rows);
            return true;
        } catch (Exception e) {
            System.out.println("[db] instrument 写入失败：" + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 日线
    /** 增量写入：只补每个标的最新日期之后的数据。返回写入行数。 */
    public int upsertKlines(Map<String, List<Object[]>> series) {
        if (series == null || series.isEmpty()) return 0;
        try {
            Map<String, String> latest = new HashMap<>();
            jdbc.query("SELECT code, MAX(trade_date) AS d FROM kline_daily GROUP BY code", rs -> {
                latest.put(rs.getString(1), rs.getString(2));
            });
            List<Object[]> rows = new ArrayList<>();
            for (var e : series.entrySet()) {
                String last = latest.get(e.getKey());
                for (Object[] b : e.getValue()) {
                    String d = String.valueOf(b[0]);
                    if (last != null && d.compareTo(last) <= 0) continue;
                    rows.add(new Object[]{e.getKey(), d, b[1], b[2], b[3], b[4], b[5]});
                }
            }
            if (rows.isEmpty()) return 0;
            jdbc.batchUpdate(
                    "INSERT INTO kline_daily (code, trade_date, open, close, high, low, volume) "
                            + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                            + "open=VALUES(open), close=VALUES(close), high=VALUES(high), "
                            + "low=VALUES(low), volume=VALUES(volume)", rows);
            return rows.size();
        } catch (Exception e) {
            System.out.println("[db] kline 写入失败：" + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 快照
    public int insertSnapshots(List<Map<String, Object>> quotes, int throttleSec) {
        double now = System.currentTimeMillis() / 1000.0;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> q : quotes) {
            String code = (String) q.get("code");
            Double prev = lastSnapshot.get(code);
            if (prev != null && now - prev < throttleSec) continue;
            rows.add(new Object[]{code,
                    LocalDateTime.now().format(fmt),
                    ((Number) q.get("price")).doubleValue(),
                    ((Number) q.get("pct")).doubleValue(),
                    q.get("amount_yi"), q.get("turnover"), q.get("pe"), q.get("mktcap_yi")});
            lastSnapshot.put(code, now);
        }
        if (rows.isEmpty()) return 0;
        try {
            jdbc.batchUpdate(
                    "INSERT INTO quote_snapshot (code, snapshot_at, price, pct, amount_yi, "
                            + "turnover, pe, mktcap_yi) VALUES (?,?,?,?,?,?,?,?)", rows);
            return rows.size();
        } catch (Exception e) {
            System.out.println("[db] snapshot 写入失败：" + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 评分
    public int upsertScores(List<Map<String, Object>> items, String tradeDate) {
        String d = tradeDate != null ? tradeDate : LocalDate.now().toString();
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> i : items) {
            Map<String, Object> a = castMap(i.get("ana"));
            if (a == null || !Boolean.TRUE.equals(a.get("ok"))) continue;
            Map<String, Object> f = castMap(a.get("factors"));
            rows.add(new Object[]{i.get("code"), d, a.get("score"), a.get("signal"),
                    f != null ? f.getOrDefault("trend", 0) : 0,
                    f != null ? f.getOrDefault("momentum", 0) : 0,
                    f != null ? f.getOrDefault("volume", 0) : 0,
                    f != null ? f.getOrDefault("risk", 0) : 0,
                    a.get("r5"), a.get("r20"), a.get("rsi")});
        }
        if (rows.isEmpty()) return 0;
        return upsertScoreRows(rows);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    public int upsertScoreRows(List<Object[]> rows) {
        if (rows.isEmpty()) return 0;
        try {
            jdbc.batchUpdate(
                    "INSERT INTO daily_score (code, trade_date, score, sig, trend, momentum, "
                            + "volume_f, risk, r5, r20, rsi) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE score=VALUES(score), sig=VALUES(sig), "
                            + "trend=VALUES(trend), momentum=VALUES(momentum), volume_f=VALUES(volume_f), "
                            + "risk=VALUES(risk), r5=VALUES(r5), r20=VALUES(r20), rsi=VALUES(rsi)", rows);
            return rows.size();
        } catch (Exception e) {
            System.out.println("[db] score 写入失败：" + e.getMessage());
            return 0;
        }
    }

    public List<Map<String, Object>> scoreHistory(String code, int days) {
        try {
            List<Map<String, Object>> raw = jdbc.queryForList(
                    "SELECT trade_date, score, sig FROM daily_score WHERE code=? "
                            + "ORDER BY trade_date DESC LIMIT " + days, code);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = raw.size() - 1; i >= 0; i--) {
                Map<String, Object> r = raw.get(i);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("date", String.valueOf(r.get("trade_date")));
                o.put("score", U.num(r.get("score")));
                o.put("signal", r.get("sig"));
                out.add(o);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 最近两个交易日之间评分变化最大的标的（动量拐点）。 */
    public Map<String, Object> scoreMovers() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<String> ds = jdbc.queryForList(
                    "SELECT DISTINCT trade_date FROM daily_score ORDER BY trade_date DESC LIMIT 2",
                    String.class);
            out.put("dates", ds);
            if (ds.size() < 2) {
                out.put("up", List.of());
                out.put("down", List.of());
                return out;
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT a.code, i.name, a.score AS s_new, b.score AS s_old, "
                            + "ROUND(a.score - b.score, 1) AS delta "
                            + "FROM daily_score a "
                            + "JOIN daily_score b ON b.code = a.code AND b.trade_date = ? "
                            + "LEFT JOIN instrument i ON i.code = a.code "
                            + "WHERE a.trade_date = ? ORDER BY delta DESC", ds.get(1), ds.get(0));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("code", r.get("code"));
                o.put("name", r.get("name") != null ? r.get("name") : r.get("code"));
                o.put("score", U.num(r.get("s_new")));
                o.put("prev", U.num(r.get("s_old")));
                o.put("delta", U.num(r.get("delta")));
                list.add(o);
            }
            out.put("up", list.subList(0, Math.min(5, list.size())));
            out.put("down", list.subList(Math.max(0, list.size() - 5), list.size()));
            java.util.Collections.reverse((List<?>) out.get("down"));
            return out;
        } catch (Exception e) {
            out.put("up", List.of());
            out.put("down", List.of());
            out.put("dates", List.of());
            return out;
        }
    }

    // ---------------------------------------------------------------- 自定义标的
    public List<Map<String, Object>> customInstruments() {
        if (!ready) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT code, name, market, kind, currency, grp FROM instrument "
                            + "WHERE custom=1 AND hidden=0 ORDER BY created_at, code");
        } catch (Exception e) {
            System.out.println("[db] 读取自定义标的失败：" + e.getMessage());
            return List.of();
        }
    }

    public List<String> hiddenBuiltins() {
        if (!ready) return List.of();
        try {
            return jdbc.queryForList("SELECT code FROM instrument WHERE custom=0 AND hidden=1", String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean instrumentAdd(String code, String name, String grp, String kind,
                                 String market, String currency) {
        try {
            jdbc.update(
                    "INSERT INTO instrument (code, name, market, kind, currency, grp, custom, hidden) "
                            + "VALUES (?,?,?,?,?,?,1,0) "
                            + "ON DUPLICATE KEY UPDATE custom=1, hidden=0, name=VALUES(name), "
                            + "market=VALUES(market), kind=VALUES(kind), currency=VALUES(currency), grp=VALUES(grp)",
                    code, name, market, kind, currency, grp);
            return true;
        } catch (Exception e) {
            System.out.println("[db] 添加标的失败：" + e.getMessage());
            return false;
        }
    }

    /** 返回处理方式：'deleted'（自定义，直接删行）/ 'hidden'（内置，标记隐藏）。 */
    public String instrumentRemove(String code) {
        try {
            List<Boolean> custom = jdbc.query(
                    "SELECT custom FROM instrument WHERE code=?", rs -> {
                        List<Boolean> out = new ArrayList<>();
                        while (rs.next()) out.add(rs.getBoolean(1));
                        return out;
                    }, code);
            if (!custom.isEmpty() && custom.get(0)) {
                jdbc.update("DELETE FROM instrument WHERE code=?", code);
                return "deleted";
            }
            jdbc.update(
                    "INSERT INTO instrument (code, name, market, kind, hidden) VALUES (?,?,'CN','stock',1) "
                            + "ON DUPLICATE KEY UPDATE hidden=1", code, code);
            return "hidden";
        } catch (Exception e) {
            System.out.println("[db] 移除标的失败：" + e.getMessage());
            return "";
        }
    }

    public int instrumentRestoreAll() {
        try {
            return jdbc.update("UPDATE instrument SET hidden=0 WHERE custom=0 AND hidden=1");
        } catch (Exception e) {
            System.out.println("[db] 恢复标的失败：" + e.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 自选
    public List<Map<String, Object>> watchlist() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT code, note, sort_no, created_at FROM watchlist ORDER BY sort_no, created_at");
            for (Map<String, Object> r : rows) {
                r.put("created_at", String.valueOf(r.get("created_at")));
            }
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<String> watchCodes() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> w : watchlist()) out.add((String) w.get("code"));
        return out;
    }

    public boolean watchAdd(String code, String note) {
        try {
            jdbc.update("INSERT IGNORE INTO watchlist (code, note) VALUES (?,?)", code, note);
            return true;
        } catch (Exception e) {
            System.out.println("[db] watchlist 写入失败：" + e.getMessage());
            return false;
        }
    }

    public boolean watchRemove(String code) {
        try {
            jdbc.update("DELETE FROM watchlist WHERE code=?", code);
            return true;
        } catch (Exception e) {
            System.out.println("[db] watchlist 删除失败：" + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 统计
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", ready);
        out.put("db", dsUrl.replaceAll("jdbc:mysql://", "").split("/")[0] + "/" + dbName);
        if (!ready) return out;
        try {
            out.put("instruments", count("instrument"));
            out.put("klines", count("kline_daily"));
            out.put("snapshots", count("quote_snapshot"));
            out.put("scores", count("daily_score"));
            out.put("watchlist", count("watchlist"));
            String last = jdbc.queryForObject("SELECT MAX(trade_date) FROM kline_daily", String.class);
            out.put("last_kline", last != null ? last : "");
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }
}
