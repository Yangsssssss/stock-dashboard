package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quantboard.util.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型持仓分析：把实时持仓快照（折人民币口径）交给 DeepSeek，
 * 让模型从配置、集中度、盈亏结构、评分信号背离等角度给出点评。
 * 结果内存缓存 30 分钟，refresh=1 强制重算。只读分析，不落库。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final long CACHE_MS = 30 * 60_000L;

    private static final String SYSTEM_PROMPT = """
            你是一名严谨的组合分析助理，服务于一位同时投资 A 股、港股、美股、ETF 与基金的个人投资者。
            下面是他的实时持仓快照（金额统一折算为人民币，含综合评分与信号）。要求：
            1. 只依据快照中的数据做分析，不要编造快照之外的信息；数据缺失就明确说数据不足。
            2. 直接给结论，量化表述（百分比、金额），不要空话与免责声明式客套。
            3. 输出 Markdown，按以下结构：
            ## 一句话总评
            ## 组合结构（占比集中度、市场/账户分布，从数据里能算多少算多少）
            ## 重点持仓点评（挑占比最大、盈亏最极端、评分信号与盈亏背离的标的，逐个说）
            ## 风险点
            ## 可操作建议（具体到标的与方向，末尾注明仅为模型参考意见，非投资建议）
            """;

    private final PositionService positions;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${quantboard.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;
    @Value("${quantboard.ai.api-key:}")
    private String apiKey;
    @Value("${quantboard.ai.model:deepseek-chat}")
    private String model;

    /** {ts, ok, content, generated_at, model, cached} — 只缓存成功结果。 */
    private volatile Map<String, Object> cache;

    public AiService(PositionService positions) {
        this.positions = positions;
    }

    public Map<String, Object> analyze(boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        String key = apiKey == null ? "" : apiKey.trim();
        out.put("model", model);
        out.put("configured", !key.isEmpty());

        Map<String, Object> hit = cache;
        if (!force && hit != null && System.currentTimeMillis() - (long) hit.get("ts") < CACHE_MS) {
            out.put("ok", true);
            out.put("content", hit.get("content"));
            out.put("generated_at", hit.get("generated_at"));
            out.put("cached", true);
            return out;
        }
        if (key.isEmpty()) {
            out.put("ok", false);
            out.put("error", "未配置 DEEPSEEK_API_KEY —— 在项目根目录 .env 里加一行 DEEPSEEK_API_KEY=sk-xxx 后重启即可");
            return out;
        }
        Map<String, Object> port = positions.list();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> brokers = (List<Map<String, Object>>) port.get("brokers");
        if (!Boolean.TRUE.equals(port.get("ready")) || brokers == null || brokers.isEmpty()) {
            out.put("ok", false);
            out.put("error", "暂无持仓数据，请先在「持仓」页录入或用截图识别导入");
            return out;
        }

        String snapshot = buildSnapshot(port, brokers);
        try {
            String content = callDeepseek(key, snapshot);
            out.put("ok", true);
            out.put("content", content);
            out.put("generated_at", LocalDateTime.now().withNano(0).toString().replace('T', ' '));
            out.put("cached", false);
            Map<String, Object> c = new HashMap<>(out);
            c.put("ts", System.currentTimeMillis());
            cache = c;
        } catch (Exception e) {
            log.warn("DeepSeek 分析失败: {}", e.getMessage());
            out.put("ok", false);
            out.put("error", "调用 DeepSeek 失败：" + e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- 快照
    /** 持仓数据 → 紧凑文本快照（一行一标的，含折算占比）。 */
    @SuppressWarnings("unchecked")
    private String buildSnapshot(Map<String, Object> port, List<Map<String, Object>> brokers) {
        Map<String, Object> gd = (Map<String, Object>) port.get("grand_cny_detail");
        Map<String, Object> fx = (Map<String, Object>) port.get("fx");
        double mvCny = U.num(gd.get("mv"));
        StringBuilder sb = new StringBuilder();
        sb.append("组合总览（折人民币）：总市值 ").append(money(mvCny))
                .append("，净投入 ").append(money(U.num(gd.get("cost"))))
                .append("，浮动盈亏 ").append(money(U.num(gd.get("pl"))))
                .append("（").append(U.num(gd.get("pl_pct"))).append("%）")
                .append("，已实现盈亏 ").append(money(U.num(gd.get("realized"))))
                .append("，近12月股息 ").append(money(U.num(gd.get("div_ttm"))))
                .append("，今日盈亏 ").append(money(U.num(gd.get("day_pl")))).append("\n\n");
        sb.append("持仓明细（列：账户 | 名称(代码) | 数量 | 现价 | 市值 | 折人民币 | 占组合比 | 持仓盈亏% | 今日% | 评分 | 信号 | 股息率(按市值)）：\n");
        int open = 0, closed = 0;
        for (Map<String, Object> b : brokers) {
            String broker = String.valueOf(b.get("broker"));
            for (Map<String, Object> it : (List<Map<String, Object>>) b.get("items")) {
                open++;
                double rate = it.get("currency") == null ? 1 : U.num(fx.get(it.get("currency")), 1);
                double cny = U.num(it.get("mv")) * rate;
                double share = mvCny > 0 ? cny / mvCny * 100 : 0;
                sb.append(broker).append(" | ").append(it.get("name")).append('(').append(it.get("code")).append(") | ")
                        .append(U.num(it.get("qty"))).append(" | ").append(U.num(it.get("price")))
                        .append(" | ").append(it.get("currency")).append(' ').append(U.num(it.get("mv")))
                        .append(" | ").append(money(cny)).append(" | ").append(fmt(share)).append("% | ")
                        .append(U.num(it.get("pl_pct"))).append("% | ").append(U.num(it.get("pct"))).append("% | ")
                        .append(it.get("score") == null ? "-" : it.get("score")).append(" | ")
                        .append(it.get("signal") == null ? "-" : it.get("signal")).append(" | ")
                        .append(U.num(it.get("div_rate_mv"))).append("%\n");
            }
            closed += ((List<?>) b.getOrDefault("closed", List.of())).size();
        }
        sb.append("\n另有 ").append(closed).append(" 个已清仓标的（不计入上表），当前在险持仓 ").append(open).append(" 只。\n");
        sb.append("汇率为静态近似值（HKD 0.92 / USD 7.15），折算金额供量级参考。");
        return sb.toString();
    }

    private static String money(double v) {
        if (Math.abs(v) >= 1e8) return String.format("%.2f亿", v / 1e8);
        if (Math.abs(v) >= 1e4) return String.format("%.1f万", v / 1e4);
        return String.format("%.0f", v);
    }

    private static String fmt(double v) {
        return Math.abs(v) >= 100 ? String.format("%.1f", v) : String.format("%.2f", v);
    }

    // ---------------------------------------------------------------- DeepSeek 调用
    private String callDeepseek(String key, String snapshot) throws Exception {
        ObjectNode root = om.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("max_tokens", 4096);
        ArrayNode msgs = root.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        msgs.addObject().put("role", "user").put("content", snapshot);

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(root)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            String body = resp.body();
            String detail = body != null && body.length() > 200 ? body.substring(0, 200) : String.valueOf(body);
            throw new IOException("HTTP " + resp.statusCode() + " " + detail);
        }
        JsonNode content = om.readTree(resp.body()).path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("响应中缺少内容字段");
        }
        return content.asText();
    }
}
