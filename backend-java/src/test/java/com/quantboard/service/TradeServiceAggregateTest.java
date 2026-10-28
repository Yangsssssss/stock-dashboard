package com.quantboard.service;

import com.quantboard.feed.FeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 持仓聚合：移动加权成本、已实现盈亏、股息 TTM。
 *
 * 这里不需要数据库——JdbcTemplate 用 mock 喂流水行即可（流水是唯一真相，聚合是纯计算）。
 * 覆盖的是「钱算错了但页面照样显示」的高危路径：分批建仓、部分卖出、清仓、超量卖出、分红。
 */
class TradeServiceAggregateTest {

    private JdbcTemplate jdbc;
    private TradeService svc;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(ArgumentMatchers.anyString())).thenAnswer(inv -> rows);
        svc = new TradeService(jdbc, mock(FeedService.class));
    }

    /** 造一行流水；amount 传 null 表示按 qty*price+fee 自动推（与手工录入一致）。 */
    private void trade(String broker, String code, String side, LocalDate d,
                       double qty, double price, double fee, Double amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("broker", broker);
        r.put("code", code);
        r.put("name", "测试标的");
        r.put("side", side);
        r.put("trade_date", d.toString());
        r.put("qty", qty);
        r.put("price", price);
        r.put("fee", fee);
        r.put("currency", "CNY");
        double amt = amount != null ? amount
                : switch (side) {
                    case "BUY", "INIT" -> qty * price + fee;
                    case "SELL" -> qty * price - fee;
                    default -> 0.0;
                };
        r.put("amount", amt);
        rows.add(r);
    }

    private TradeService.Pos one() {
        Map<String, TradeService.Pos> all = svc.aggregate();
        assertEquals(1, all.size(), "应当只有一条持仓记录");
        return all.values().iterator().next();
    }

    @Test
    @DisplayName("单笔买入：成本含手续费，均价 = 成本/股数")
    void singleBuy() {
        trade("默认账户", "sh600519", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 5, null);
        TradeService.Pos p = one();
        assertEquals(100, p.qty, 1e-9);
        assertEquals(1005, p.costTotal, 1e-9);
        assertEquals(10.05, p.avgCost(), 1e-9);
        assertEquals(0, p.realized, 1e-9);
        assertFalse(p.closed());
        assertEquals(1, p.trades);
    }

    @Test
    @DisplayName("分批加仓后部分卖出：移动加权成本，已实现 = 卖出额 - 摊成本 - 手续费")
    void weightedAverageAndPartialSell() {
        trade("默认账户", "sh600519", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 5, null);   // 成本 1005
        trade("默认账户", "sh600519", "BUY", LocalDate.of(2026, 2, 5), 100, 20, 5, null);   // 成本 2005
        trade("默认账户", "sh600519", "SELL", LocalDate.of(2026, 3, 5), 100, 25, 5, null);

        TradeService.Pos p = one();
        assertEquals(100, p.qty, 1e-9);
        assertEquals(1505, p.costTotal, 1e-9);        // 3010 - 15.05*100
        assertEquals(15.05, p.avgCost(), 1e-9);
        assertEquals(100 * (25 - 15.05) - 5, p.realized, 1e-9);   // 990
    }

    @Test
    @DisplayName("全部卖出后 closed=true 且成本清零，不残留 1e-9 级别的尾巴")
    void fullClose() {
        trade("默认账户", "sz000001", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 5, null);
        trade("默认账户", "sz000001", "SELL", LocalDate.of(2026, 3, 5), 100, 12, 5, null);

        TradeService.Pos p = one();
        assertEquals(0, p.qty, 1e-12);
        assertEquals(0, p.costTotal, 1e-12);
        assertTrue(p.closed());
        assertEquals(0, p.avgCost(), 1e-12);
        assertEquals(100 * (12 - 10.05) - 5, p.realized, 1e-9);   // 190
    }

    @Test
    @DisplayName("卖出量超过持仓时按持仓截断，不会把持仓做成负数")
    void oversellIsClamped() {
        trade("默认账户", "sz000001", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 0, null);
        trade("默认账户", "sz000001", "SELL", LocalDate.of(2026, 3, 5), 500, 12, 0, null);

        TradeService.Pos p = one();
        assertEquals(0, p.qty, 1e-12);
        assertEquals(0, p.costTotal, 1e-12);
        assertEquals(200, p.realized, 1e-9);      // 只按实际卖出的 100 股算
    }

    @Test
    @DisplayName("恒等式：清仓后 已实现盈亏 == -(净投入)，这是收益口径的根")
    void realizedEqualsNegativeNetInvested() {
        trade("默认账户", "sh600519", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 5, null);   // 流出 1005
        trade("默认账户", "sh600519", "BUY", LocalDate.of(2026, 2, 5), 200, 11, 6, null);   // 流出 2206
        trade("默认账户", "sh600519", "SELL", LocalDate.of(2026, 3, 5), 300, 13, 7, null);  // 流入 3893

        TradeService.Pos p = one();
        double netInvested = 1005 + 2206 - 3893;     // = -682
        assertEquals(-netInvested, p.realized, 1e-9);
        assertTrue(p.closed());
    }

    @Test
    @DisplayName("分红单独累计，不冲减成本；近 12 个月单独统计 TTM")
    void dividends() {
        LocalDate now = LocalDate.now();
        trade("默认账户", "sh600519", "BUY", now.minusDays(400), 100, 10, 0, null);
        trade("默认账户", "sh600519", "DIV", now.minusDays(380), 0, 0, 0, 120.0);   // 一年以外
        trade("默认账户", "sh600519", "DIV", now.minusDays(30), 0, 0, 0, 80.0);    // 一年以内

        TradeService.Pos p = one();
        assertEquals(200, p.divTotal, 1e-9);
        assertEquals(80, p.divTtm, 1e-9);
        assertEquals(1000, p.costTotal, 1e-9, "分红不能冲减持仓成本");
    }

    @Test
    @DisplayName("同一标的不同券商分开聚合，互不串账")
    void splitByBroker() {
        trade("华泰", "sh600519", "BUY", LocalDate.of(2026, 1, 5), 100, 10, 0, null);
        trade("富途", "sh600519", "BUY", LocalDate.of(2026, 1, 6), 200, 20, 0, null);

        Map<String, TradeService.Pos> all = svc.aggregate();
        assertEquals(2, all.size());
        assertEquals(1000, all.get("华泰|sh600519").costTotal, 1e-9);
        assertEquals(4000, all.get("富途|sh600519").costTotal, 1e-9);
    }

    @Test
    @DisplayName("导入流水 price 不可信时以 amount 兜底（口径分裂的已知坑）")
    void amountWinsOverPrice() {
        // 导入行：price 误写为当日市价 999，但真实现金流出是 1005
        trade("导入", "sh600519", "BUY", LocalDate.of(2026, 1, 5), 100, 999, 0, 1005.0);
        assertEquals(1005, one().costTotal, 1e-9);
    }

    @Test
    @DisplayName("空流水表返回空聚合，不抛异常")
    void emptyTable() {
        assertTrue(svc.aggregate().isEmpty());
    }
}
