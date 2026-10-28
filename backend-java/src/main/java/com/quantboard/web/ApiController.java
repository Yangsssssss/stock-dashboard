package com.quantboard.web;

import com.quantboard.db.DbService;
import com.quantboard.feed.FeedService;
import com.quantboard.feed.FundService;
import com.quantboard.feed.Universe;
import com.quantboard.service.AiService;
import com.quantboard.service.BoardService;
import com.quantboard.service.DividendService;
import com.quantboard.service.FamilyService;
import com.quantboard.service.FinanceService;
import com.quantboard.service.GuruService;
import com.quantboard.service.CusipMapService;
import com.quantboard.service.NameCnService;
import com.quantboard.service.NewsService;
import com.quantboard.service.PerformanceService;
import com.quantboard.service.PositionService;
import com.quantboard.service.TradeService;
import com.quantboard.util.U;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class ApiController {
    private final BoardService board;
    private final FeedService feed;
    private final DbService db;
    private final FinanceService finance;
    private final PositionService positions;
    private final FundService funds;
    private final TradeService trades;
    private final DividendService dividends;
    private final PerformanceService perf;
    private final AiService ai;
    private final NewsService news;
    private final FamilyService family;
    private final GuruService guru;
    private final NameCnService nameCn;
    private final CusipMapService cusipMap;

    public ApiController(BoardService board, FeedService feed, DbService db,
                         FinanceService finance, PositionService positions, FundService funds,
                         TradeService trades, DividendService dividends, PerformanceService perf,
                         AiService ai, NewsService news, FamilyService family, GuruService guru,
                         NameCnService nameCn, CusipMapService cusipMap) {
        this.board = board;
        this.feed = feed;
        this.db = db;
        this.finance = finance;
        this.positions = positions;
        this.funds = funds;
        this.trades = trades;
        this.dividends = dividends;
        this.perf = perf;
        this.ai = ai;
        this.news = news;
        this.family = family;
        this.guru = guru;
        this.nameCn = nameCn;
        this.cusipMap = cusipMap;
    }

    /** 消息中心：政策 + 企业新闻（快讯按关键词与标签归类，叠加持仓/自选公告）。 */
    @GetMapping("/api/news")
    public Map<String, Object> newsFeed(@RequestParam(defaultValue = "0") String force) {
        return news.feed("1".equals(force));
    }

    @GetMapping("/api/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "0") String force) {
        return board.buildBoard("1".equals(force));
    }

    /** 盘中分时：1 分钟价格序列 + 昨收基准 + 时间标签；不支持/未开盘返回空序列。 */
    @GetMapping("/api/trends")
    public Map<String, Object> trends(@RequestParam String code) {
        Map<String, Object> t = feed.fetchTrends(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", codeName(code));
        if (t == null) {
            out.put("prices", List.of());
            out.put("times", List.of());
            out.put("preClose", 0);
        } else {
            out.put("prices", t.get("prices"));
            out.put("times", t.get("times"));
            out.put("preClose", t.get("preClose"));
        }
        return out;
    }

    @GetMapping("/api/kline")
    public Map<String, Object> kline(@RequestParam String code,
                                     @RequestParam(defaultValue = "180") int days,
                                     @RequestParam(defaultValue = "day") String period) {
        String p = period == null ? "day" : period.trim().toLowerCase();
        List<Object[]> bars;
        String adj = "qfq";
        switch (p) {
            case "month", "quarter", "year" -> {
                FeedService.MonthlyBars m = feed.fetchMonthly(code);
                bars = switch (p) {
                    case "quarter" -> FeedService.aggregateBars(m.bars(), 2);
                    case "year" -> FeedService.aggregateBars(m.bars(), 3);
                    default -> m.bars();
                };
                adj = m.adj();
            }
            // 日线：常规源只回约 320 根（新浪 datalen=320），要更长的窗口走全量日线
            default -> bars = days > 320 ? feed.fetchKlineAll(code) : feed.fetchKline(code, 400);
        }
        int from = Math.max(0, bars.size() - days);
        List<Object[]> seg = bars.subList(from, bars.size());
        List<Double> closes = new ArrayList<>();
        for (Object[] b : seg) closes.add(U.num(b[2]));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", codeName(code));
        out.put("adj", adj);           // qfq=前复权 / none=不复权（长周期兜底源）
        out.put("bars", seg);
        out.put("ma5", U.sma(closes, 5));
        out.put("ma10", U.sma(closes, 10));
        out.put("ma20", U.sma(closes, 20));
        out.put("ma60", U.sma(closes, 60));
        return out;
    }

    private String codeName(String code) {
        String builtin = Universe.codeName(code);
        if (builtin != null) return builtin;
        for (Map<String, Object> it : db.customInstruments()) {
            if (code.equals(it.get("code"))) return (String) it.get("name");
        }
        return code;
    }

    /** 当天分时（东财 trends2，1 分钟线）：市场表格迷你走势图按需拉取，服务端缓存 60 秒。 */
    @GetMapping("/api/trend")
    public Map<String, Object> trend(@RequestParam String code) {
        Map<String, Object> tr = feed.fetchTrends(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("trend", tr != null ? tr.get("prices") : List.of());
        out.put("preClose", tr != null ? tr.get("preClose") : null);
        return out;
    }

    @GetMapping("/api/finance")
    public Map<String, Object> finance(@RequestParam String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("rows", finance.quarterly(code.toLowerCase()));
        return out;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>(feed.searchSymbols(q, 8));
        // 基金搜索结果并入（股票不足或无结果时基金候选更有用）
        List<Map<String, Object>> fundItems = funds.searchFunds(q, 5);
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> it : items) seen.add(String.valueOf(it.get("code")));
        for (Map<String, Object> it : fundItems) {
            if (seen.add(String.valueOf(it.get("code")))) items.add(it);
        }
        out.put("items", items);
        return out;
    }

    @GetMapping("/api/watchlist")
    public Map<String, Object> watchlist() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", db.watchlist());
        out.put("ready", db.ready());
        return out;
    }

    @PostMapping("/api/watchlist")
    public ResponseEntity<Map<String, Object>> watchlistPost(@RequestBody Map<String, Object> body) {
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String action = String.valueOf(body.getOrDefault("action", "add"));
        // 批量移除：{"action":"remove_many","codes":["sh600036","f003847",...]}
        if ("remove_many".equals(action)) {
            int removed = 0;
            Object codesObj = body.get("codes");
            if (codesObj instanceof List<?> list) {
                for (Object o : list) {
                    String c = String.valueOf(o).trim();
                    if (!c.matches("[a-z]{2}[A-Za-z0-9.]{1,12}") && !FundService.isFund(c)) continue;
                    if (db.watchRemove(c)) removed++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("removed", removed);
            out.put("watch", db.watchCodes());
            return ResponseEntity.ok(out);
        }
        if (!code.matches("[a-z]{2}[A-Za-z0-9.]{1,12}") && !FundService.isFund(code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid code"));
        }
        boolean ok = "remove".equals(action)
                ? db.watchRemove(code)
                : db.watchAdd(code, (String) body.get("note"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("watch", db.watchCodes());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/history")
    public Map<String, Object> history(@RequestParam String code,
                                       @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("points", db.scoreHistory(code, days));
        return out;
    }

    @GetMapping("/api/movers")
    public Map<String, Object> movers() {
        return db.scoreMovers();
    }

    @GetMapping("/api/db")
    public Map<String, Object> dbStats() {
        return db.stats();
    }

    // ---------------------------------------------------------------- 持仓
    @GetMapping("/api/positions")
    public Map<String, Object> positions() {
        return positions.list();
    }

    @PostMapping("/api/positions")
    public ResponseEntity<Map<String, Object>> positionsPost(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        if ("remove".equals(action)) {
            String posKey = String.valueOf(body.getOrDefault("pos_key", "")).trim();
            if (posKey.contains("|")) {
                String[] parts = posKey.split("\\|", 2);
                boolean ok = positions.removePosition(parts[0], parts[1]);
                family.invalidateSec();
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", ok)));
            }
            // 兼容旧调用：按单笔流水 id 删除
            long id;
            try { id = Long.parseLong(String.valueOf(body.get("id"))); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id / pos_key")); }
            boolean ok = trades.remove(id);
            family.invalidateSec();
            return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", ok)));
        }
        String broker = String.valueOf(body.getOrDefault("broker", "")).trim();
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String side = String.valueOf(body.getOrDefault("side", "")).trim().toUpperCase();   // 空=建仓；BUY=再买入；SELL=卖出
        double qty = U.num(body.get("qty"));
        double cost = U.num(body.get("cost"));
        if (qty <= 0 || cost < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "数量需大于 0，成本不小于 0"));
        }
        if ("SELL".equals(side) && cost <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "卖出需填写成交价"));
        }
        Map<String, Object> r = positions.upsert(broker, code, name, qty, cost,
                String.valueOf(body.getOrDefault("trade_date", "")), side);
        family.invalidateSec();
        return r.getOrDefault("ok", false).equals(true)
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    // ---------------------------------------------------------------- 家庭资产
    /** 家庭总览：证券市值（流水推导）+ 非证券台账 + 负债 + 净值 + 目标偏离。 */
    @GetMapping("/api/family")
    public Map<String, Object> family() {
        return family.overview();
    }

    @PostMapping("/api/family")
    public ResponseEntity<Map<String, Object>> familyPost(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        switch (action) {
            case "remove" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", family.remove(id))));
            }
            case "update" -> {
                Map<String, Object> r = family.update(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            default -> {
                Map<String, Object> r = family.add(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
        }
    }

    /** 保存目标配置占比：{"targets":{"a":40,"hk":20,"us":10,"etf_fund":20,"cash_other":10}} */
    @PostMapping("/api/family/target")
    public ResponseEntity<Map<String, Object>> familyTarget(@RequestBody Map<String, Object> body) {
        Map<String, Object> r = family.saveTargets(body);
        return r.getOrDefault("ok", false).equals(true)
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    // ------------------------------------------------------- 家庭成员 / 收支
    @PostMapping("/api/family/member")
    public ResponseEntity<Map<String, Object>> familyMember(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        switch (action) {
            case "remove" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", family.memberRemove(id))));
            }
            case "update" -> {
                Map<String, Object> r = family.memberUpdate(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            default -> {
                Map<String, Object> r = family.memberAdd(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
        }
    }

    @PostMapping("/api/family/flow")
    public ResponseEntity<Map<String, Object>> familyFlow(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        switch (action) {
            case "remove" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", family.flowRemove(id))));
            }
            case "update" -> {
                Map<String, Object> r = family.flowUpdate(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            default -> {
                Map<String, Object> r = family.flowAdd(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
        }
    }

    // ---------------------------------------------------------------- 大佬持仓（13F / 主权基金）
    /** 跟踪机构总览：机构列表 + 各报告期 + 共识榜。 */
    @GetMapping("/api/guru")
    public Map<String, Object> guruOverview() {
        return guru.overview();
    }

    /** 持仓明细：investor=0 全部机构；quarter 空 = 各机构各自最新一期；分页返回。 */
    @GetMapping("/api/guru/holdings")
    public Map<String, Object> guruHoldings(@RequestParam(defaultValue = "0") long investor,
                                            @RequestParam(defaultValue = "") String quarter,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "100") int size) {
        return guru.holdings(investor, quarter, page, size);
    }

    /** 环比调仓：本期 vs 上期的新建 / 加仓 / 减仓 / 清仓。investor 必须指定一家机构。 */
    @GetMapping("/api/guru/changes")
    public Map<String, Object> guruChanges(@RequestParam(defaultValue = "0") long investor,
                                           @RequestParam(defaultValue = "") String quarter) {
        return guru.changes(investor, quarter);
    }

    /** 机构 / 持仓 CRUD。action: add_investor / update_investor / remove_investor / add / remove。 */
    @PostMapping("/api/guru")
    public ResponseEntity<Map<String, Object>> guruPost(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add_investor"));
        switch (action) {
            case "add_investor" -> {
                Map<String, Object> r = guru.addInvestor(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            case "update_investor" -> {
                Map<String, Object> r = guru.updateInvestor(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            case "remove_investor" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", guru.removeInvestor(id))));
            }
            case "add" -> {
                Map<String, Object> r = guru.addHolding(body);
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
            case "remove" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", guru.removeHolding(id))));
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error", "未知 action: " + action));
            }
        }
    }

    /**
     * 从 SEC EDGAR 拉取该机构的 13F-HR（同步，约 5–20 秒）。
     * skip=0 取最新一期；skip=1 取上一期（补历史，供环比调仓使用）。
     */
    @PostMapping("/api/guru/import")
    public ResponseEntity<Map<String, Object>> guruImport(@RequestBody Map<String, Object> body) {
        long id;
        try { id = Long.parseLong(String.valueOf(body.get("id"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
        int skip = (int) U.num(body.get("skip"));
        Map<String, Object> r = guru.import13f(id, Math.max(0, Math.min(skip, 12)));
        return r.getOrDefault("ok", false).equals(true)
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    /** 补齐持仓标的中文名（腾讯行情批量取名，已收录的跳过，可反复调用）。 */
    @PostMapping("/api/guru/names")
    public Map<String, Object> guruNames() {
        return nameCn.fillMissing();
    }

    /**
     * 补齐 13F 的 CUSIP → 美股代码映射（OpenFIGI 权威映射，已查过的跳过）。
     * 单个 HTTP 请求只处理 limit 个，剩余量看返回的 left，循环调用直到 left=0。
     */
    @PostMapping("/api/guru/cusip-map")
    public Map<String, Object> guruCusipMap(@RequestParam(defaultValue = "100") int limit) {
        return cusipMap.fillMissing(limit);
    }

    /** OpenFIGI 查不到美国挂牌的 CUSIP，改用东财搜索接口按中文名 / 13F 发行名反查。 */
    @PostMapping("/api/guru/cusip-search")
    public Map<String, Object> guruCusipSearch(@RequestParam(defaultValue = "100") int limit) {
        return cusipMap.fillBySearch(limit);
    }

    /** 把存量持仓里仍以 CUSIP 当代码的行改写成正常美股代码，并合并撞车的重复行。可重复调用。 */
    @PostMapping("/api/guru/remap")
    public Map<String, Object> guruRemap() {
        return guru.remapCusip();
    }

    // ---------------------------------------------------------------- 交易流水
    @GetMapping("/api/trades")
    public Map<String, Object> tradesList(@RequestParam(required = false) String code,
                                          @RequestParam(required = false) String broker,
                                          @RequestParam(defaultValue = "300") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trades", trades.list(code, broker, limit));
        return out;
    }

    @PostMapping("/api/trades")
    public ResponseEntity<Map<String, Object>> tradesPost(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        switch (action) {
            case "remove" -> {
                long id;
                try { id = Long.parseLong(String.valueOf(body.get("id"))); }
                catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
                boolean ok = trades.remove(id);
                family.invalidateSec();
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", ok)));
            }
            case "remove_position" -> {
                String broker = String.valueOf(body.getOrDefault("broker", "")).trim();
                String code = String.valueOf(body.getOrDefault("code", "")).trim();
                int n = trades.removePosition(broker, code);
                family.invalidateSec();
                return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", n > 0, "removed", n)));
            }
            default -> {
                Map<String, Object> r = trades.add(body);
                family.invalidateSec();
                return r.getOrDefault("ok", false).equals(true)
                        ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
            }
        }
    }

    /** 收益引擎：总览、区间收益、净值曲线、标的贡献、最大回撤、基准对比。 */
    @GetMapping("/api/performance")
    public Map<String, Object> performance(@RequestParam(defaultValue = "365") int days,
                                           @RequestParam(defaultValue = "") String benchmark) {
        return perf.report(days, benchmark);
    }

    /** 手动落一次当日快照（正常由 /api/performance 自动触发）。 */
    @PostMapping("/api/performance/snapshot")
    public Map<String, Object> performanceSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("summary", perf.snapshot());
        return out;
    }

    /** DeepSeek 持仓分析：refresh=1 绕过 30 分钟缓存强制重算。 */
    @GetMapping("/api/positions/ai")
    public Map<String, Object> positionsAi(@RequestParam(defaultValue = "0") String refresh) {
        return ai.analyze("1".equals(refresh));
    }

    /** 券商截图 OCR：只解析返回候选，不入库（前端确认后再逐行调 add）。 */
    @PostMapping("/api/positions/ocr")
    public ResponseEntity<Map<String, Object>> positionsOcr(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未收到图片"));
        }
        String ext = "png";
        String n = file.getOriginalFilename();
        if (n != null && n.contains(".")) ext = n.substring(n.lastIndexOf('.') + 1).toLowerCase();
        Map<String, Object> r = positions.parseScreenshot(fileBytes(file), ext);
        if (!r.getOrDefault("ok", false).equals(true)) {
            r.putIfAbsent("error", "未能从截图中识别出持仓行（请确认是持仓列表页截图）");
        }
        return ResponseEntity.ok(r);
    }

    private static byte[] fileBytes(MultipartFile f) {
        try { return f.getBytes(); } catch (Exception e) { return null; }
    }

    @PostMapping("/api/instruments")
    public ResponseEntity<Map<String, Object>> instrumentsPost(@RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "add"));
        if ("restore".equals(action)) {
            int n = db.instrumentRestoreAll();
            return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", true, "restored", n)));
        }
        // 批量移出看板：{"action":"remove_many","codes":["sh600036","f003847",...]}
        // 注意：必须在单代码格式校验之前（remove_many 不带 code 字段）
        if ("remove_many".equals(action)) {
            int removed = 0;
            Object codesObj = body.get("codes");
            if (codesObj instanceof List<?> list) {
                for (Object o : list) {
                    String c = String.valueOf(o).trim().toLowerCase();
                    // 只接受合法代码，防止未知代码被 instrumentRemove 写成 hidden 垃圾行
                    if (!c.matches("(sh|sz|hk|us)[A-Za-z0-9.]{1,10}") && !FundService.isFund(c)) continue;
                    if (!db.instrumentRemove(c).isEmpty()) removed++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("removed", removed);
            return ResponseEntity.ok(out);
        }
        String code = String.valueOf(body.getOrDefault("code", "")).trim().toLowerCase();
        if (!code.matches("(sh|sz|hk|us)[A-Za-z0-9.]{1,10}") && !FundService.isFund(code)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "无效代码，格式如 sh600036 / hk09988 / usNVDA / sh510300 / f003847（基金）"));
        }
        if ("remove".equals(action)) {
            String mode = db.instrumentRemove(code);
            if (mode.isEmpty()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "移除失败"));
            }
            return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", true, "mode", mode)));
        }
        // 添加：先验证行情是否可取（基金走净值源）
        Map<String, Map<String, Object>> quotes = FundService.isFund(code)
                ? funds.fetchQuotes(List.of(code)) : feed.fetchQuotes(List.of(code));
        Map<String, Object> q = quotes.get(code);
        if (q == null || U.num(q.get("price")) == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "代码 " + code + " 无法取到行情，请确认是否有效"));
        }
        String[] cls = Universe.classifyCode(code);
        String grp = cls[0], kind = cls[1], market = cls[2], currency = cls[3];
        String name = q.get("name") != null && !String.valueOf(q.get("name")).isEmpty()
                ? (String) q.get("name") : code;
        boolean ok = db.instrumentAdd(code, name, grp, kind, market, currency);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("code", code);
        out.put("name", name);
        out.put("grp", grp);
        out.put("kind", kind);
        return ResponseEntity.ok(out);
    }
}
