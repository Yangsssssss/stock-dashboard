package com.quantboard.feed;

import com.quantboard.testkit.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 腾讯行情解析。样本是线上真实抓下来的 GBK 响应（fixtures/tencent_quotes_gbk.txt），
 * 覆盖 A股 / 港股 / 美股三种字段布局。
 *
 * 为什么值得测：这段代码靠数字下标取字段（f[37] 成交额、f[39] 市盈率…），
 * 上游改一个字段位，整排数字会静默错位——不抛异常、页面照常渲染，只是数是错的。
 * 单位换算（万元 / 亿元）也是错一次就差 10000 倍。
 */
class FeedParseTest {

    private static String raw;

    @BeforeAll
    static void load() {
        raw = new String(Fixtures.bytes("tencent_quotes_gbk.txt"), Charset.forName("GBK"));
    }

    private Map<String, Map<String, Object>> parse() {
        return FeedService.parseTencentRaw(raw, Map.of());
    }

    @Test
    @DisplayName("A股：价格/涨跌/成交额单位（万元→亿）/市值/换手/PE")
    void aShare() {
        Map<String, Object> q = parse().get("sh600519");
        assertNotNull(q, "sh600519 未解析出来");
        assertEquals("sh600519", q.get("code"));
        assertEquals("贵州茅台", q.get("name"));
        assertEquals(1327.34, (Double) q.get("price"), 1e-6);
        assertEquals(1355.90, (Double) q.get("prev_close"), 1e-6);
        assertEquals(1354.98, (Double) q.get("open"), 1e-6);
        assertEquals(-28.56, (Double) q.get("change"), 1e-6);
        assertEquals(-2.11, (Double) q.get("pct"), 1e-6);
        assertEquals("CNY", q.get("currency"));
        assertEquals("亿", q.get("unit"));
        // f37=308642（万元）→ 30.86 亿
        assertEquals(30.86, (Double) q.get("amount_yi"), 1e-6);
        assertEquals(0.18, (Double) q.get("turnover"), 1e-6);
        assertEquals(20.38, (Double) q.get("pe"), 1e-6);
        assertEquals(2.06, (Double) q.get("amplitude"), 1e-6);
        assertEquals(16592.8, (Double) q.get("mktcap_yi"), 1e-6);
        assertNotNull(q.get("time"));
    }

    @Test
    @DisplayName("港股：币种 HKD、成交额按亿取、换手率置 null（口径不可比）")
    void hkShare() {
        Map<String, Object> q = parse().get("hk00700");
        assertNotNull(q);
        assertEquals("腾讯控股", q.get("name"));
        assertEquals("HKD", q.get("currency"));
        assertTrue((Double) q.get("price") > 0);
        assertNull(q.get("turnover"), "港股没有可比换手率，必须置空让前端显示 —");
        assertNotNull(q.get("amount_yi"));
    }

    @Test
    @DisplayName("美股：币种 USD、中文名保留、涨跌幅正常")
    void usShare() {
        Map<String, Object> q = parse().get("usAAPL");
        assertNotNull(q);
        assertEquals("USD", q.get("currency"));
        assertEquals("苹果", q.get("name"));
        assertEquals(347.10, (Double) q.get("price"), 1e-6);
        assertNotNull(q.get("amount_yi"));
    }

    @Test
    @DisplayName("代码大小写还原：back 映射把远端大写 code 换回调用方原始写法")
    void codeMappingRestored() {
        Map<String, Map<String, Object>> m = FeedService.parseTencentRaw(raw, Map.of("usAAPL", "usAapl"));
        assertNotNull(m.get("usAapl"), "应当回填调用方原始 code，否则缓存键会分裂成两份");
        assertNull(m.get("usAAPL"));
    }

    @Test
    @DisplayName("脏响应不炸：空串、非行情行、字段数不足、被 WAF 拦截的 HTML")
    void tolerateGarbage() {
        assertTrue(FeedService.parseTencentRaw("", Map.of()).isEmpty());
        assertTrue(FeedService.parseTencentRaw("<html>403 Forbidden</html>", Map.of()).isEmpty());
        assertTrue(FeedService.parseTencentRaw("v_pv_none_match=\"1\";", Map.of()).isEmpty());
        assertTrue(FeedService.parseTencentRaw("v_sh600519=\"1~贵州茅台~600519~10.0\";", Map.of()).isEmpty(),
                "字段不足 46 个应整条丢弃，而不是取到 null 后算出 0 元");
        // 半截响应：第一条合法、第二条残缺 → 合法的要留下
        Map<String, Map<String, Object>> m = FeedService.parseTencentRaw(
                raw + "v_sz000001=\"1~平安银行~000001\";", Map.of());
        assertEquals(3, m.size());
        assertFalse(m.isEmpty());
    }
}
