package com.quantboard.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具读取器：把 src/test/resources/fixtures 下的文件读成 Java 数据结构。
 *
 * 金标准文件由 fixtures/generate.py 用 legacy/quant/indicators.py（Python 版）生成，
 * 修指标算法时应「先改 Python 或明确接受偏离 → 重跑 generate.py → 再改 Java」。
 */
public final class Fixtures {
    private static final ObjectMapper M = new ObjectMapper();

    private Fixtures() {}

    /** 读 CSV 日线 → List<Object[]>{date, open, close, high, low, volume}。 */
    public static List<Object[]> bars(String name) {
        String text = text(name);
        List<Object[]> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 1; i < lines.length; i++) {          // 跳过表头
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            String[] c = line.split(",");
            out.add(new Object[]{c[0],
                    Double.parseDouble(c[1]), Double.parseDouble(c[2]),
                    Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                    Double.parseDouble(c[5])});
        }
        return out;
    }

    /** 读 expected.json。 */
    public static JsonNode expected() {
        try {
            return M.readTree(text("expected.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读原始字节（GBK 等非 UTF-8 样本用）。 */
    public static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("fixtures 不存在: " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("fixtures 不存在: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
