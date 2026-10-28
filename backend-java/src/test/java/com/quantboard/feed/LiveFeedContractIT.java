package com.quantboard.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游契约测试：真的去打一次腾讯行情，确认字段位没变。
 *
 * 打网络，所以命名用 *IT（surefire 默认只扫 *Test），不进日常构建。需要时手动跑：
 *     ./mvnw test -Dtest=LiveFeedContractIT
 *
 * 价值：fixtures 里的样本是某一天抓的，上游改接口时它不会变、测试也不会红；
 * 只有这条会红。改 FeedService 解析逻辑前、或发现页面上数字整体对不上时跑一次。
 */
class LiveFeedContractIT {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    @DisplayName("线上腾讯行情：A股/美股仍能解析出价格与币种")
    void tencentStillParses() throws Exception {
        String raw = new String(HttpFetch.get("https://qt.gtimg.cn/q=sh600519,usAAPL"), GBK);
        Map<String, Map<String, Object>> m = FeedService.parseTencentRaw(raw, Map.of());

        Map<String, Object> cn = m.get("sh600519");
        assertNotNull(cn, "线上响应里没有 sh600519，上游格式可能变了。原始响应片段：" + snippet(raw));
        assertEquals("CNY", cn.get("currency"));
        assertEquals("贵州茅台", cn.get("name"));
        assertTrue((Double) cn.get("price") > 0);

        Map<String, Object> us = m.get("usAAPL");
        assertNotNull(us, "线上响应里没有 usAAPL。原始响应片段：" + snippet(raw));
        assertEquals("USD", us.get("currency"));
        assertTrue((Double) us.get("price") > 0);
    }

    private static String snippet(String raw) {
        return raw.length() > 300 ? raw.substring(0, 300) : raw;
    }
}
