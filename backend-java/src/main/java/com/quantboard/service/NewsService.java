package com.quantboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantboard.db.DbService;
import com.quantboard.feed.HttpFetch;
import com.quantboard.feed.Universe;
import com.quantboard.util.U;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 消息中心：政策 + 企业新闻聚合。
 *
 * 数据来源：
 *  1) 新浪财经 7×24 快讯（自带「宏观 / 公司 / 市场 / 国际」分类标签），翻 3 页约 150 条；
 *  2) 东方财富个股公告，按当前持仓 + 自选标的逐个拉取（只有沪深 A 股有稳定公告接口）。
 *
 * 分类优先级：政策关键词 > 关联标的 / 公司标签 > 市场 > 国际。
 * 政策关键词优先于标签，因为「国常会」「证监会」这类消息常被标成「公司」或「市场」。
 */
@Service
public class NewsService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final long TTL_MS = 180_000;      // 3 分钟缓存
    private static final int PAGES = 5;              // 5 页 ≈ 250 条，覆盖 4-5 小时
    private static final int PAGE_SIZE = 50;
    private static final int MAX_ANN_CODES = 12;     // 最多扫几个标的的公告
    private static final int ANN_PER_CODE = 5;

    /**
     * 政策 / 监管口径关键词。**只放强信号词**：
     * 「通知」「监管」「政策」「办法」这类泛词几乎每条公司新闻都能命中，一律不收。
     */
    private static final String[] POLICY_KW = {
            "国务院", "国常会", "政治局", "中央经济工作会议", "证监会", "央行", "人民银行",
            "金融监管总局", "银保监", "财政部", "发改委", "工信部", "商务部", "市场监管总局",
            "能源局", "网信办", "住建部", "自然资源部", "税务总局", "海关总署", "国资委",
            "新规", "细则", "征求意见", "印发", "条例", "降准", "降息", "加息",
            "LPR", "MLF", "逆回购", "关税", "反倾销", "出口管制",
            "补贴", "专项债", "特别国债", "减税", "降费", "扩大内需", "以旧换新",
            "险资", "平准基金", "汇金", "国家队", "印花税", "IPO 节奏", "转融通"
    };

    /** 需要高亮的要紧消息。 */
    private static final String[] HOT_KW = {
            "重磅", "突发", "国务院", "国常会", "证监会", "央行", "降准", "降息", "加息",
            "暂停", "熔断", "暴涨", "暴跌", "立案", "退市", "重组"
    };

    /** 美国主体 / 美股市场：命中则单列「美国」分类（用户要求与美国相关的新闻单独一栏）。 */
    private static final String[] US_KW = {
            "美国", "美联储", "美股", "美债", "美元", "纳斯达克", "纳指", "标普", "道琼斯",
            "华尔街", "白宫", "特朗普", "沃伦", "鲍威尔", "非农", "CPI", "PPI", "FOMC"
    };

    /** 外国主体：命中则不算「中国政策」（除非标题同时出现中国 / 我国 / 国内）。 */
    private static final String[] FOREIGN_KW = {
            "美国", "美联储", "欧洲央行", "欧央行", "日本央行", "英国", "德国", "法国",
            "巴西", "印度", "俄罗斯", "韩国", "乌克兰", "土耳其", "加拿大", "澳洲",
            "墨西哥", "越南", "沙特", "伊朗", "以色列", "阿根廷", "印尼", "泰国",
            "委内瑞拉", "古巴", "波兰", "乌克兰", "尼日利亚", "南非"
    };

    private final DbService db;
    private final TradeService trades;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    private volatile long ts = 0;
    private volatile Map<String, Object> cache;

    public NewsService(DbService db, TradeService trades) {
        this.db = db;
        this.trades = trades;
    }

    public Map<String, Object> feed(boolean force) {
        Map<String, Object> c = cache;
        if (!force && c != null && System.currentTimeMillis() - ts < TTL_MS) return c;
        synchronized (this) {
            c = cache;
            if (!force && c != null && System.currentTimeMillis() - ts < TTL_MS) return c;
            Map<String, Object> out = build();
            cache = out;
            ts = System.currentTimeMillis();
            return out;
        }
    }

    // ---------------------------------------------------------------- 组装
    private Map<String, Object> build() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, String> focus = focusStocks();

        // 1) 快讯：3 页并行
        List<Future<List<Map<String, Object>>>> fs = new ArrayList<>();
        for (int p = 1; p <= PAGES; p++) {
            final int page = p;
            fs.add(pool.submit(() -> fetchFlash(page, focus)));
        }
        for (Future<List<Map<String, Object>>> f : fs) {
            try { items.addAll(f.get()); } catch (Exception ignore) { }
        }

        // 2) 公告：持仓 + 自选（沪深 A 股）
        List<Future<List<Map<String, Object>>>> as = new ArrayList<>();
        for (Map.Entry<String, String> e : focus.entrySet()) {
            final String code = e.getKey();
            final String name = e.getValue();
            String em = emStockCode(code);
            if (em == null) continue;
            as.add(pool.submit(() -> fetchNotices(em, code, name)));
            if (as.size() >= MAX_ANN_CODES) break;
        }
        for (Future<List<Map<String, Object>>> f : as) {
            try { items.addAll(f.get()); } catch (Exception ignore) { }
        }

        // 去重 + 排序
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String k = String.valueOf(it.get("id"));
            seen.putIfAbsent(k, it);
        }
        List<Map<String, Object>> all = new ArrayList<>(seen.values());
        all.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", all.size());
        for (String k : new String[]{"policy", "company", "us", "market", "global", "ann"}) {
            int n = 0;
            for (Map<String, Object> it : all) if (k.equals(it.get("kind"))) n++;
            counts.put(k, n);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        out.put("counts", counts);
        out.put("items", all);
        out.put("focus", new ArrayList<>(focus.values()));
        return out;
    }

    /** 关注标的：当前持仓 + 自选，code -> name。 */
    private Map<String, String> focusStocks() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            for (TradeService.Pos p : trades.aggregate().values()) {
                if (!p.closed()) m.put(p.code, p.name);
            }
        } catch (Exception ignore) { }
        try {
            for (String c : db.watchCodes()) {
                if (m.containsKey(c)) continue;
                String n = c.startsWith("us") ? null : Universe.codeName(c);
                m.put(c, n != null ? n : c);
            }
        } catch (Exception ignore) { }
        return m;
    }

    // ---------------------------------------------------------------- 快讯
    private List<Map<String, Object>> fetchFlash(int page, Map<String, String> focus) {
        List<Map<String, Object>> out = new ArrayList<>();
        String url = "https://zhibo.sina.com.cn/api/zhibo/feed?page=" + page
                + "&page_size=" + PAGE_SIZE + "&zhibo_id=152&tag_id=0&dire=f&dpc=1";
        try {
            String txt = HttpFetch.getUtf8(url, "https://finance.sina.com.cn/7x24/");
            JsonNode list = M.readTree(txt).path("result").path("data").path("feed").path("list");
            if (!list.isArray()) return out;
            for (JsonNode n : list) {
                String text = n.path("rich_text").asText("").trim();
                if (text.isEmpty()) continue;
                String time = n.path("create_time").asText("");
                String url2 = "";
                try {
                    JsonNode ext = M.readTree(n.path("ext").asText("{}"));
                    url2 = ext.path("docurl").asText("");
                } catch (Exception ignore) { }

                Set<String> tags = new LinkedHashSet<>();
                for (JsonNode t : n.path("tag")) {
                    String nm = t.path("name").asText("");
                    if (!nm.isEmpty()) tags.add(nm);
                }

                String[] td = splitTitle(text);
                String title = td[0];
                String digest = td[1];

                List<String> hit = new ArrayList<>();
                for (Map.Entry<String, String> e : focus.entrySet()) {
                    String nm = e.getValue();
                    if (nm != null && nm.length() >= 2 && text.contains(nm)) hit.add(nm);
                }

                String kind = classify(title + digest, tags, hit);
                boolean hot = containsAny(title, HOT_KW);

                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", "s" + n.path("id").asLong());
                it.put("time", time);
                it.put("title", title);
                it.put("digest", digest);
                it.put("url", url2);
                it.put("kind", kind);
                it.put("tags", new ArrayList<>(tags));
                it.put("stocks", hit);
                it.put("hot", hot);
                it.put("source", "新浪财经");
                out.add(it);
            }
        } catch (Exception ignore) { }
        return out;
    }

    private static String classify(String text, Set<String> tags, List<String> hit) {
        boolean foreign = containsAny(text, FOREIGN_KW);
        boolean cnMark = text.contains("中国") || text.contains("我国") || text.contains("国内")
                || text.contains("A股") || text.contains("沪深");
        // 政策优先，但「美国财政部」「巴西央行」这类海外监管不算中国政策
        if (containsAny(text, POLICY_KW) && (!foreign || cnMark)) return "policy";
        // 企业只看「是否命中关注标的」：新浪的「公司」标签连波音、赛峰都打，不能作依据
        if (!hit.isEmpty()) return "company";
        // 美国新闻单列（持仓相关的美国公司新闻已在上面归「企业」）
        if (containsAny(text, US_KW) && !cnMark) return "us";
        if (tags.contains("宏观") && (!foreign || cnMark)) return "policy";
        if (tags.contains("市场") || tags.contains("数据")) return "market";
        return "global";
    }

    /** rich_text 形如「【标题】正文」，拆成标题 + 摘要。 */
    private static String[] splitTitle(String text) {
        int l = text.indexOf('【'), r = text.indexOf('】');
        if (l >= 0 && r > l + 1) {
            String title = text.substring(l + 1, r).trim();
            String rest = text.substring(r + 1).trim();
            return new String[]{title, rest.length() > 160 ? rest.substring(0, 160) + "…" : rest};
        }
        String one = text.replaceAll("\\s+", " ");
        if (one.length() <= 60) return new String[]{one, ""};
        int cut = Math.min(one.length(), 60);
        return new String[]{one.substring(0, cut), one.substring(cut)};
    }

    // ---------------------------------------------------------------- 公告
    private List<Map<String, Object>> fetchNotices(String emCode, String code, String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        String url = "https://np-anotice-stock.eastmoney.com/api/security/ann"
                + "?sr=-1&page_size=" + ANN_PER_CODE + "&page_index=1&ann_type=A"
                + "&client_source=web&stock_list=" + emCode;
        try {
            String txt = HttpFetch.getUtf8(url, "https://data.eastmoney.com/");
            JsonNode list = M.readTree(txt).path("data").path("list");
            if (!list.isArray()) return out;
            for (JsonNode n : list) {
                String title = n.path("title").asText("").trim();
                if (title.isEmpty()) continue;
                String date = n.path("notice_date").asText("");
                if (date.length() > 10) date = date.substring(0, 10);
                String art = n.path("art_code").asText("");
                // 公告用精确披露时间（display_time 形如 2026-10-23 20:39:48:642），
                // 缺省才退回 notice_date；预告类公告给的日期可能在未来，钳到当前时刻
                String dt = n.path("display_time").asText("");
                String time = dt.length() >= 19
                        ? dt.substring(0, 10) + " " + dt.substring(11, 19)
                        : date + " 00:00:00";
                String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                if (time.compareTo(nowStr) > 0) time = nowStr;
                String link = art.isEmpty() ? "" :
                        "https://data.eastmoney.com/notices/detail/" + emCode + "/" + art + ".html";
                List<String> hit = new ArrayList<>();
                hit.add(name != null && !name.isEmpty() ? name : code);

                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", "a" + (art.isEmpty() ? title.hashCode() : art));
                it.put("time", time);
                it.put("title", title.replaceFirst("^" + java.util.regex.Pattern.quote(
                        name == null ? "" : name + ":"), "").trim());
                it.put("digest", "");
                it.put("url", link);
                it.put("kind", "ann");
                it.put("tags", List.of("公告"));
                it.put("stocks", hit);
                it.put("hot", false);
                it.put("source", "东方财富");
                out.add(it);
            }
        } catch (Exception ignore) { }
        return out;
    }

    // ---------------------------------------------------------------- 工具
    private static boolean containsAny(String text, String[] kws) {
        if (text == null || text.isEmpty()) return false;
        for (String k : kws) if (text.contains(k)) return true;
        return false;
    }

    private static String emStockCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        if (c.matches("(sh|sz|bj)\\d{6}")) return c.substring(2);
        return null;
    }

    /** 供前端下拉框用：关注标的列表。 */
    public List<String> focusNames() {
        return new ArrayList<>(focusStocks().values());
    }

    static long num(Object o) { return (long) U.num(o); }
}
