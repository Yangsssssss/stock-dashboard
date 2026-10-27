package com.quantboard.indicator;

import com.quantboard.util.U;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多因子评分引擎：0.34×趋势 + 0.30×动量 + 0.16×量能 + 0.20×风险。
 * 与 Python 版 indicators.analyze / correlation 逐条对齐。
 */
@Service
public class AnalysisService {

    /**
     * bars 元素为 [date, open, close, high, low, volume]。
     * 返回分析结果 Map；数据不足时 {ok:false, reason:...}。
     */
    public Map<String, Object> analyze(List<Object[]> bars, List<Object[]> benchmarkBars) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (bars == null || bars.size() < 65) {
            out.put("ok", false);
            out.put("reason", "K线数据不足");
            return out;
        }

        List<Double> closes = new ArrayList<>(bars.size());
        for (Object[] b : bars) closes.add(U.num(b[2]));
        double price = closes.get(closes.size() - 1);

        List<Double> maAll5 = U.sma(closes, 5);
        List<Double> maAll10 = U.sma(closes, 10);
        List<Double> maAll20 = U.sma(closes, 20);
        List<Double> maAll60 = U.sma(closes, 60);
        Double ma5 = maAll5.get(maAll5.size() - 1);
        Double ma10 = maAll10.get(maAll10.size() - 1);
        Double ma20 = maAll20.get(maAll20.size() - 1);
        Double ma60 = maAll60.get(maAll60.size() - 1);

        double v5 = 0, v20 = 0;
        int n = closes.size();
        for (int i = n - 5; i < n; i++) v5 += U.num(bars.get(i)[5]);
        for (int i = n - 20; i < n; i++) v20 += U.num(bars.get(i)[5]);
        v5 /= 5;
        v20 /= 20;
        double volRatio = v20 != 0 ? v5 / v20 : 0.0;

        double r5 = n > 6 ? U.pct(price, closes.get(n - 6)) : 0.0;
        double r20 = n > 21 ? U.pct(price, closes.get(n - 21)) : 0.0;
        double r60 = n > 61 ? U.pct(price, closes.get(n - 61)) : 0.0;
        Double rsi14 = Indicators.rsi(closes, 14);
        Indicators.Macd m = Indicators.macd(closes, 12, 26, 9);
        Double at = Indicators.atr(bars, 14);
        Indicators.Boll bb = Indicators.boll(closes, 20, 2.0);

        List<Double> rets = new ArrayList<>(closes.size());
        for (int i = 1; i < closes.size(); i++) rets.add(closes.get(i) / closes.get(i - 1) - 1);
        double volAnn = rets.size() >= 20 ? U.stdev(rets.subList(rets.size() - 60, rets.size())) * Math.sqrt(252) * 100 : 0.0;

