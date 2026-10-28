package com.quantboard.service;

import com.quantboard.feed.HttpFetch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美股 / 港股 …标的中文名。
 *
 * 13F 申报里只有发行人英文法定名（APPLIED MATLS INC 这类压缩全大写写法），
 * 中文界面需要的是「应用材料」这种简称，所以单独维护一张 security_cn 表：
 * code（本项目代码，如 usAAPL）→ 中文名。
 *
 * 数据来源优先级：
 * 1) MANUAL 手工词典（src=manual，补腾讯只给英文名的标的，重刷不覆盖）
 * 2) 腾讯行情 qt.gtimg.cn 批量取名（一次 60 个代码，约八成标的能拿到中文简称）
 * 拿不到中文名的标的留空，前端回落显示 SEC 英文原名。
 */
@Service
public class NameCnService {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern LINE = Pattern.compile("v_us([^=]+)=\"([^\"]*)\"");
    /** 单批请求的代码数：腾讯对 URL 长度有限制，40 个稳定。 */
    private static final int BATCH = 40;

    private final JdbcTemplate jdbc;

    public NameCnService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 手工词典：腾讯行情只返回英文名（或名称太长的）标的，按常用中文叫法兜底。
     * 键为本项目代码（us + ticker，股份类别用 - 连接）。
     */
    private static final Map<String, String> MANUAL = new LinkedHashMap<>();
    static {
        MANUAL.put("usMETA", "Meta 平台（脸书）");
        MANUAL.put("usV", "Visa（维萨）");
        MANUAL.put("usMA", "万事达");
        MANUAL.put("usGFS", "格芯（GlobalFoundries）");
        MANUAL.put("usPLTR", "Palantir（帕兰提尔）");
        MANUAL.put("usGEV", "GE Vernova（通用电气能源）");
        MANUAL.put("usWELL", "Welltower（健康地产信托）");
        MANUAL.put("usBKNG", "Booking（缤客）");
        MANUAL.put("usHWM", "Howmet（豪迈航空）");
        MANUAL.put("usANET", "Arista（阿里斯塔网络）");
        MANUAL.put("usSHOP", "Shopify（肖皮法）");
        MANUAL.put("usINVH", "Invitation Homes（出租住宅信托）");
        MANUAL.put("usELV", "Elevance（艾弗森健康）");
        MANUAL.put("usAPP", "AppLovin（阿普洛文）");
        MANUAL.put("usEXR", "Extra Space（自助仓储信托）");
        MANUAL.put("usBRK-B", "伯克希尔·哈撒韦 B");
        MANUAL.put("usBRK-A", "伯克希尔·哈撒韦 A");
        MANUAL.put("usGOOG", "谷歌-C");
        MANUAL.put("usGOOGL", "谷歌-A");
        MANUAL.put("usBF-B", "布朗福曼 B");
        MANUAL.put("usSPY", "标普 500ETF-SPDR");
        MANUAL.put("usVOO", "标普 500ETF-Vanguard");
        MANUAL.put("usQQQ", "纳指 100ETF-Invesco");
        MANUAL.put("usTSM", "台积电");
        MANUAL.put("usTSLA", "特斯拉");
        MANUAL.put("usNVDA", "英伟达");
        MANUAL.put("usAAPL", "苹果");
        MANUAL.put("usMSFT", "微软");
        MANUAL.put("usAMZN", "亚马逊");
        MANUAL.put("usAVGO", "博通");

        // 腾讯只给英文名、但国内投资者普遍用中文叫法的标的（按 13F 持仓市值从高到低补，覆盖页面首屏）
        MANUAL.put("usLLYVA", "自由媒体 Live-A");
        MANUAL.put("usTMUS", "T-Mobile 美国");
        MANUAL.put("usKKR", "KKR 集团");
        MANUAL.put("usMMM", "3M 公司");
        MANUAL.put("usHHH", "霍华德·休斯控股");
        MANUAL.put("usTRGP", "塔加资源");
        MANUAL.put("usDDOG", "Datadog（数据狗）");
        MANUAL.put("usTDG", "特迪格姆");
        MANUAL.put("usADBE", "奥多比（Adobe）");
        MANUAL.put("usBE", "布鲁姆能源");
        MANUAL.put("usCOHR", "高意（Coherent）");
        MANUAL.put("usDASH", "DoorDash（外卖平台）");
        MANUAL.put("usKVUE", "凯威（Kenvue）");
        MANUAL.put("usLNG", "切尼尔能源");
        MANUAL.put("usSNOW", "Snowflake（雪花）");
        MANUAL.put("usO", "Realty Income（净租地产）");
        MANUAL.put("usYUM", "百胜集团");
        MANUAL.put("usAMRZ", "Amrize（建材）");
        MANUAL.put("usAMH", "美国房屋租赁信托");
        MANUAL.put("usCPAY", "Corpay（企业支付）");
        MANUAL.put("usCOR", "Cencora（药品分销）");
        MANUAL.put("usPSX", "菲利普斯 66");
        MANUAL.put("usTRP", "TC 能源");
        MANUAL.put("usALAB", "Astera Labs（连接芯片）");
        MANUAL.put("usEG", "珠峰集团（Everest）");
        MANUAL.put("usWPM", "惠顿贵金属");
        MANUAL.put("usCBRS", "Cerebras（晶圆级芯片）");
        MANUAL.put("usWCN", "Waste Connections（环卫）");
        MANUAL.put("usODFL", "老多米尼恩货运");
        MANUAL.put("usFER", "法罗里奥（Ferrovial）");
        MANUAL.put("usSPOT", "Spotify（声田）");
        MANUAL.put("usLINE", "Lineage（冷链物流）");
        MANUAL.put("usXYZ", "Block（原 Square）");
        MANUAL.put("usSTLD", "钢铁动力");
        MANUAL.put("usSTRL", "Sterling 基础设施");
        MANUAL.put("usRKLB", "火箭实验室（Rocket Lab）");
        MANUAL.put("usTDY", "特利丹（Teledyne）");
        MANUAL.put("usSUNB", "Sunbelt 租赁");
        MANUAL.put("usAXON", "Axon（泰瑟枪）");
        MANUAL.put("usAGX", "Argan（电力工程）");
        MANUAL.put("usADM", "阿彻丹尼尔斯米德兰");
        MANUAL.put("usNTRA", "Natera（基因检测）");
        MANUAL.put("usVIK", "维京控股（Viking）");
        MANUAL.put("usCUBE", "CubeSmart（自助仓储）");
        MANUAL.put("usAPTV", "安波福（Aptiv）");
        MANUAL.put("usMDB", "MongoDB（文档数据库）");
        MANUAL.put("usFTV", "福迪威（Fortive）");
        MANUAL.put("usSW", "斯墨菲特西岩");
        MANUAL.put("usETSY", "Etsy（手工艺电商）");
        MANUAL.put("usTFC", "Truist 金融");
        MANUAL.put("usEME", "EMCOR 集团");
        MANUAL.put("usCRWV", "CoreWeave（AI 云）");
        MANUAL.put("usFERG", "弗格森（Ferguson）");
        MANUAL.put("usW", "Wayfair（家居电商）");
        MANUAL.put("usCRBG", "Corebridge 金融");
        MANUAL.put("usWSM", "威廉索诺玛");
        MANUAL.put("usCOIN", "Coinbase（加密交易所）");
        MANUAL.put("usTWLO", "Twilio（云通信）");
        MANUAL.put("usRVMD", "Revolution Medicines（抗癌药）");
        MANUAL.put("usLYV", "现场国度（Live Nation）");
        MANUAL.put("usCASY", "Casey’s 便利店");
        MANUAL.put("usNVT", "nVent 电气");
        MANUAL.put("usINSM", "Insmed（生物制药）");
        MANUAL.put("usPTC", "PTC（工业软件）");
        MANUAL.put("usFTS", "Fortis（公用事业）");
        MANUAL.put("usFFIV", "F5（应用交付）");
        MANUAL.put("usFTAI", "FTAI 航空");
        MANUAL.put("usPNFP", "Pinnacle 金融合作");
        MANUAL.put("usMSTR", "Strategy（原微策略）");
        MANUAL.put("usCVNA", "Carvana（二手车电商）");
        MANUAL.put("usNTR", "Nutrien（化肥）");
    }

