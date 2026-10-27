package com.quantboard.web;

import com.quantboard.db.DbService;
import com.quantboard.feed.FeedService;
import com.quantboard.feed.FundService;
import com.quantboard.feed.Universe;
import com.quantboard.service.BoardService;
import com.quantboard.service.FinanceService;
import com.quantboard.service.PositionService;
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

    public ApiController(BoardService board, FeedService feed, DbService db,
                         FinanceService finance, PositionService positions, FundService funds) {
        this.board = board;
        this.feed = feed;
        this.db = db;
        this.finance = finance;
        this.positions = positions;
        this.funds = funds;
    }

    @GetMapping("/api/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "0") String force) {
        return board.buildBoard("1".equals(force));
    }

    @GetMapping("/api/kline")
    public Map<String, Object> kline(@RequestParam String code,
                                     @RequestParam(defaultValue = "180") int days) {
        List<Object[]> bars = feed.fetchKline(code, 400);
        int from = Math.max(0, bars.size() - days);
        List<Object[]> seg = bars.subList(from, bars.size());
        List<Double> closes = new ArrayList<>();
        for (Object[] b : seg) closes.add(U.num(b[2]));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", codeName(code));
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
            long id;
            try { id = Long.parseLong(String.valueOf(body.get("id"))); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "无效 id")); }
            boolean ok = positions.remove(id);
            return ResponseEntity.ok(new LinkedHashMap<>(Map.of("ok", ok)));
        }
        String broker = String.valueOf(body.getOrDefault("broker", "")).trim();
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        double qty = U.num(body.get("qty"));
        double cost = U.num(body.get("cost"));
        if (qty <= 0 || cost < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "数量需大于 0，成本不小于 0"));
        }
        Map<String, Object> r = positions.upsert(broker, code, name, qty, cost);
        return r.getOrDefault("ok", false).equals(true)
                ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
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
