package com.quantboard.feed;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 抓取：经系统 curl 子进程发出。
 * 东财等源站会按 TLS 指纹识别 Python/JVM 客户端并断连或降速，curl 指纹正常。
 */
public final class HttpFetch {
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private HttpFetch() {}

    /**
     * 自定义请求头版本（SEC EDGAR 要求申报身份 UA）。
     * 调用方自带 User-Agent 时不再叠加默认浏览器 UA —— curl 会把两个都发出去，
     * SEC 按浏览器 UA 判定为「未申报的自动化工具」直接 403。
     */
    public static byte[] get(String url, int timeoutSec, String... headers) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-s");
        cmd.add("--max-time");
        cmd.add(String.valueOf(timeoutSec));
        cmd.add("--compressed");
        if (!hasHeader(headers, "user-agent")) {
            cmd.add("-H");
            cmd.add("User-Agent: " + UA);
        }
        for (String h : headers) {
            if (h == null || h.isBlank()) continue;
            cmd.add("-H");
            cmd.add(h);
        }
        cmd.add(url);
        return run(cmd, timeoutSec, url);
    }

    /** POST JSON（OpenFIGI 等只需要 body 的接口）。请求体走临时文件，避免命令行转义问题。 */
    public static byte[] postJson(String url, String json, int timeoutSec, String... headers) throws IOException {
        java.nio.file.Path body = java.nio.file.Files.createTempFile("qbpost", ".json");
        try {
            java.nio.file.Files.writeString(body, json, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>();
            cmd.add("curl");
            cmd.add("-s");
            cmd.add("--max-time");
            cmd.add(String.valueOf(timeoutSec));
            cmd.add("--compressed");
            if (!hasHeader(headers, "user-agent")) {
                cmd.add("-H");
                cmd.add("User-Agent: " + UA);
            }
            cmd.add("-X");
            cmd.add("POST");
            cmd.add("-H");
            cmd.add("Content-Type: application/json");
            for (String h : headers) {
                if (h == null || h.isBlank()) continue;
                cmd.add("-H");
                cmd.add(h);
            }
            cmd.add("--data-binary");
            cmd.add("@" + body);
            cmd.add(url);
            return run(cmd, timeoutSec, url);
        } finally {
            try { java.nio.file.Files.deleteIfExists(body); } catch (IOException ignore) { }
        }
    }

    private static boolean hasHeader(String[] headers, String name) {
        String p = name.toLowerCase() + ":";
        for (String h : headers) {
            if (h != null && h.toLowerCase().startsWith(p)) return true;
        }
        return false;
    }

    public static byte[] get(String url, String referer, int timeoutSec) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-s");
        cmd.add("--max-time");
        cmd.add(String.valueOf(timeoutSec));
        cmd.add("--compressed");
        cmd.add("-H");
        cmd.add("User-Agent: " + UA);
        if (referer != null && !referer.isEmpty()) {
            cmd.add("-H");
            cmd.add("Referer: " + referer);
        }
        cmd.add(url);
        return run(cmd, timeoutSec, url);
    }

    /**
     * 直连失败（curl rc=7，典型场景：国内网络直连 data.sec.gov 被墙）时，
     * 自动探测本机代理重试一次。顺序：显式环境变量 → macOS 系统代理（scutil）。
     * 只在失败后兜底，国内数据源（腾讯/东财）正常路径零开销、不经过代理。
     */
    private static byte[] run(List<String> cmd, int timeoutSec, String url) throws IOException {
        try {
            return runOnce(cmd, timeoutSec, url);
        } catch (IOException e) {
            String m = e.getMessage();
            if (m != null && m.startsWith("curl rc=7") && !hasProxyArg(cmd)) {
                String proxy = detectProxy();
                if (proxy != null) {
                    List<String> retry = new ArrayList<>(cmd);
                    retry.add(retry.size() - 1, "-x");
                    retry.add(retry.size() - 1, proxy);
                    return runOnce(retry, timeoutSec, url);
                }
            }
            throw e;
        }
    }

    private static boolean hasProxyArg(List<String> cmd) {
        return cmd.contains("-x") || cmd.contains("--proxy");
    }

    private static volatile String proxyCache;
    private static volatile boolean proxyResolved = false;

    /** 探测可用代理，结果缓存（进程生命周期内一次）。探测不到返回 null。 */
    private static String detectProxy() {
        if (proxyResolved) return proxyCache;
        synchronized (HttpFetch.class) {
            if (proxyResolved) return proxyCache;
            proxyCache = resolveProxy();
            proxyResolved = true;
            return proxyCache;
        }
    }

    private static String resolveProxy() {
        // 1) 显式环境变量优先（.env 里配 QUANTBOARD_HTTP_PROXY=http://127.0.0.1:7890）
        for (String k : new String[]{"QUANTBOARD_HTTP_PROXY", "QUANTBOARD_HTTPS_PROXY",
                "https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"}) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                v = v.trim();
                if (v.contains("://") || v.matches(".+:\\d+")) return v.startsWith("http") ? v : "http://" + v;
            }
        }
        // 2) macOS 系统代理（Clash/Surge 等都会写入）
        try {
            Process p = new ProcessBuilder("scutil", "--proxy").start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            if (out.replaceAll("\\s", "").contains("HTTPSEnable:1")) {
                String host = match(out, "HTTPSProxy\\s*:\\s*(\\S+)");
                String port = match(out, "HTTPSPort\\s*:\\s*(\\d+)");
                if (host != null && port != null) return "http://" + host + ":" + port;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private static String match(String s, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] runOnce(List<String> cmd, int timeoutSec, String url) throws IOException {
        java.nio.file.Path tmp = null;
        try {
            // 响应体写临时文件、stdout 只留 HTTP 状态码：被 WAF 拦截时能直接报 403 而不是「JSON 解析失败」
            tmp = java.nio.file.Files.createTempFile("qbfetch", ".body");
            List<String> c = new ArrayList<>(cmd.subList(0, cmd.size() - 1));
            c.add("-o");
            c.add(tmp.toString());
            c.add("-w");
            c.add("%{http_code}");
            c.add(cmd.get(cmd.size() - 1));

            Process p = new ProcessBuilder(c).start();
            byte[] statusOut;
            try (java.io.InputStream in = p.getInputStream()) {
                statusOut = in.readAllBytes();
            }
            if (!p.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("curl timeout: " + shortUrl(url));
            }
            int rc = p.exitValue();
            byte[] body = java.nio.file.Files.readAllBytes(tmp);
            if (rc != 0) {
                throw new IOException("curl rc=" + rc + ": " + shortUrl(url));
            }
            int code = 0;
            try {
                code = Integer.parseInt(new String(statusOut, StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException ignore) { /* 拿不到状态码就按内容判断 */ }
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + snippet(body) + " <- " + shortUrl(url));
            }
            if (body.length == 0) {
                throw new IOException("响应为空（HTTP " + code + "）: " + shortUrl(url));
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + shortUrl(url), e);
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (IOException ignore) { }
            }
        }
    }

    /** 响应体片段，用于错误信息（去掉换行、截断）。 */
    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) return "(empty)";
        String s = new String(body, 0, Math.min(body.length, 400), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    public static byte[] get(String url, String referer) throws IOException {
        return get(url, referer, 10);
    }

    public static byte[] get(String url) throws IOException {
        return get(url, null, 10);
    }

    public static String getText(String url, String referer, Charset cs) throws IOException {
        return new String(get(url, referer), cs);
    }

    public static String getUtf8(String url, String referer) throws IOException {
        return new String(get(url, referer), StandardCharsets.UTF_8);
    }

    /** 带自定义超时（秒）的 UTF-8 文本抓取。 */
    public static String getUtf8(String url, String referer, int timeoutSec) throws IOException {
        return new String(get(url, referer, timeoutSec), StandardCharsets.UTF_8);
    }

    private static String shortUrl(String url) {
        return url.length() > 60 ? url.substring(0, 60) : url;
    }
}