    /**
     * 补齐缺失的中文名（页面首访或 13F 导入后调用）。
     * 按持仓市值从大到小排队：界面默认按市值排序，先保证看得见的那些有中文名。
     * 返回 {ok, need, matched, filled}：need 为仍需取的代码数，filled 为本次新写入条数。
     */
    public Map<String, Object> fillMissing() {
        List<String> codes = new ArrayList<>();
        try {
            codes = jdbc.queryForList(
                    "SELECT h.code FROM guru_holding h "
                            + "LEFT JOIN security_cn s ON s.code = SUBSTRING_INDEX(h.code, '~', 1) "
                            + "WHERE s.code IS NULL AND h.code LIKE 'us%' "
                            + "GROUP BY h.code ORDER BY MAX(h.value_usd) DESC", String.class);
        } catch (Exception ignore) {
            // security_cn 还没建表（迁移未跑）时不做事
        }
        applyManualOverEmpty();
        return fill(codes);
    }

    /**
     * 手工词典里新增的条目，若库里已经存了「无中文名」占位行（腾讯只给英文名的那些），
     * 这里直接就地覆盖，不用再回源查一遍。
     */
    private void applyManualOverEmpty() {
        try {
            java.util.Set<String> empty = new java.util.HashSet<>(
                    jdbc.queryForList("SELECT code FROM security_cn WHERE name_cn = ''", String.class));
            for (Map.Entry<String, String> e : MANUAL.entrySet()) {
                if (empty.contains(e.getKey())) upsert(e.getKey(), e.getValue(), "manual");
            }
        } catch (Exception ignore) { }
    }

