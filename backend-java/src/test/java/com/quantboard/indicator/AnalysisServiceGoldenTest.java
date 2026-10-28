package com.quantboard.indicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.quantboard.testkit.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评分引擎对拍 + 不变量。
 *
 * 评分是全站最「不可解释」的一块：4 个因子、十几个加减项，任何一个系数被改动，
 * 页面上的排序会整片翻掉但没人看得出来。所以这里钉两道桩：
 *   1) 对拍：同一份 K 线，Java 输出必须等于 Python 老版输出（含结论文案）；
 *   2) 不变量：无论系数怎么改，单调上涨必须显著高于单调下跌、分数必须落在 0~100。
 */
class AnalysisServiceGoldenTest {

    private final AnalysisService svc = new AnalysisService();

    private static List<Object[]> bars;
    private static List<Object[]> bench;
    private static JsonNode exp;

    @BeforeAll
    static void load() {
        bars = Fixtures.bars("bars_120.csv");
        bench = Fixtures.bars("bars_bench.csv");
        exp = Fixtures.expected();
    }

    /** 构造单调走势的合成日线。 */
    private static List<Object[]> trend(int n, double start, double dailyPct, double vol) {
        List<Object[]> out = new ArrayList<>();
        double c = start;
        for (int i = 0; i < n; i++) {
            c = c * (1 + dailyPct);
            double o = c * (1 - dailyPct / 2);
            out.add(new Object[]{String.format("2025-01-%02d", 1 + i % 28),
                    o, c, Math.max(o, c) * 1.004, Math.min(o, c) * 0.996, vol});
        }
        return out;
    }

    @Test
    @DisplayName("对拍：analyze() 全字段与 Python 版一致")
    void analyzeMatchesPython() {
        Map<String, Object> a = svc.analyze(bars, bench);
        JsonNode e = exp.get("analyze");

        assertEquals(true, a.get("ok"));
        assertEquals(e.get("signal").asText(), a.get("signal"));
        assertEquals(e.get("signal_cls").asText(), a.get("signal_cls"));
        assertEquals(e.get("score").asDouble(), (Double) a.get("score"), 1e-6);

        // 结论文案逐字对齐：这段字直接显示在卡片上，改一个标点都算回归
        assertEquals(e.get("conclusion").asText(), a.get("conclusion"));

        Map<?, ?> f = (Map<?, ?>) a.get("factors");
        for (String k : new String[]{"trend", "momentum", "volume", "risk"}) {
            assertEquals(e.get("factors").get(k).asDouble(),
                    ((Number) f.get(k)).doubleValue(), 1e-6, "因子 " + k);
        }

        // 标量指标：两边都是先算 double 再独立 round，留 0.02 容忍 HALF_UP / 银行家舍入的边界差
        String[] scalars = {"ma5", "ma10", "ma20", "ma60", "r5", "r20", "r60", "rsi",
                "atr", "atr_pct", "vol_ratio", "vol_ann", "dd60", "pos60", "rs",
                "support", "resistance"};
        for (String k : scalars) {
            if (e.get(k).isNull()) continue;
            assertNotNull(a.get(k), "缺失字段 " + k);
            assertEquals(e.get(k).asDouble(), ((Number) a.get(k)).doubleValue(), 0.02, "字段 " + k);
        }

        Map<?, ?> macd = (Map<?, ?>) a.get("macd");
        for (String k : new String[]{"dif", "dea", "hist", "hist_prev", "dif_prev"}) {
            assertEquals(e.get("macd").get(k).asDouble(),
                    ((Number) macd.get(k)).doubleValue(), 1e-3, "macd." + k);
        }
        Map<?, ?> boll = (Map<?, ?>) a.get("boll");
        for (String k : new String[]{"up", "mid", "low"}) {
            assertEquals(e.get("boll").get(k).asDouble(),
                    ((Number) boll.get(k)).doubleValue(), 0.02, "boll." + k);
        }
    }

