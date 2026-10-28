package com.quantboard.indicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.quantboard.testkit.Fixtures;
import com.quantboard.util.U;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对拍测试（differential / golden test）：Java 指标 vs Python 老版指标，同一份输入必须同一份输出。
 *
 * 期望值由 fixtures/generate.py 调用 legacy/quant/indicators.py 算出并固化在 expected.json。
 * 这是本工程最划算的一类测试——Java 是 Python 的翻译版，注释里承诺「逐条对齐」，
 * 靠肉眼 review 无法保证；靠这份带固定种子的 120 根 K 线可以保证。
 */
class IndicatorsGoldenTest {

    private static List<Object[]> bars;
    private static List<Double> closes;
    private static JsonNode exp;
    private static JsonNode prim;

    @BeforeAll
    static void load() {
        bars = Fixtures.bars("bars_120.csv");
        closes = new ArrayList<>();
        for (Object[] b : bars) closes.add((Double) b[2]);
        exp = Fixtures.expected();
        prim = exp.get("primitives");
    }

    @Test
    @DisplayName("夹具自检：120 根 bar，格式为 date/o/c/h/l/v")
    void fixtureShape() {
        assertEquals(120, bars.size());
        Object[] b = bars.get(0);
        assertEquals(6, b.length);
        assertTrue(((String) b[0]).matches("\\d{4}-\\d{2}-\\d{2}"));
        double high = (Double) b[3], low = (Double) b[4], close = (Double) b[2];
        assertTrue(high >= close && close >= low, "high/close/low 顺序写反会静默算错 ATR");
    }

    @Test
    @DisplayName("SMA / EMA / STDEV 与 Python 一致")
    void movingAverages() {
        List<Double> ma5 = U.sma(closes, 5);
        List<Double> ma20 = U.sma(closes, 20);
        List<Double> ma60 = U.sma(closes, 60);
        assertEquals(prim.get("sma5_last").asDouble(), ma5.get(ma5.size() - 1), 1e-9);
        assertEquals(prim.get("sma20_last").asDouble(), ma20.get(ma20.size() - 1), 1e-9);
        assertEquals(prim.get("sma60_last").asDouble(), ma60.get(ma60.size() - 1), 1e-9);
        assertEquals(prim.get("ema12_last").asDouble(),
                U.ema(closes, 12).get(closes.size() - 1), 1e-9);
        assertEquals(prim.get("stdev_last20").asDouble(),
                U.stdev(closes.subList(closes.size() - 20, closes.size())), 1e-9);
    }

    @Test
    @DisplayName("RSI(14) 与 Python 一致")
    void rsi() {
        assertEquals(prim.get("rsi14").asDouble(), Indicators.rsi(closes, 14), 1e-9);
    }

    @Test
    @DisplayName("MACD(12,26,9) 五个字段全部一致")
    void macd() {
        Indicators.Macd m = Indicators.macd(closes, 12, 26, 9);
        JsonNode e = prim.get("macd");
        assertEquals(e.get("dif").asDouble(), m.dif(), 1e-9);
        assertEquals(e.get("dea").asDouble(), m.dea(), 1e-9);
        assertEquals(e.get("hist").asDouble(), m.hist(), 1e-9);
        assertEquals(e.get("hist_prev").asDouble(), m.histPrev(), 1e-9);
        assertEquals(e.get("dif_prev").asDouble(), m.difPrev(), 1e-9);
    }

    @Test
    @DisplayName("ATR(14) 与 Python 一致（依赖 bar 的 high/low 下标）")
    void atr() {
        assertEquals(prim.get("atr14").asDouble(), Indicators.atr(bars, 14), 1e-9);
    }

    @Test
    @DisplayName("布林带(20,2) 与 Python 一致")
    void boll() {
        Indicators.Boll b = Indicators.boll(closes, 20, 2.0);
        JsonNode e = prim.get("boll");
        assertEquals(e.get("mid").asDouble(), b.mid(), 1e-9);
        assertEquals(e.get("up").asDouble(), b.up(), 1e-9);
        assertEquals(e.get("low").asDouble(), b.low(), 1e-9);
        assertEquals(e.get("sd").asDouble(), b.sd(), 1e-9);
    }

    @Test
    @DisplayName("数据不足时一律返回 null，不抛异常也不给假数值")
    void insufficientData() {
        assertNull(Indicators.rsi(closes.subList(0, 14), 14));   // 需要 n+1 根
        assertNull(Indicators.atr(bars.subList(0, 14), 14));
        assertNull(Indicators.macd(closes.subList(0, 20), 12, 26, 9));
        assertNull(Indicators.boll(closes.subList(0, 10), 20, 2.0));
    }
}
