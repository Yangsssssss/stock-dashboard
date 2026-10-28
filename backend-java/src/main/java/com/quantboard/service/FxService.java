package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.feed.HttpFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 汇率：全站外币折算的唯一来源。
 *
 * 早先 fx-hkd / fx-usd 写死在 application.yml（0.92 / 7.15），意味着组合里的港币、
 * 美元资产永远按某个历史时点的汇率折算 —— 持仓、家庭总览、收益曲线全部一起吃这个偏差，
 * 而且没有任何地方提示「这是哪天、哪个价」。现在改为按日入库：
 *
 *   1) 每天 08:10 拉一次，服务启动时补拉一次；
 *   2) 落 fx_rate(ccy, rate_date, rate, src)，主键去重，重复抓同一天只覆盖；
 *   3) 估值接口一律带 as_of / src / stale，前端据此标注「汇率 6.6673（10-28）」。
 *
 * 取数顺序：新浪外汇（一次请求拿 USD/HKD 两个币种）→ open.er-api（美元本位交叉换算）
 * → 配置里的静态值（兜底，标记 stale）。
 */
@Service
public class FxService {
    private static final Logger log = LoggerFactory.getLogger(FxService.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern QUOTE = Pattern.compile("hq_str_fx_s(\\w+)=\"([^\"]*)\"");
    /** 超过这个天数没更新，就认为汇率不新鲜，接口标 stale。 */
    private static final int STALE_DAYS = 5;

    private final JdbcTemplate jdbc;
    private final double fallbackHkd;
    private final double fallbackUsd;

    /** 当前生效汇率：ccy → [rate, date, src]。整体替换，读侧无锁。 */
    private volatile Map<String, double[]> rates = new HashMap<>();
    private volatile Map<String, String> rateDate = new HashMap<>();
    private volatile Map<String, String> rateSrc = new HashMap<>();

    public FxService(JdbcTemplate jdbc,
                     @Value("${quantboard.fx-hkd:0.92}") double fallbackHkd,
                     @Value("${quantboard.fx-usd:7.15}") double fallbackUsd) {
        this.jdbc = jdbc;
        this.fallbackHkd = fallbackHkd;
        this.fallbackUsd = fallbackUsd;
        applyFallback();
    }

    // ---------------------------------------------------------------- 对外
    /** 1 单位外币折人民币。CNY 恒为 1；未知币种也按 1 处理（与原口径一致）。 */
    public double rate(String currency) {
        if (currency == null) return 1;
        String c = currency.trim().toUpperCase();
        if ("HKD".equals(c) || "USD".equals(c)) {
            double[] v = rates.get(c);
            if (v != null && v[0] > 0) return v[0];
        }
        return 1;
    }

    /**
     * 前端/接口用的汇率视图。保留原有的 fx.HKD / fx.USD 两个数值键（前端直接乘），
     * 追加 as_of / src / stale 供界面标注取数日期。
     */
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("USD", rate("USD"));
        out.put("HKD", rate("HKD"));
        String asOf = rateDate.get("USD");
        if (asOf == null) asOf = rateDate.get("HKD");
        out.put("as_of", asOf == null ? "" : asOf);
        out.put("src", rateSrc.getOrDefault("USD", rateSrc.getOrDefault("HKD", "config")));
        out.put("stale", isStale(asOf));
        return out;
    }

    private static boolean isStale(String asOf) {
        if (asOf == null || asOf.isBlank()) return true;
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(asOf), LocalDate.now()) > STALE_DAYS;
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- 调度
    /** 每天 08:10 更新（在 A 股开盘前完成，当天估值即用新汇率）。 */
    @Scheduled(cron = "0 10 8 * * *", zone = "Asia/Shanghai")
    public void refreshDaily() {
        refresh();
    }

    /** 启动时：先读库里的最近一次，再后台拉新（不阻塞首屏）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        loadFromDb();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
                refresh();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "fx-refresh");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 抓取
    /** 返回本次是否成功写入了新汇率。 */
    public synchronized boolean refresh() {
        Map<String, double[]> got = fetchSina();
        String src = "sina";
        if (got.isEmpty()) {
            got = fetchErApi();
            src = "er-api";
        }
        if (got.isEmpty()) {
            log.warn("[fx] 两个源都没取到汇率，继续沿用 {}（{}）", rate("USD"), rateDate.getOrDefault("USD", "静态配置"));
            loadFromDb();
            return false;
        }
        LocalDate today = LocalDate.now();
        for (var e : got.entrySet()) {
            double v = e.getValue()[0];
            LocalDate d = e.getValue()[1] == 0 ? today : LocalDate.ofEpochDay((long) e.getValue()[1]);
            try {
                jdbc.update("INSERT INTO fx_rate (ccy, rate_date, rate, src) VALUES (?,?,?,?) "
                                + "ON DUPLICATE KEY UPDATE rate=VALUES(rate), src=VALUES(src)",
                        e.getKey(), d, v, src);
            } catch (Exception ex) {
                log.warn("[fx] 写入 {} 失败: {}", e.getKey(), ex.getMessage());
            }
        }
        loadFromDb();
        log.info("[fx] 汇率更新（{}）：USD {} / HKD {}", src, rate("USD"), rate("HKD"));
        return true;
    }

