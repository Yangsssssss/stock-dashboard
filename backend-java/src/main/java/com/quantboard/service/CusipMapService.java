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

    /**
     * OpenFIGI 对这些 CUSIP 只登记了欧洲 / 加拿大挂牌，或干脆查不到（数据缺失），
     * 但公司明确、代码已在行情源逐个验证过，这里直接给答案，避免退化成「无代码」。
     * 逐条核对方式：腾讯行情 qt.gtimg.cn 返回的中文简称与 13F 发行人名称一致才算通过。
     */
    private static final Map<String, String> MANUAL = Map.ofEntries(
            Map.entry("14888U101", "CPRX"),    // Catalyst Pharmaceuticals（只登记了法兰克福 CPRXEUR/CPRXGBP）
            Map.entry("530307305", "LBRDK"),   // Liberty Broadband C 类股
            Map.entry("68634K106", "ORLA"),    // Orla Mining（只登记了欧洲代码 OLA）
            Map.entry("947890109", "WBS"),     // Webster Financial（只登记了德国代码 WED）
            Map.entry("G25508105", "CRH"),     // CRH 水泥
            Map.entry("H01301128", "ALC"),     // 爱尔康
            Map.entry("744573106", "PEG"),     // 公共服务企业集团 PSEG
            Map.entry("55261F104", "MTB"),     // M&T 银行
            Map.entry("297178105", "ESS"),     // Essex 房地产信托
            Map.entry("G0450A105", "ACGL"),    // 阿奇资本 Arch Capital
            Map.entry("46266C105", "IQV")      // IQVIA 控股
    );

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
                    String manual = MANUAL.get(cusip);
                    String ticker = manual != null ? manual : pickTicker(res.path(j));
                    String name = pickName(res.path(j));
                    save(cusip, ticker, name, "openfigi");
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

    /**
     * 兜底：OpenFIGI 查不到美国挂牌的 CUSIP（多为爱尔兰/泽西/荷兰注册的 G、H 开头编号，
     * 以及各类注册在境外的运营主体），改用东方财富的搜索接口按名称反查。
     *
     * 实测 807 个这种 CUSIP 里能再捞回 232 个，且名称逐条对得上
     * （希捷科技→STX、江森自控→JCI、阿斯麦→ASML、皇家加勒比→RCL…）。
     * 中文名命中率明显高于 13F 的英文发行名，所以先试中文名（去掉括号补充说明）。
     */
    public Map<String, Object> fillBySearch(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        int cap = limit <= 0 ? 100 : Math.min(limit, 300);
        List<Map<String, Object>> todo;
        try {
            todo = jdbc.queryForList(
                    "SELECT t.c AS cusip, MAX(t.name) AS en, MAX(s.name_cn) AS cn FROM ( "
                            + "  SELECT SUBSTRING_INDEX(code, '~', 1) AS c, name, value_usd FROM guru_holding "
                            + "  WHERE code NOT LIKE 'us%' "
                            + ") t LEFT JOIN security_cn s ON s.code = t.c "
                            + "LEFT JOIN cusip_ticker m ON m.cusip = t.c "
                            + "WHERE m.cusip IS NULL OR m.ticker = '' "
                            + "GROUP BY t.c ORDER BY MAX(t.value_usd) DESC LIMIT ?", cap);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "查询失败：" + e.getMessage());
            return out;
        }
        int hit = 0;
        for (Map<String, Object> r : todo) {
            String cusip = str(r.get("cusip"));
            String cn = str(r.get("cn")).replaceAll("[（(].*?[）)]", "").trim();
            String en = str(r.get("en"));
            // 13F 的发行名常常又长又带后缀（RENAISSANCERE HLDGS LTD），整串喂给搜索接口一条都搜不到；
            // 逐级缩短成功率明显更高：RENAISSANCERE 能命中 RNR、CHECK POINT 命中 CHKP、ICON PLC 命中 ICLR。
            List<String> tries = new ArrayList<>();
            if (cn.length() >= 2) tries.add(cn);
            if (en.length() >= 2) tries.add(en);
            String[] parts = en.split("\\s+");
            if (parts.length >= 2) tries.add(parts[0] + " " + parts[1]);
            if (parts.length >= 2 && parts[0].length() >= 4) tries.add(parts[0]);

            String ticker = "";
            for (String q : tries) {
                ticker = searchEastmoney(q);
                if (!ticker.isEmpty()) break;
                pause(120);
            }
            save(cusip, ticker, "", "eastmoney");   // 没搜到也记一条负缓存，下次不再重试
            if (!ticker.isEmpty()) hit++;
            pause(150);
        }
        out.put("ok", true);
        out.put("todo", todo.size());
        out.put("hit", hit);
        return out;
    }

    /** 东财搜索建议接口：按名称返回候选证券，只认美股市场（MktNum 105/106/107/153/154）。 */
    private String searchEastmoney(String q) {
        try {
            String url = "https://searchapi.eastmoney.com/api/suggest/get?input="
                    + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=8";
            byte[] raw = HttpFetch.get(url, "https://quote.eastmoney.com/", 12);
            JsonNode root = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            JsonNode data = root.path("QuotationCodeTable").path("Data");
            if (!data.isArray()) return "";
            for (JsonNode it : data) {
                String mkt = it.path("MktNum").asText("");
                if (!"105".equals(mkt) && !"106".equals(mkt) && !"107".equals(mkt)) continue;
                String code = it.path("Code").asText("").trim().toUpperCase();
                if (code.matches("[A-Z][A-Z0-9\\-]{0,6}")) return code;
            }
        } catch (Exception ignore) { }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
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
     * 从单条结果里挑美股代码。
     *
     * 关键：只认美国市场。OpenFIGI 对同一个证券会给加拿大（CP/RC）、德国（GR/GD）、
     * 欧洲（ER）等一堆挂牌记录，早先不限交易所直接取第一条，结果埃克森美孚被取成
     * 加拿大代码 EXMOC、Webster 被取成德国代码 WED。美国市场的 exchCode 是
     * US（综合）/ UN（NYSE）/ UW·UQ（NASDAQ）/ UA…UZ（其他美国交易所）/ MM·MF·MU（市场层级）。
     *
     * 带 `*` 的 ticker（EA*、QRVO*）是 OpenFIGI 的标注，去掉即是正常代码。
     */
    private static String pickTicker(JsonNode item) {
        JsonNode data = item.path("data");
        if (!data.isArray() || data.isEmpty()) return "";
        for (JsonNode d : data) {
            if (!"Equity".equals(d.path("marketSector").asText())) continue;
            if (!isUsExchange(d.path("exchCode").asText(""))) continue;
            String sec = d.path("securityType").asText("");
            if (sec.startsWith("Mutual")) continue;
            if (sec.contains("Preferred") && !sec.contains("ADR")) continue;
            String t = d.path("ticker").asText("").trim().toUpperCase().replace("*", "");
            if (t.isEmpty()) continue;
            return t.replace('/', '-').replace('.', '-');
        }
        return "";
    }

    private static boolean isUsExchange(String exch) {
        if (exch == null || exch.isEmpty()) return false;
        if (exch.startsWith("U")) return true;
        return "MM".equals(exch) || "MF".equals(exch) || "MU".equals(exch);
    }

    private static String pickName(JsonNode item) {
        JsonNode data = item.path("data");
        if (data.isArray() && !data.isEmpty()) {
            String n = data.get(0).path("name").asText("");
            return n.length() > 96 ? n.substring(0, 96) : n;
        }
        return "";
    }

    private void save(String cusip, String ticker, String name, String src) {
        try {
            jdbc.update("INSERT INTO cusip_ticker (cusip, ticker, name, src) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE ticker=VALUES(ticker), name=VALUES(name), src=VALUES(src)",
                    cusip, ticker, name == null ? "" : name, src);
        } catch (Exception ignore) { }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
