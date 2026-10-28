package com.quantboard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值工具：全站所有指标的底座。这里错一位小数，评分、收益、持仓全线跟着错。
 * 重点覆盖「脏数据」和「除零」两类真实踩过的坑（腾讯/东财返回 "-"、"None"、空串）。
 */
class UTest {

    @Nested
    @DisplayName("num：脏字符串解析")
    class Num {
        @Test
        void dirtyStringsFallBack() {
            assertEquals(0.0, U.num(null));
            assertEquals(-1.0, U.num(null, -1.0));
            assertEquals(0.0, U.num(""));
            assertEquals(0.0, U.num("  "));
            assertEquals(0.0, U.num("-"));          // 腾讯停牌/无数据返回 "-"
            assertEquals(0.0, U.num("None"));
            assertEquals(0.0, U.num("null"));
            assertEquals(7.0, U.num("null", 7.0));
        }

        @Test
        void plainNumbers() {
            assertEquals(1327.34, U.num("1327.34"));
            assertEquals(1327.34, U.num(" 1327.34 "));
            assertEquals(-2.11, U.num(-2.11));
            assertEquals(42.0, U.num(42));          // Integer 也要能转
        }

        @Test
        void unparsableDoesNotThrow() {
            assertEquals(0.0, U.num("--"));
            assertEquals(0.0, U.num("N/A"));
            assertEquals(3.0, U.num("abc", 3.0));
        }
    }

    @Nested
    @DisplayName("round：HALF_UP 且吞掉 NaN/Inf")
    class Round {
        @Test
        void halfUpNotBankers() {
            assertEquals(2.35, U.round(2.345, 2), 1e-9);
            assertEquals(0.13, U.round(0.125, 2), 1e-9);   // double 版 HALF_UP
            assertEquals(2.0, U.round(1.5, 0), 1e-9);
            assertEquals(-2.35, U.round(-2.345, 2), 1e-9); // BigDecimal 对负数也是绝对值进位
        }

        @Test
        void nanAndInfinityToZero() {
            assertEquals(0.0, U.round(Double.NaN, 2));
            assertEquals(0.0, U.round(Double.POSITIVE_INFINITY, 3));
        }

        @Test
        void nullPassThrough() {
            assertNull(U.round((Double) null, 2));
            assertNull(U.round3(null));
        }
    }

    @Nested
    @DisplayName("pct：除零与符号")
    class Pct {
        @Test
        void zeroDenominator() {
            assertEquals(0.0, U.pct(100, 0));
            assertEquals(0.0, U.pct(0, 0));
        }

        @Test
        void returnsPercentNotFraction() {
            assertEquals(10.0, U.pct(110, 100), 1e-9);
            assertEquals(-50.0, U.pct(50, 100), 1e-9);
        }
    }

    @Nested
    @DisplayName("clamp / sma / ema / stdev")
    class Series {
        @Test
        void clampBounds() {
            assertEquals(0.0, U.clamp(-5, 0, 100));
            assertEquals(100.0, U.clamp(150, 0, 100));
            assertEquals(37.5, U.clamp(37.5, 0, 100));
        }

        @Test
        void smaLeavesLeadingNulls() {
            List<Double> v = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
            List<Double> s = U.sma(v, 3);
            assertEquals(5, s.size());
            assertNull(s.get(0));
            assertNull(s.get(1));
            assertEquals(2.0, s.get(2), 1e-9);
            assertEquals(3.0, s.get(3), 1e-9);
            assertEquals(4.0, s.get(4), 1e-9);
        }

        @Test
        void emaSeedsWithFirstValue() {
            List<Double> v = List.of(10.0, 20.0);
            List<Double> e = U.ema(v, 3);   // k = 0.5
            assertEquals(10.0, e.get(0), 1e-9);
            assertEquals(15.0, e.get(1), 1e-9);
            assertTrue(U.ema(List.of(), 5).isEmpty());
        }

        @Test
        void stdevIsSampleStdev() {
            // [2,4,4,4,5,5,7,9] 总体标准差 2，样本标准差 = 2*sqrt(8/7)
            List<Double> v = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
            assertEquals(2.0 * Math.sqrt(8.0 / 7.0), U.stdev(v), 1e-12);
            assertEquals(0.0, U.stdev(List.of(3.0)));     // 单点不炸
            assertEquals(0.0, U.stdev(List.of()));
        }
    }
}
