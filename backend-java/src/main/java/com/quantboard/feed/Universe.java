package com.quantboard.feed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置标的池（与 Python 版 feed.UNIVERSE 一致）。 */
public final class Universe {
    private Universe() {}

    /** 单个内置标的：code / 名称兜底 / kind。 */
    public record Item(String code, String name, String kind) {}

    public record Group(String label, String benchmark, List<Item> items) {}

    private static List<Item> items(Object... arr) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < arr.length; i += 3) {
            out.add(new Item((String) arr[i], (String) arr[i + 1], (String) arr[i + 2]));
        }
        return out;
    }

    public static final Map<String, Group> UNIVERSE = new LinkedHashMap<>();

    static {
        UNIVERSE.put("a", new Group("A 股", "sh000300", items(
                "sh000001", "上证指数", "index",
                "sz399001", "深证成指", "index",
                "sz399006", "创业板指", "index",
                "sh000688", "科创50", "index",
                "sh000300", "沪深300", "index",
                "sh000905", "中证500", "index",
                "sh600519", "贵州茅台", "stock",
                "sz300750", "宁德时代", "stock",
                "sz002594", "比亚迪", "stock",
                "sh688981", "中芯国际", "stock",
                "sh600036", "招商银行", "stock",
                "sz002475", "立讯精密", "stock",
                "sh601318", "中国平安", "stock",
                "sh600900", "长江电力", "stock",
                "sz000858", "五粮液", "stock",
                "sh601899", "紫金矿业", "stock")));
        UNIVERSE.put("hk", new Group("港股", "hkHSI", items(
                "hkHSI", "恒生指数", "index",
                "hkHSTECH", "恒生科技", "index",
                "hkHSCEI", "国企指数", "index",
                "hk00700", "腾讯控股", "stock",
                "hk09988", "阿里巴巴", "stock",
                "hk03690", "美团", "stock",
                "hk01810", "小米集团", "stock",
                "hk01211", "比亚迪股份", "stock",
                "hk00981", "中芯国际", "stock",
                "hk01024", "快手", "stock",
                "hk09618", "京东集团", "stock",
                "hk02015", "理想汽车", "stock")));
        UNIVERSE.put("us", new Group("美股", "usIXIC", items(
                "usDJI", "道琼斯", "index",
                "usIXIC", "纳斯达克", "index",
                "usINX", "标普500", "index",
                "usAAPL", "苹果", "stock",
                "usNVDA", "英伟达", "stock",
                "usMSFT", "微软", "stock",
                "usGOOGL", "谷歌", "stock",
                "usAMZN", "亚马逊", "stock",
                "usMETA", "Meta", "stock",
                "usTSLA", "特斯拉", "stock",
                "usTSM", "台积电", "stock",
                "usAVGO", "博通", "stock")));
        UNIVERSE.put("etf", new Group("ETF", "sh000300", items(
                "sh510300", "沪深300ETF", "etf",
                "sh510500", "中证500ETF", "etf",
                "sh588000", "科创50ETF", "etf",
                "sz159915", "创业板ETF", "etf",
                "sh512880", "证券ETF", "etf",
                "sh512480", "半导体ETF", "etf",
                "sh515790", "光伏ETF", "etf",
                "sh512690", "酒ETF", "etf",
                "sh513100", "纳指ETF", "etf",
                "sh513500", "标普500ETF", "etf",
                "sh513180", "恒生科技ETF", "etf",
                "sh518880", "黄金ETF", "etf")));
        // 场外基金（含债基）：内部代码 f + 6 位基金代码；基准用场内上证国债指数
        UNIVERSE.put("fund", new Group("基金", "sh000012", items(
                "sh000012", "国债指数", "index",
                "f003847", "华安鼎丰债券A", "fund",
                "f000801", "中金纯债A", "fund",
                "f110027", "易方达安心回报债券A", "fund",
                "f270048", "广发纯债A", "fund",
                "f485111", "工银双利债券A", "fund",
                "f000032", "易方达信用债A", "fund",
                "f161725", "招商白酒指数A", "fund",
                "f005827", "易方达蓝筹精选", "fund",
                "f000961", "天弘沪深300联接A", "fund",
                "f110011", "易方达优质精选", "fund",
                "f320007", "诺安成长混合A", "fund",
                "f161005", "富国天惠成长A", "fund")));
    }

    /** 相关性矩阵标的。 */
    public static final String[][] CORR_SET = {
            {"sh510300", "沪深300ETF"},
            {"sz159915", "创业板ETF"},
            {"sh513180", "恒生科技ETF"},
            {"sh513100", "纳指ETF"},
            {"sh518880", "黄金ETF"},
            {"sh000012", "国债指数"},
            {"f000801", "中金纯债A"},
    };

    public static List<String> allCodes() {
        List<String> out = new ArrayList<>();
        for (Group g : UNIVERSE.values()) {
            for (Item it : g.items()) out.add(it.code());
        }
        return out;
    }

    public static String codeName(String code) {
        for (Group g : UNIVERSE.values()) {
            for (Item it : g.items()) {
                if (it.code().equals(code)) return it.name();
            }
        }
        return null;
    }

    public static String codeKind(String code) {
        for (Group g : UNIVERSE.values()) {
            for (Item it : g.items()) {
                if (it.code().equals(code)) return it.kind();
            }
        }
        return null;
    }

    /** 腾讯美股代码：指数直接用，个股按交易所加后缀（.OQ 纳斯达克 / .N 纽交所）。 */
    public static final Map<String, String> US_TENCENT = Map.ofEntries(
            Map.entry("usDJI", "usDJI"), Map.entry("usIXIC", "usIXIC"), Map.entry("usINX", "usINX"),
            Map.entry("usAAPL", "usAAPL.OQ"), Map.entry("usNVDA", "usNVDA.OQ"),
            Map.entry("usMSFT", "usMSFT.OQ"), Map.entry("usGOOGL", "usGOOGL.OQ"),
            Map.entry("usAMZN", "usAMZN.OQ"), Map.entry("usMETA", "usMETA.OQ"),
            Map.entry("usTSLA", "usTSLA.OQ"), Map.entry("usAVGO", "usAVGO.OQ"),
            Map.entry("usTSM", "usTSM.N"));

    // 东财 secid：1=沪 / 0=深 / 116=港股 / 124=恒生科技 / 100=海外指数
    public static final Map<String, String> US_SECID = Map.ofEntries(
            Map.entry("usDJI", "100.DJIA"), Map.entry("usIXIC", "100.NDX"), Map.entry("usINX", "100.SPX"),
            Map.entry("usAAPL", "105.AAPL"), Map.entry("usNVDA", "105.NVDA"), Map.entry("usMSFT", "105.MSFT"),
            Map.entry("usGOOGL", "105.GOOGL"), Map.entry("usAMZN", "105.AMZN"), Map.entry("usMETA", "105.META"),
            Map.entry("usTSLA", "105.TSLA"), Map.entry("usAVGO", "105.AVGO"), Map.entry("usTSM", "106.TSM"));

    public static final Map<String, String> HK_INDEX_SECID = Map.of(
            "hkHSI", "100.HSI", "hkHSCEI", "100.HSCEI", "hkHSTECH", "124.HSTECH");

    public static String secid(String code) {
        if (code.startsWith("us")) {
            return US_SECID.getOrDefault(code, "105." + code.substring(2).toUpperCase());
        }
        String hkIdx = HK_INDEX_SECID.get(code);
        if (hkIdx != null) return hkIdx;
        if (code.startsWith("hk")) return "116." + code.substring(2);
        if (code.startsWith("sh")) return "1." + code.substring(2);
        if (code.startsWith("sz") || code.startsWith("bj")) return "0." + code.substring(2);
        return "";
    }

    /** 内部代码 → (grp, kind, market, currency)。 */
    public static String[] classifyCode(String code) {
        if (FundService.isFund(code)) return new String[]{"fund", "fund", "CN", "CNY"};
        if (code.startsWith("us")) {
            boolean idx = code.equals("usDJI") || code.equals("usIXIC") || code.equals("usINX");
            return new String[]{"us", idx ? "index" : "stock", "US", "USD"};
        }
        if (HK_INDEX_SECID.containsKey(code)) return new String[]{"hk", "index", "HK", "HKD"};
        if (code.startsWith("hk")) return new String[]{"hk", "stock", "HK", "HKD"};
        if (code.matches("^(sh5|sz15).*")) return new String[]{"etf", "etf", "CN", "CNY"};
        if (code.matches("^(sh000|sh899|sz39[59]).*")) return new String[]{"a", "index", "CN", "CNY"};
        return new String[]{"a", "stock", "CN", "CNY"};
    }
}