    /** 为指定代码补齐中文名（13F 导入后按本次涉及的代码调用，避免全量扫）。 */
    public Map<String, Object> fillCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return summary(0, 0, 0);
        java.util.Set<String> have;
        try {
            have = new java.util.HashSet<>(jdbc.queryForList("SELECT code FROM security_cn", String.class));
        } catch (Exception ignore) {
            return summary(0, 0, 0);
        }
        List<String> todo = new ArrayList<>();
        for (String c : new LinkedHashSet<>(codes)) {
            if (c == null || !c.toLowerCase().startsWith("us") || have.contains(c)) continue;
            todo.add(c);
        }
        if (todo.isEmpty()) return summary(0, 0, 0);
        return fill(todo);
    }

    private Map<String, Object> fill(List<String> todo) {
        int filled = 0;
        int matched = 0;
        int cached = 0;
        try {
            // 手工词典优先落库，避免被行情源的英文名占位
            for (Map.Entry<String, String> e : MANUAL.entrySet()) {
                if (!todo.contains(e.getKey())) continue;
                filled += upsert(e.getKey(), e.getValue(), "manual");
            }
            List<String> remote = new ArrayList<>();
            for (String c : todo) if (!MANUAL.containsKey(c)) remote.add(c);

            for (int i = 0; i < remote.size(); i += BATCH) {
                List<String> batch = remote.subList(i, Math.min(remote.size(), i + BATCH));
                Map<String, String> got = queryTencent(batch);
                // 整批颗粒无收通常是被限流（腾讯连发几十个请求后会短暂拒绝），停一下再重试一次
                if (got.isEmpty() && batch.size() > 3) {
                    pause(700);
                    got = queryTencent(batch);
                }
                // 有回应但只有英文名（腾讯没做中文简称）→ 记一条空名占位，
                // 下次不再重复请求；界面回落到 SEC 英文原名
                for (Map.Entry<String, String> e : got.entrySet()) {
                    if (e.getValue().isEmpty()) {
                        cached += upsert(e.getKey(), "", "none");
                    } else {
                        matched++;
                        filled += upsert(e.getKey(), e.getValue(), "tencent");
                    }
                }
                if (i + BATCH < remote.size()) pause(320);
            }
        } catch (Exception ignore) { /* 网络问题不影响页面，下次再补 */ }
        Map<String, Object> out = summary(todo.size(), matched, filled);
        out.put("cached", cached);
        return out;
    }

    private Map<String, Object> summary(int need, int matched, int filled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("need", need);
        out.put("matched", matched);
        out.put("filled", filled);
        out.put("have", countHave());
        return out;
    }

    private int countHave() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM security_cn", Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private int upsert(String code, String nameCn, String src) {
        try {
            return jdbc.update("INSERT INTO security_cn (code, name_cn, src) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE name_cn = IF(src='manual', name_cn, VALUES(name_cn)), "
                    + "src = IF(src='manual', src, VALUES(src))", code, nameCn, src);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 腾讯批量取名：v_usAAPL="200~苹果~AAPL.OQ~…"，第 2 段是名称。
     * 返回值为中文简称；腾讯只给英文名（如 T-Mobile Us, Inc.）时返回空串，
     * 由调用方记成「无中文名」占位；未出现在响应里的代码不返回。
     */
    private Map<String, String> queryTencent(List<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder q = new StringBuilder();
        for (String c : codes) {
            // usBRK-B → usBRK.B（腾讯用点号表示股份类别）
            String t = c.substring(2).replace('-', '.');
            if (q.length() > 0) q.append(',');
            q.append("us").append(t);
        }
        String url = "https://qt.gtimg.cn/q=" + q;
        try {
            String txt = new String(HttpFetch.get(url, "https://gu.qq.com/", 12), GBK);
            Matcher m = LINE.matcher(txt);
            while (m.find()) {
                String tick = m.group(1).replace('.', '-').toUpperCase();
                String[] f = m.group(2).split("~");
                if (f.length < 3) continue;
                String name = f[1] == null ? "" : f[1].trim();
                out.put("us" + tick, hasCjk(name) ? name : "");
            }
        } catch (Exception ignore) { }
        return out;
    }

    /** 是否含汉字（纯英文名不入库，界面回落显示 SEC 原文更准确）。 */
    public static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x4E00 && s.charAt(i) <= 0x9FFF) return true;
        }
        return false;
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