        double hi60 = Double.NEGATIVE_INFINITY, lo60 = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, n - 60); i < n; i++) {
            hi60 = Math.max(hi60, closes.get(i));
            lo60 = Math.min(lo60, closes.get(i));
        }
        double dd = U.pct(price, hi60);
        double pos60 = hi60 > lo60 ? (price - lo60) / (hi60 - lo60) * 100 : 50.0;

        // 相对强弱：20 日超额收益
        Double rs = null;
        if (benchmarkBars != null && benchmarkBars.size() > 21) {
            double bcLast = U.num(benchmarkBars.get(benchmarkBars.size() - 1)[2]);
            double bcPrev = U.num(benchmarkBars.get(benchmarkBars.size() - 21)[2]);
            rs = r20 - U.pct(bcLast, bcPrev);
        }

        List<String> notes = new ArrayList<>();

        // 1) 趋势：均线结构 + 区间位置
        double trend = 50.0;
        if (ma20 != null) trend += price > ma20 ? 16 : -16;
        if (ma20 != null && ma60 != null) {
            if (ma20 > ma60) { trend += 14; notes.add("中期趋势向上（MA20>MA60）"); }
            else { trend -= 14; notes.add("中期趋势向下（MA20<MA60）"); }
        }
        if (ma5 != null && ma10 != null && ma20 != null) {
            if (ma5 > ma10 && ma10 > ma20) { trend += 8; notes.add("短中期均线多头排列"); }
            else if (ma5 < ma10 && ma10 < ma20) { trend -= 8; notes.add("短中期均线空头排列"); }
        }
        trend += (pos60 - 50) * 0.4;
        trend = U.clamp(trend, 0, 100);

        // 2) 动量：区间涨幅 + RSI + 相对强弱
        double momentum = 50 + U.clamp(r20 * 1.3, -32, 32) + U.clamp(r5 * 1.1, -18, 18);
        if (rsi14 != null) {
            if (rsi14 >= 50 && rsi14 <= 70) momentum += 8;
            else if (rsi14 > 70 && rsi14 <= 80) momentum += 2;
            else if (rsi14 > 80) { momentum -= 12; notes.add(String.format("RSI %.0f 进入超买区", rsi14)); }
            else if (rsi14 < 30) { momentum += 5; notes.add(String.format("RSI %.0f 处于超卖区", rsi14)); }
        }
        if (rs != null) {
            momentum += U.clamp(rs * 1.5, -8, 8);
            if (rs > 3) notes.add(String.format("20 日跑赢基准 %.1fpct", rs));
            else if (rs < -3) notes.add(String.format("20 日跑输基准 %.1fpct", Math.abs(rs)));
        }
        momentum = U.clamp(momentum, 0, 100);

        // 3) 量能：量比方向配合 + MACD 动能
        double volumeF = 50.0;
        if (volRatio >= 1.3) {
            if (r5 > 0) { volumeF += 22; notes.add(String.format("放量上涨（量比 %.1f）", volRatio)); }
            else { volumeF -= 22; notes.add(String.format("放量下跌（量比 %.1f）", volRatio)); }
        } else if (volRatio > 0 && volRatio <= 0.7) {
            volumeF -= 6;
            notes.add("成交明显缩量");
        }
        if (m != null) {
            if (m.hist() > 0 && m.hist() >= m.histPrev()) { volumeF += 16; notes.add("MACD 红柱走强"); }
            else if (m.hist() > 0) volumeF += 8;
            else { volumeF -= 16; notes.add("MACD 绿柱，动能偏弱"); }
        }
        volumeF = U.clamp(volumeF, 0, 100);

        // 4) 风险：波动率与回撤
        double risk = 50 - Math.max(0.0, volAnn - 25) * 0.7
                + Math.min(12.0, Math.max(0.0, 25 - volAnn) * 0.45)
                - Math.max(0.0, -dd - 8) * 0.9;
        if (at != null && price != 0 && (at / price * 100) < 1.5) risk += 6;
        risk = U.clamp(risk, 0, 100);

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("trend", U.round(trend, 1));
        factors.put("momentum", U.round(momentum, 1));
        factors.put("volume", U.round(volumeF, 1));
        factors.put("risk", U.round(risk, 1));

        double score = U.clamp(0.34 * trend + 0.30 * momentum + 0.16 * volumeF + 0.20 * risk, 0, 100);
        score = U.round(score, 1);

        String sig, cls;
        if (score >= 72) { sig = "强势"; cls = "strong"; }
        else if (score >= 58) { sig = "偏强"; cls = "good"; }
        else if (score >= 44) { sig = "中性"; cls = "neutral"; }
        else if (score >= 30) { sig = "偏弱"; cls = "weak"; }
        else { sig = "弱势"; cls = "bad"; }

        // 结论一句话（趋势 + 涨幅 + 量能 + 最多两条补充信号）
        List<String> parts = new ArrayList<>();
        if (ma20 != null && ma60 != null) {
            parts.add(ma20 > ma60 ? "中期趋势向上" : "中期趋势仍在下行");
        }
        parts.add(String.format("20 日 %+.1f%%", r20));
        if (volRatio > 0) {
            parts.add(volRatio > 1.25 ? "量能放大" : (volRatio < 0.75 ? "量能萎缩" : "量能平稳"));
        }
        String conclusion = String.join("，", parts);
        List<String> extra = notes.stream()
                .filter(x -> !x.startsWith("中期趋势") && !x.startsWith("短中期均线"))
                .toList();
        if (!extra.isEmpty()) {
            conclusion += "。" + String.join("；", extra.subList(0, Math.min(2, extra.size()))) + "。";
        }

        out.put("ok", true);
        out.put("score", score);
        out.put("signal", sig);
        out.put("signal_cls", cls);
        out.put("factors", factors);
        out.put("conclusion", conclusion);
        out.put("ma5", U.round3(ma5));
        out.put("ma10", U.round3(ma10));
        out.put("ma20", U.round3(ma20));
        out.put("ma60", U.round3(ma60));
        out.put("r5", U.round(r5, 2));
        out.put("r20", U.round(r20, 2));
        out.put("r60", U.round(r60, 2));
        out.put("rsi", rsi14 != null ? U.round(rsi14, 1) : null);
        if (m != null) {
            Map<String, Object> macdMap = new LinkedHashMap<>();
            macdMap.put("dif", U.round(m.dif(), 4));
            macdMap.put("dea", U.round(m.dea(), 4));
            macdMap.put("hist", U.round(m.hist(), 4));
            macdMap.put("hist_prev", U.round(m.histPrev(), 4));
            macdMap.put("dif_prev", U.round(m.difPrev(), 4));
            out.put("macd", macdMap);
        } else {
            out.put("macd", null);
        }
        out.put("atr", at != null ? U.round(at, 3) : null);
        out.put("atr_pct", at != null && price != 0 ? U.round(at / price * 100, 2) : null);
        out.put("vol_ratio", U.round(volRatio, 2));
        out.put("vol_ann", U.round(volAnn, 1));
        out.put("dd60", U.round(dd, 1));
        out.put("pos60", U.round(pos60, 1));
        out.put("rs", rs != null ? U.round(rs, 2) : null);
        if (bb != null) {
            Map<String, Object> bollMap = new LinkedHashMap<>();
            bollMap.put("up", U.round(bb.up(), 3));
            bollMap.put("mid", U.round(bb.mid(), 3));
            bollMap.put("low", U.round(bb.low(), 3));
            out.put("boll", bollMap);
        } else {
            out.put("boll", null);
        }
        out.put("support", U.round(lo60, 3));
        out.put("resistance", U.round(hi60, 3));
        return out;
    }

    /** 按最后 N 个交易日对齐算收益率相关性。series: {label: closes}。 */
    public Map<String, Object> correlation(LinkedHashMap<String, List<Double>> series) {
        List<String> names = new ArrayList<>(series.keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", names);
        int minLen = series.values().stream().mapToInt(List::size).min().orElse(0);
        if (minLen < 25) {
            List<List<Integer>> zero = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                List<Integer> row = new ArrayList<>();
                for (int j = 0; j < names.size(); j++) row.add(0);
                zero.add(row);
            }
            out.put("matrix", zero);
            return out;
        }
        int n = Math.min(minLen, 60);
        Map<String, List<Double>> rets = new LinkedHashMap<>();
        for (var e : series.entrySet()) {
            List<Double> seg = e.getValue().subList(e.getValue().size() - n, e.getValue().size());
            List<Double> r = new ArrayList<>();
            for (int i = 1; i < seg.size(); i++) r.add(seg.get(i) / seg.get(i - 1) - 1);
            rets.put(e.getKey(), r);
        }
        List<List<Double>> matrix = new ArrayList<>();
        for (String a : names) {
            List<Double> row = new ArrayList<>();
            for (String b : names) row.add(corr(rets.get(a), rets.get(b)));
            matrix.add(row);
        }
        out.put("matrix", matrix);
        return out;
    }

    private static double corr(List<Double> a, List<Double> b) {
        int m = Math.min(a.size(), b.size());
        if (m == 0) return 0.0;
        double ma = 0, mb = 0;
        for (int i = 0; i < m; i++) { ma += a.get(i); mb += b.get(i); }
        ma /= m;
        mb /= m;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < m; i++) {
            cov += (a.get(i) - ma) * (b.get(i) - mb);
            va += (a.get(i) - ma) * (a.get(i) - ma);
            vb += (b.get(i) - mb) * (b.get(i) - mb);
        }
        va = Math.sqrt(va);
        vb = Math.sqrt(vb);
        return (va != 0 && vb != 0) ? U.round(cov / (va * vb), 2) : 0.0;
    }
}
