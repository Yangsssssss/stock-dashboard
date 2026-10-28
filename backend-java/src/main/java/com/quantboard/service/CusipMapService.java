package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.feed.HttpFetch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 13F CUSIP → 美股代码映射。
 *
 * 背景：SEC 13F 的 information table 只申报 CUSIP（9 位库斯普编号），从来不写股票代码。
 * 原先只能拿发行人名称去 SEC 的 company_tickers.json 里模糊匹配反查，
 * 而 13F 里的发行名会被截断到 28 个字符（TAIWAN SEMICONDUCTOR MANUFAC / APPLIED MATLS INC），
 * 匹配不上时就把 CUSIP 直接当代码显示，界面上出现「874039100」这种谁也看不懂的数字。
 *
 * 现在改为按 CUSIP 直查 Bloomberg 的 OpenFIGI 公开映射接口（不需要 key），
 * 命中即用，名称匹配只作为兜底。查过但确实没有美股代码的（未上市公司、非美股标的）
 * 记一条 ticker='' 的负缓存，避免每次重复请求。
 */
@Service
public class CusipMapService {

    private static final String URL = "https://api.openfigi.com/v3/mapping";
    /** OpenFIGI 免费额度一次最多 10 个 CUSIP；无 key 时限 25 请求/分钟。 */
    private static final int BATCH = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public CusipMapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 补齐缺失的 CUSIP 映射。一次最多处理 limit 个（每个约 0.3 秒 + 限流间隔），
     * 待处理量大时由调用方分批循环，避免单个 HTTP 请求等太久。
     * 返回 {ok, todo, hit, miss, left}。
     */
    public Map<String, Object> fillMissing(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        int cap = limit <= 0 ? 100 : Math.min(limit, 400);
        List<String> todo;
        try {
            todo = jdbc.queryForList(
                    "SELECT DISTINCT SUBSTRING_INDEX(h.code, '~', 1) AS c FROM guru_holding h "
                            + "LEFT JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE h.code NOT LIKE 'us%' AND m.cusip IS NULL "
                            + "ORDER BY c LIMIT ?", String.class, cap);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "cusip_ticker 表不可用（迁移 V16 未执行）：" + e.getMessage());
            return out;
        }
        int hit = 0;
        int miss = 0;
        int failed = 0;
        for (int i = 0; i < todo.size(); i += BATCH) {
            List<String> batch = todo.subList(i, Math.min(todo.size(), i + BATCH));
            JsonNode res = query(batch);
            if (res == null) {
                failed += batch.size();
            } else {
                for (int j = 0; j < batch.size(); j++) {
                    String cusip = batch.get(j);
                    String ticker = pickTicker(res.path(j));
                    String name = pickName(res.path(j));
                    save(cusip, ticker, name);
                    if (ticker.isEmpty()) miss++; else hit++;
                }
            }
            if (i + BATCH < todo.size()) pause(2600);   // 限流：25 请求/分钟
        }
        out.put("ok", true);
        out.put("todo", todo.size());
        out.put("hit", hit);
        out.put("miss", miss);
        out.put("failed", failed);
        out.put("left", countLeft());
        return out;
    }

    private int countLeft() {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT SUBSTRING_INDEX(h.code, '~', 1)) FROM guru_holding h "
                            + "LEFT JOIN cusip_ticker m ON m.cusip = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE h.code NOT LIKE 'us%' AND m.cusip IS NULL", Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 批量查询，被限流（429）时退避重试。返回与 batch 等长的数组节点，失败返回 null。 */
    private JsonNode query(List<String> batch) {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) body.append(',');
            body.append("{\"idType\":\"ID_CUSIP\",\"idValue\":\"").append(batch.get(i)).append("\"}");
        }
        body.append(']');
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                byte[] raw = HttpFetch.postJson(URL, body.toString(), 30,
                        "User-Agent: QuantBoard Research yangs1997@126.com");
                JsonNode node = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
                return node.isArray() ? node : null;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("429")) {
                    pause(12000);
                } else if (attempt < 2) {
                    pause(4000);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 从单条结果里挑美股代码：优先美国主板（exchCode=US）的普通股 / ETF。
     * BRK/B 这种带斜杠的股份类别统一改成短横线（项目内部代码约定）。
     */
    private static String pickTicker(JsonNode item) {
        JsonNode data = item.path("data");
        if (!data.isArray() || data.isEmpty()) return "";
        String loose = "";
        for (JsonNode d : data) {
            if (!"Equity".equals(d.path("marketSector").asText())) continue;
            String t = d.path("ticker").asText("").trim().toUpperCase();
            if (t.isEmpty()) continue;
            String sec = d.path("securityType").asText("");
            if (sec.startsWith("Mutual") || sec.contains("Preferred")) continue;
            String code = t.replace('/', '-').replace('.', '-');
            if ("US".equals(d.path("exchCode").asText())) return code;
            if (loose.isEmpty()) loose = code;
        }
        return loose;
    }

    private static String pickName(JsonNode item) {
        JsonNode data = item.path("data");
        if (data.isArray() && !data.isEmpty()) {
            String n = data.get(0).path("name").asText("");
            return n.length() > 96 ? n.substring(0, 96) : n;
        }
        return "";
    }

    private void save(String cusip, String ticker, String name) {
        try {
            jdbc.update("INSERT INTO cusip_ticker (cusip, ticker, name, src) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE ticker=VALUES(ticker), name=VALUES(name), src=VALUES(src)",
                    cusip, ticker, name == null ? "" : name, "openfigi");
        } catch (Exception ignore) { }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
