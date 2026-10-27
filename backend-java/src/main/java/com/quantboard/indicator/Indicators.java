package com.quantboard.indicator;

import com.quantboard.util.U;

import java.util.ArrayList;
import java.util.List;

/** 基础技术指标，与 Python 版 indicators.py 对齐。 */
public final class Indicators {
    private Indicators() {}

    public static double stdev(List<Double> vals) {
        return U.stdev(vals);
    }

    /** Wilder RSI。 */
    public static Double rsi(List<Double> closes, int n) {
        if (closes.size() < n + 1) return null;
        double gains = 0.0, losses = 0.0;
        for (int i = 1; i <= n; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            gains += Math.max(d, 0.0);
            losses += Math.max(-d, 0.0);
        }
        double ag = gains / n, al = losses / n;
        for (int i = n + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            ag = (ag * (n - 1) + Math.max(d, 0.0)) / n;
            al = (al * (n - 1) + Math.max(-d, 0.0)) / n;
        }
        if (al == 0) return 100.0;
        double rs = ag / al;
        return 100 - 100 / (1 + rs);
    }

    /** MACD：dif/dea/hist 及前一根。 */
    public record Macd(double dif, double dea, double hist, double histPrev, double difPrev) {}

    public static Macd macd(List<Double> closes, int fast, int slow, int sig) {
        if (closes.size() < slow) return null;
        List<Double> ef = U.ema(closes, fast), es = U.ema(closes, slow);
        List<Double> dif = new ArrayList<>(ef.size());
        for (int i = 0; i < ef.size(); i++) dif.add(ef.get(i) - es.get(i));
        List<Double> dea = U.ema(dif, sig);
        List<Double> hist = new ArrayList<>(dif.size());
        for (int i = 0; i < dif.size(); i++) hist.add((dif.get(i) - dea.get(i)) * 2);
        return new Macd(dif.get(dif.size() - 1), dea.get(dea.size() - 1), hist.get(hist.size() - 1),
                hist.size() > 1 ? hist.get(hist.size() - 2) : 0.0,
                dif.size() > 1 ? dif.get(dif.size() - 2) : 0.0);
    }

    /** ATR（Wilder 平滑）。bar = [date, open, close, high, low, volume]，high=3 low=4 close=2。 */
    public static Double atr(List<Object[]> bars, int n) {
        if (bars.size() < n + 1) return null;
        List<Double> trs = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            double h = U.num(bars.get(i)[3]), l = U.num(bars.get(i)[4]), pc = U.num(bars.get(i - 1)[2]);
            trs.add(Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc))));
        }
        if (trs.size() < n) return null;
        double a = 0.0;
        for (int i = 0; i < n; i++) a += trs.get(i);
        a /= n;
        for (int i = n; i < trs.size(); i++) a = (a * (n - 1) + trs.get(i)) / n;
        return a;
    }

    /** 布林带。 */
    public record Boll(double mid, double up, double low, double sd) {}

    public static Boll boll(List<Double> closes, int n, double k) {
        if (closes.size() < n) return null;
        List<Double> seg = closes.subList(closes.size() - n, closes.size());
        double mid = 0.0;
        for (double v : seg) mid += v;
        mid /= n;
        double sd = stdev(seg);
        return new Boll(mid, mid + k * sd, mid - k * sd, sd);
    }
}
