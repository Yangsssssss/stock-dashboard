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
        try {
            Process p = new ProcessBuilder(cmd).start();
            byte[] out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("curl timeout: " + shortUrl(url));
            }
            if (p.exitValue() != 0 || out.length == 0) {
                throw new IOException("curl rc=" + p.exitValue() + ": " + shortUrl(url));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + shortUrl(url), e);
        }
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

    private static String shortUrl(String url) {
        return url.length() > 60 ? url.substring(0, 60) : url;
    }
}
