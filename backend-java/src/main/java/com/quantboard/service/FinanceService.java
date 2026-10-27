package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.feed.HttpFetch;
import com.quantboard.feed.Universe;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 季度财报：东财 datacenter F10 接口。
 * A股=RPT_LICO_FN_CPD（业绩报表）；港股=RPT_HKF10_FN_MAININDICATOR（主要指标）；
 * 美股=RPT_USF10_FN_INCOME（利润表，科目×报告期结构，取单季报聚合）。
 * 仅个股有财报，指数 / ETF 返回空列表。结果缓存 30 分钟。
 */
@Service
public class FinanceService {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String DC_WEB = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String DC = "https://datacenter.eastmoney.com/securities/api/data/v1/get";
    private static final String REF = "https://emweb.securities.eastmoney.com/";
    private static final long TTL_MS = 30 * 60_000L;

    private record Cache(List<Map<String, Object>> rows, long at) {}

    private final ConcurrentHashMap<String, Cache> cache = new ConcurrentHashMap<>();

    /** 返回近 8 个报告期，字段：period/revenue/revenueYoy/profit/profitYoy/grossMargin/netMargin/roe/eps/bps/currency。 */
    public List<Map<String, Object>> quarterly(String code) {
        String[] cls = Universe.classifyCode(code);
        if (!"stock".equals(cls[1])) return List.of();
        long now = System.currentTimeMillis();
        Cache c = cache.get(code);
        if (c != null && now - c.at < TTL_MS) return c.rows();
        List<Map<String, Object>> rows = switch (cls[0]) {
            case "a" -> fetchA(code.substring(2));
            case "hk" -> fetchHk(code.substring(2));
            case "us" -> fetchUs(code.substring(2));
            default -> List.of();
        };
        cache.put(code, new Cache(rows, now));
        return rows;
    }

    private JsonNode getData(String url) throws IOException {
        String body = HttpFetch.getUtf8(url, REF);
        JsonNode root = M.readTree(body);
        JsonNode data = root.path("result").path("data");
        return data.isArray() ? data : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Double d(JsonNode row, String field) {
        JsonNode v = row.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    private static String s(JsonNode row, String field) {
        JsonNode v = row.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static Map<String, Object> row(String period, String currency,
            Double revenue, Double revenueYoy, Double profit, Double profitYoy,
            Double grossMargin, Double netMargin, Double roe, Double eps, Double bps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", period);
        m.put("currency", currency);
        m.put("revenue", revenue);
        m.put("revenueYoy", revenueYoy);
        m.put("profit", profit);
        m.put("profitYoy", profitYoy);
        m.put("grossMargin", grossMargin);
        m.put("netMargin", netMargin);
        m.put("roe", roe);
        m.put("eps", eps);
        m.put("bps", bps);
        return m;
    }

    /* ---------------- A 股 / ETF（业绩报表） ---------------- */
    private List<Map<String, Object>> fetchA(String secCode) {
        String url = DC_WEB + "?reportName=RPT_LICO_FN_CPD&columns=ALL&pageSize=8"
                + "&sortColumns=REPORTDATE&sortTypes=-1"
                + "&filter=" + enc("(SECURITY_CODE=\"" + secCode + "\")");
        try {
            JsonNode data = getData(url);
            if (data == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode r : data) {
                String period = s(r, "DATATYPE");
                if (period == null || period.isEmpty()) {
                    String rd = s(r, "REPORTDATE");
                    period = rd != null ? rd.substring(0, 10) : "?";
                }
                Double rev = d(r, "TOTAL_OPERATE_INCOME"), prof = d(r, "PARENT_NETPROFIT");
                // 该数据集无净利率字段，用 净利/营收 自算（与毛利率口径一致，均为累计报告期）
                Double netMargin = (rev != null && prof != null && rev != 0)
                        ? Math.round(prof / rev * 10000) / 100.0 : null;
                out.add(row(period, "CNY",
                        rev, d(r, "YSTZ"),
                        prof, d(r, "SJLTZ"),
                        d(r, "XSMLL"), netMargin, d(r, "WEIGHTAVG_ROE"),
                        d(r, "BASIC_EPS"), d(r, "BPS")));
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /* ---------------- 港股（主要财务指标） ---------------- */
    private List<Map<String, Object>> fetchHk(String secCode) {
        String url = DC + "?reportName=RPT_HKF10_FN_MAININDICATOR&columns=ALL&pageSize=8"
                + "&sortColumns=REPORT_DATE&sortTypes=-1"
                + "&filter=" + enc("(SECUCODE=\"" + secCode + ".HK\")");
        try {
            JsonNode data = getData(url);
            if (data == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode r : data) {
                out.add(row(s(r, "REPORT_TYPE"), "HKD",
                        d(r, "OPERATE_INCOME"), d(r, "OPERATE_INCOME_YOY"),
                        d(r, "HOLDER_PROFIT"), d(r, "HOLDER_PROFIT_YOY"),
                        d(r, "GROSS_PROFIT_RATIO"), d(r, "NET_PROFIT_RATIO"),
                        d(r, "ROE_AVG"), d(r, "BASIC_EPS"), d(r, "BPS")));
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /* ---------------- 美股（利润表：科目 × 报告期，取单季报） ---------------- */
    private List<Map<String, Object>> fetchUs(String ticker) {
        String url = DC + "?reportName=RPT_USF10_FN_INCOME&columns=ALL&pageSize=400"
                + "&sortColumns=REPORT_DATE&sortTypes=-1"
                + "&filter=" + enc("(SECURITY_CODE=\"" + ticker.toUpperCase() + "\")");
        try {
            JsonNode data = getData(url);
            if (data == null) return List.of();
            // 报告期 → 期间科目表
            Map<String, Map<String, Double>> periods = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            for (JsonNode r : data) {
                if (!"单季报".equals(s(r, "REPORT_TYPE"))) continue;
                String date = s(r, "REPORT_DATE");
                if (date == null) continue;
                periods.computeIfAbsent(date, k -> new LinkedHashMap<>());
                String item = s(r, "ITEM_NAME");
                Double amt = d(r, "AMOUNT");
                Double yoy = d(r, "YOY_RATIO");
                if (item == null || amt == null) continue;
                Map<String, Double> m = periods.get(date);
                m.put(item, amt);
                if (yoy != null) m.put(item + "|yoy", yoy);
                String label = s(r, "REPORT_TYPE_DETAILS");
                if (label != null) labels.put(date, label);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            int taken = 0;
            for (Map.Entry<String, Map<String, Double>> e : periods.entrySet()) {
                if (taken >= 8) break;
                Map<String, Double> m = e.getValue();
                Double revenue = m.get("营业收入");
                if (revenue == null || revenue == 0) continue;
                Double gross = m.get("毛利");
                Double profit = m.get("归属于母公司股东净利润");
                Double profitYoy = m.get("归属于母公司股东净利润|yoy");
                if (profit == null) {
                    profit = m.get("净利润");
                    profitYoy = m.get("净利润|yoy");
                }
                taken++;
                Map<String, Object> row = row(
                        labels.getOrDefault(e.getKey(), e.getKey().substring(0, 10)), "USD",
                        revenue, m.get("营业收入|yoy"),
                        profit, profitYoy,
                        gross != null ? gross / revenue * 100 : null,
                        profit != null ? profit / revenue * 100 : null,
                        null, m.get("基本每股收益-普通股"), null);
                out.add(row);
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