    /**
     * 新浪外汇：一次请求拿到在岸美元、港元兑人民币。
     * 字段布局（实测 fx_susdcny / fx_shkdcny 一致）：
     *   0 时间 · 1 买价 · 2 卖价 · 3 昨收 · 4 成交量 · 5 今开 · 6 最高 · 7 最低 · 8 现价 · 9 名称 · … · 末位 日期
     * 取现价（8）；再用合理区间做一次校验，字段漂移时直接判失败，避免写入 33.0 这种成交量。
     */
    private Map<String, double[]> fetchSina() {
        Map<String, double[]> out = new HashMap<>();
        try {
            String text = HttpFetch.getText("https://hq.sinajs.cn/list=fx_susdcny,fx_shkdcny",
                    "https://finance.sina.com.cn", GBK);
            if (text == null) return out;
            Matcher m = QUOTE.matcher(text);
            while (m.find()) {
                String sym = m.group(1).toLowerCase();          // usdcny / hkdcny
                String ccy = sym.startsWith("usd") ? "USD" : sym.startsWith("hkd") ? "HKD" : null;
                if (ccy == null) continue;
                String[] f = m.group(2).split(",");
                if (f.length < 10) continue;
                double price = parse(f[8]);
                if (!plausible(ccy, price)) continue;
                LocalDate d = LocalDate.now();
                String tail = f[f.length - 1].trim();
                try { d = LocalDate.parse(tail.substring(0, 10)); } catch (Exception ignore) { }
                out.put(ccy, new double[]{price, d.toEpochDay(), 0});
            }
        } catch (Exception e) {
            log.info("[fx] 新浪取数失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * open.er-api：美元本位，一次拿全币种。
     * 港元兑人民币没有直接报价，用 USD/CNY ÷ USD/HKD 交叉换算（市场通行的算法）。
     */
    private Map<String, double[]> fetchErApi() {
        Map<String, double[]> out = new HashMap<>();
        try {
            byte[] body = HttpFetch.get("https://open.er-api.com/v6/latest/USD", 12);
            JsonNode root = M.readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (!"success".equals(root.path("result").asText())) return out;
            JsonNode r = root.path("rates");
            double cny = r.path("CNY").asDouble(0);
            double hkd = r.path("HKD").asDouble(0);
            LocalDate d = LocalDate.now();
            long ts = root.path("time_last_update_unix").asLong(0);
            if (ts > 0) d = LocalDate.ofEpochDay(java.time.Instant.ofEpochSecond(ts)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toEpochDay());
            if (plausible("USD", cny)) out.put("USD", new double[]{cny, d.toEpochDay(), 0});
            if (hkd > 0 && cny > 0 && plausible("HKD", cny / hkd)) {
                out.put("HKD", new double[]{cny / hkd, d.toEpochDay(), 0});
            }
        } catch (Exception e) {
            log.info("[fx] er-api 取数失败: {}", e.getMessage());
        }
        return out;
    }

    /** 合理区间：美元兑人民币 5–9，港元兑人民币 0.6–1.2。挡住字段错位读成成交量/涨跌幅。 */
    private static boolean plausible(String ccy, double v) {
        if (v <= 0) return false;
        return "USD".equals(ccy) ? (v > 5 && v < 9) : (v > 0.6 && v < 1.2);
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- 读库
    /** 读每个币种最近一条；库里没有就退回配置值。 */
    private void loadFromDb() {
        Map<String, double[]> r = new HashMap<>();
        Map<String, String> d = new HashMap<>();
        Map<String, String> s = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT f.ccy, f.rate, f.rate_date, f.src FROM fx_rate f "
                            + "JOIN (SELECT ccy, MAX(rate_date) md FROM fx_rate GROUP BY ccy) t "
                            + "ON t.ccy = f.ccy AND t.md = f.rate_date");
            for (Map<String, Object> row : rows) {
                String ccy = String.valueOf(row.get("ccy")).toUpperCase();
                r.put(ccy, new double[]{((Number) row.get("rate")).doubleValue(), 0, 0});
                d.put(ccy, String.valueOf(row.get("rate_date")));
                s.put(ccy, String.valueOf(row.get("src")));
            }
        } catch (Exception e) {
            // 表还没建（迁移未跑）或库不可用：保留旧值
        }
        if (!r.containsKey("USD")) {
            r.put("USD", new double[]{fallbackUsd, 0, 0});
            d.put("USD", "");
            s.put("USD", "config");
        }
        if (!r.containsKey("HKD")) {
            r.put("HKD", new double[]{fallbackHkd, 0, 0});
            d.put("HKD", "");
            s.put("HKD", "config");
        }
        rates = r;
        rateDate = d;
        rateSrc = s;
    }

    private void applyFallback() {
        Map<String, double[]> r = new HashMap<>();
        r.put("USD", new double[]{fallbackUsd, 0, 0});
        r.put("HKD", new double[]{fallbackHkd, 0, 0});
        rates = r;
        rateDate = new HashMap<>();
        rateSrc = new HashMap<>();
        rateSrc.put("USD", "config");
        rateSrc.put("HKD", "config");
    }
}
