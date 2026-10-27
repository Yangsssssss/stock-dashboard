package com.quantboard.service;

import com.quantboard.db.DbService;
import com.quantboard.feed.FeedService;
import com.quantboard.feed.FundService;
import com.quantboard.feed.Universe;
import com.quantboard.indicator.AnalysisService;
import com.quantboard.util.U;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** 看板组装：行情 → 指标评分 → 分组 → 排序 → 相关性 → 板块 → 异步落库。 */
@Service
public class BoardService {
    private static final long BOARD_TTL_MS = 25_000;

    private final FeedService feed;
    private final AnalysisService analysis;
    private final DbService db;
    private final FundService fundService;
    private final ExecutorService klinePool = Executors.newFixedThreadPool(8);
    private final ExecutorService persistPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "persist");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean persistBusy = new AtomicBoolean(false);

    private volatile long boardTs = 0;
    private volatile Map<String, Object> boardCache;

    public BoardService(FeedService feed, AnalysisService analysis, DbService db, FundService fundService) {
        this.feed = feed;
        this.analysis = analysis;
        this.db = db;
        this.fundService = fundService;
    }

    public Map<String, Object> buildBoard(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && boardCache != null && now - boardTs < BOARD_TTL_MS) return boardCache;
        synchronized (this) {
            if (!force && boardCache != null && System.currentTimeMillis() - boardTs < BOARD_TTL_MS) {
                return boardCache;
            }
            long t0 = System.currentTimeMillis();
            BuildResult r = buildInternal();
            boardTs = System.currentTimeMillis();
            boardCache = r.payload;
            persistAsync(r.payload, r.klines);
            System.out.printf("[board] 总计 %.2fs%n", (boardTs - t0) / 1000.0);
            return r.payload;
        }
    }

    private record BuildResult(Map<String, Object> payload, Map<String, List<Object[]>> klines) {}

    @SuppressWarnings("unchecked")
    private BuildResult buildInternal() {
        Set<String> hidden = new LinkedHashSet<>(db.hiddenBuiltins());
        List<Map<String, Object>> custom = db.customInstruments();
        Set<String> builtinCodes = new LinkedHashSet<>();
        for (Universe.Group g : Universe.UNIVERSE.values()) {
            for (Universe.Item it : g.items()) builtinCodes.add(it.code());
        }
        List<String> customCodes = new ArrayList<>();
        for (Map<String, Object> it : custom) {
            String code = (String) it.get("code");
            if (!builtinCodes.contains(code)) customCodes.add(code);
        }

        List<String> allCodes = new ArrayList<>(Universe.allCodes());
        allCodes.addAll(customCodes);
        // 场外基金报价单独走 FundService，其余走腾讯行情
        List<String> stockCodes = new ArrayList<>();
        List<String> fundCodes = new ArrayList<>();
        for (String c : allCodes) (FundService.isFund(c) ? fundCodes : stockCodes).add(c);
        long t0 = System.currentTimeMillis();
        Map<String, Map<String, Object>> quotes = feed.fetchQuotes(stockCodes);
        quotes.putAll(fundService.fetchQuotes(fundCodes));
        long t1 = System.currentTimeMillis();
        System.out.printf("[board] quotes %d/%d (股票%d 基金%d) 用时 %.2fs%n",
                quotes.size(), allCodes.size(), stockCodes.size(), fundCodes.size(), (t1 - t0) / 1000.0);

        // K 线并行抓取（带缓存）。TTL 加随机抖动，避免 52 只同时过期触发数据源限流
        Map<String, List<Object[]>> klines = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String c : allCodes) {
                long jitter = (c.hashCode() & 0x7fffffffL) % 240_000;
                futures.add(klinePool.submit(() ->
                        klines.put(c, feed.fetchKline(c, 260, FeedService.KLINE_TTL_MS + jitter))));
            }
            for (Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
        }
        long t2 = System.currentTimeMillis();
        long okCount = klines.values().stream().filter(v -> v != null && !v.isEmpty()).count();
        System.out.printf("[board] klines 远端%d条 用时 %.2fs%n", okCount, (t2 - t1) / 1000.0);

        // 自定义标的按分组挂载
        Map<String, List<Map<String, Object>>> customByGrp = new LinkedHashMap<>();
        Set<String> customCodeSet = new LinkedHashSet<>();
        for (Map<String, Object> it : custom) {
            String grp = it.get("grp") != null ? (String) it.get("grp") : "a";
            customByGrp.computeIfAbsent(grp, k -> new ArrayList<>()).add(it);
            customCodeSet.add((String) it.get("code"));
        }

        Map<String, Object> groups = new LinkedHashMap<>();
        for (var gEntry : Universe.UNIVERSE.entrySet()) {
            String gkey = gEntry.getKey();
            Universe.Group g = gEntry.getValue();
            List<Object[]> bench = klines.getOrDefault(g.benchmark(), List.of());
            List<Map<String, Object>> items = new ArrayList<>();

            for (Universe.Item it : g.items()) {
                if (hidden.contains(it.code())) continue;
                Map<String, Object> q = quotes.get(it.code());
                if (q == null) continue;
                Map<String, Object> item = makeItem(it.code(), q, it.name(), it.kind(), gkey,
                        klines.get(it.code()), bench, it.code().equals(g.benchmark()));
                if (customCodeSet.contains(it.code())) item.put("custom", true);
                items.add(item);
            }
            // 用户自定义标的
            for (Map<String, Object> it : customByGrp.getOrDefault(gkey, List.of())) {
                String code = (String) it.get("code");
                Map<String, Object> q = quotes.get(code);
                boolean exists = items.stream().anyMatch(i -> code.equals(i.get("code")));
                if (q == null || exists) continue;
                Map<String, Object> item = makeItem(code, q, (String) it.get("name"),
                        (String) it.get("kind"), gkey, klines.get(code),
                        klines.getOrDefault(g.benchmark(), List.of()), false);
                item.put("custom", true);
                items.add(item);
            }

            List<Double> pcts = new ArrayList<>();
            int up = 0, down = 0, flat = 0, okAna = 0;
            double r20Sum = 0;
            for (Map<String, Object> i : items) {
                boolean isIndex = "index".equals(i.get("kind"));
                if (!isIndex) {
                    double pct = U.num(i.get("pct"));
                    pcts.add(pct);
                    if (pct > 0) up++;
                    else if (pct < 0) down++;
                    else flat++;
                }
                Map<String, Object> a = castMap(i.get("ana"));
                if (!isIndex && a != null && Boolean.TRUE.equals(a.get("ok"))) {
                    okAna++;
                    r20Sum += U.num(a.get("r20"));
                }
            }
            double avgPct = pcts.isEmpty() ? 0 : pcts.stream().mapToDouble(Double::doubleValue).sum() / pcts.size();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("avg_pct", U.round(avgPct, 2));
            stats.put("up", up);
            stats.put("down", down);
            stats.put("flat", flat);
            stats.put("avg_r20", U.round(okAna > 0 ? r20Sum / okAna : 0, 2));

            Map<String, Object> gOut = new LinkedHashMap<>();
            gOut.put("label", g.label());
            gOut.put("benchmark", g.benchmark());
            gOut.put("items", items);
            gOut.put("stats", stats);
            groups.put(gkey, gOut);
        }

        // 全市场信号排序
        List<Map<String, Object>> pool = new ArrayList<>();
        for (var gEntry : groups.entrySet()) {
            String gkey = gEntry.getKey();
            Map<String, Object> g = castMap(gEntry.getValue());
            for (Object o : (List<?>) g.get("items")) {
                Map<String, Object> i = castMap(o);
                Map<String, Object> a = castMap(i.get("ana"));
                if (a == null || !Boolean.TRUE.equals(a.get("ok"))) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("code", i.get("code"));
                p.put("name", i.get("name"));
                p.put("group", gkey);
                p.put("group_label", g.get("label"));
                p.put("kind", i.get("kind"));
                p.put("price", i.get("price"));
                p.put("pct", i.get("pct"));
                p.put("currency", i.get("currency"));
                p.put("score", a.get("score"));
                p.put("signal", a.get("signal"));
                p.put("signal_cls", a.get("signal_cls"));
                p.put("r20", a.get("r20"));
                p.put("rsi", a.get("rsi"));
                p.put("rs", a.get("rs"));
                p.put("conclusion", a.get("conclusion"));
                pool.add(p);
            }
        }
        pool.sort(Comparator.comparingDouble((Map<String, Object> x) -> U.num(x.get("score"))).reversed());
        List<Map<String, Object>> top = pool.subList(0, Math.min(8, pool.size()));
        int from = Math.max(0, pool.size() - 8);
        List<Map<String, Object>> bottom = new ArrayList<>(pool.subList(from, pool.size()));
        java.util.Collections.reverse(bottom);

        // 相关性矩阵
        LinkedHashMap<String, List<Double>> series = new LinkedHashMap<>();
        for (String[] pair : Universe.CORR_SET) {
            List<Object[]> bars = klines.getOrDefault(pair[0], List.of());
            if (bars.size() > 25) {
                List<Double> closes = new ArrayList<>(bars.size());
                for (Object[] b : bars) closes.add(U.num(b[2]));
                series.put(pair[1], closes);
            }
        }
        Map<String, Object> corr = analysis.correlation(series);

        // 行业板块
        long t3 = System.currentTimeMillis();
        List<Map<String, Object>> sectors = feed.fetchSectors();
        System.out.printf("[board] sectors %d个 用时 %.2fs%n", sectors.size(),
                (System.currentTimeMillis() - t3) / 1000.0);
        List<Map<String, Object>> secUp = new ArrayList<>(sectors);
        secUp.sort(Comparator.comparingDouble((Map<String, Object> s) -> U.num(s.get("pct"))).reversed());
        List<Map<String, Object>> secDown = new ArrayList<>(sectors);
        secDown.sort(Comparator.comparingDouble(s -> U.num(s.get("pct"))));

        Map<String, Object> sectorsOut = new LinkedHashMap<>();
        sectorsOut.put("up", secUp.subList(0, Math.min(14, secUp.size())));
        sectorsOut.put("down", secDown.subList(0, Math.min(10, secDown.size())));
        sectorsOut.put("count", sectors.size());

        // 核心指数
        List<Object> indices = new ArrayList<>();
        for (Object gObj : groups.values()) {
            Map<String, Object> g = castMap(gObj);
            for (Object iObj : (List<?>) g.get("items")) {
                Map<String, Object> i = castMap(iObj);
                if ("index".equals(i.get("kind"))) indices.add(i);
            }
        }
        Map<String, Object> om = new LinkedHashMap<>();
        om.put("indices", indices);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        payload.put("sessions", feed.sessionState());
        payload.put("groups", groups);
        payload.put("om", om);
        Map<String, Object> ranking = new LinkedHashMap<>();
        ranking.put("top", top);
        ranking.put("bottom", bottom);
        ranking.put("all", pool);
        payload.put("ranking", ranking);
        payload.put("corr", corr);
        payload.put("sectors", sectorsOut);
        payload.put("watch", db.watchCodes());
        payload.put("custom_count", custom.size());
        payload.put("hidden", db.hiddenBuiltins());
        Map<String, Object> dbOut = new LinkedHashMap<>();
        dbOut.put("ready", db.ready());
        payload.put("db", dbOut);
        return new BuildResult(payload, klines);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private Map<String, Object> makeItem(String code, Map<String, Object> q, String fallbackName,
                                         String kind, String gkey, List<Object[]> bars,
                                         List<Object[]> bench, boolean isBench) {
        List<Object[]> safeBars = bars != null ? bars : List.of();
        Map<String, Object> ana = analysis.analyze(safeBars, isBench ? null : bench);
        List<Double> spark = new ArrayList<>();
        for (Object[] b : safeBars.subList(Math.max(0, safeBars.size() - 45), safeBars.size())) {
            spark.add(U.round(U.num(b[2]), 4));
        }
        Map<String, Object> item = new LinkedHashMap<>(q);
        String name = q.get("name") != null && !String.valueOf(q.get("name")).isEmpty()
                ? (String) q.get("name") : fallbackName;
        item.put("name", name);
        item.put("kind", kind);
        item.put("group", gkey);
        item.put("spark", spark);
        item.put("ana", ana);
        return item;
    }

    // ---------------------------------------------------------------- 异步落库
    /** 把一轮行情/评分异步写库，绝不阻塞接口响应。series 复用 board 已抓的 K 线。 */
    private void persistAsync(Map<String, Object> payload, Map<String, List<Object[]>> klines) {
        if (!db.ready() || !persistBusy.compareAndSet(false, true)) return;
        persistPool.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<Map<String, Object>> items = new ArrayList<>();
                Map<String, Object> groups = castMap(payload.get("groups"));
                for (Object gObj : groups.values()) {
                    Map<String, Object> g = castMap(gObj);
                    for (Object iObj : (List<?>) g.get("items")) {
                        items.add(castMap(iObj));
                    }
                }
                db.upsertInstruments(items);
                int nK = db.upsertKlines(klines);
                int nS = db.insertSnapshots(items, 180);
                int nSc = db.upsertScores(items, LocalDate.now().toString());
                if (nK > 0 || nS > 0 || nSc > 0) {
                    System.out.printf("[db] 落库 日线+%d 快照+%d 评分+%d 用时 %.2fs%n",
                            nK, nS, nSc, (System.currentTimeMillis() - t0) / 1000.0);
                }
            } catch (Exception e) {
                System.out.println("[db] 落库失败：" + e.getMessage());
            } finally {
                persistBusy.set(false);
            }
        });
    }
}
