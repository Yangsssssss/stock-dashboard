package com.quantboard.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 数值工具，与 Python 版行为对齐。 */
public final class U {
    private U() {}

    /** Python _num：解析失败返回 default。 */
    public static double num(Object x, double dft) {
        if (x == null) return dft;
        String s = String.valueOf(x).trim();
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("None") || s.equalsIgnoreCase("null")) return dft;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    public static double num(Object x) {
        return num(x, 0.0);
    }

    public static Double round(Double v, int n) {
        if (v == null) return null;
        return round(v.doubleValue(), n);
    }

    public static double round(double v, int n) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return BigDecimal.valueOf(v).setScale(n, RoundingMode.HALF_UP).doubleValue();
    }

    public static Double round3(Double v) {
        return v == null ? null : round(v, 3);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** (a / b - 1) * 100，b 为 0 时返回 0。 */
    public static double pct(double a, double b) {
        if (b == 0) return 0.0;
        return (a / b - 1) * 100;
    }

    /** 简单移动平均，前面不足处为 null。 */
    public static List<Double> sma(List<Double> vals, int n) {
        List<Double> out = new ArrayList<>(vals.size());
        double s = 0.0;
        for (int i = 0; i < vals.size(); i++) {
            s += vals.get(i);
            if (i >= n) s -= vals.get(i - n);
            out.add(i >= n - 1 ? s / n : null);
        }
        return out;
    }

    public static List<Double> ema(List<Double> vals, int n) {
        List<Double> out = new ArrayList<>(vals.size());
        if (vals.isEmpty()) return out;
        double k = 2.0 / (n + 1);
        out.add(vals.get(0));
        for (int i = 1; i < vals.size(); i++) {
            out.add(vals.get(i) * k + out.get(i - 1) * (1 - k));
        }
        return out;
    }

    /** 样本标准差（n-1）。 */
    public static double stdev(List<Double> vals) {
        int n = vals.size();
        if (n < 2) return 0.0;
        double m = 0.0;
        for (double v : vals) m += v;
        m /= n;
        double s = 0.0;
        for (double v : vals) s += (v - m) * (v - m);
        return Math.sqrt(s / (n - 1));
    }
}