    @Test
    @DisplayName("对拍：相关性矩阵对角为 1、对称、同序列相关为 1")
    void correlationMatchesPython() {
        List<Double> closes = new ArrayList<>();
        for (Object[] b : bars) closes.add((Double) b[2]);
        List<Double> benchCloses = new ArrayList<>();
        for (Object[] b : bench) benchCloses.add((Double) b[2]);

        LinkedHashMap<String, List<Double>> series = new LinkedHashMap<>();
        series.put("MAIN", closes);
        series.put("BENCH", benchCloses);
        series.put("MAIN2", closes.stream().map(c -> c * 1.01).toList());

        Map<String, Object> r = svc.correlation(series);
        assertEquals(List.of("MAIN", "BENCH", "MAIN2"), r.get("labels"));

        @SuppressWarnings("unchecked")
        List<List<Double>> m = (List<List<Double>>) r.get("matrix");
        assertEquals(3, m.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, m.get(i).get(i), 1e-9, "对角必须是 1");
            for (int j = 0; j < 3; j++) {
                assertEquals(m.get(i).get(j), m.get(j).get(i), 1e-9, "矩阵必须对称");
                assertTrue(Math.abs(m.get(i).get(j)) <= 1.0 + 1e-9, "相关系数必须落在 [-1,1]");
            }
        }

        JsonNode e = exp.get("correlation").get("matrix");
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(e.get(i).get(j).asDouble(), m.get(i).get(j), 0.02);
            }
        }
    }

    @Test
    @DisplayName("不变量：单调上涨的评分显著高于单调下跌")
    void uptrendBeatsDowntrend() {
        double up = (Double) svc.analyze(trend(80, 100, 0.008, 1_000_000), null).get("score");
        double down = (Double) svc.analyze(trend(80, 100, -0.008, 1_000_000), null).get("score");
        assertTrue(up > down + 25,
                "涨势 score=" + up + " 应明显高于跌势 score=" + down + "，否则趋势/动量因子失效");
    }

    @Test
    @DisplayName("不变量：任意输入下 score 与各因子都落在 0~100")
    void scoreAlwaysInRange() {
        double[][] cases = {{0.03, 1e6}, {-0.03, 1e6}, {0.0, 1e3}, {0.001, 5e7}, {-0.001, 1e2}};
        for (double[] c : cases) {
            Map<String, Object> a = svc.analyze(trend(80, 100, c[0], c[1]), null);
            double score = (Double) a.get("score");
            assertTrue(score >= 0 && score <= 100, "score 越界: " + score);
            Map<?, ?> f = (Map<?, ?>) a.get("factors");
            for (String k : new String[]{"trend", "momentum", "volume", "risk"}) {
                double v = ((Number) f.get(k)).doubleValue();
                assertTrue(v >= 0 && v <= 100, k + " 越界: " + v);
            }
            assertNotNull(a.get("conclusion"));
        }
    }

    @Test
    @DisplayName("K 线不足 65 根时返回 ok=false，而不是硬算出一个分数")
    void notEnoughBars() {
        Map<String, Object> a = svc.analyze(trend(64, 100, 0.005, 1e6), null);
        assertEquals(false, a.get("ok"));
        assertEquals("K线数据不足", a.get("reason"));
        assertFalse(a.containsKey("score"));

        assertEquals(false, svc.analyze(null, null).get("ok"));
        assertEquals(false, svc.analyze(List.of(), null).get("ok"));
    }

    @Test
    @DisplayName("缺基准时不输出 rs 字段，有基准时才有")
    void benchmarkOptional() {
        Map<String, Object> without = svc.analyze(bars, null);
        assertTrue(without.containsKey("rs"));
        assertEquals(null, without.get("rs"));

        Map<String, Object> with = svc.analyze(bars, bench);
        assertNotNull(with.get("rs"));
    }
}
