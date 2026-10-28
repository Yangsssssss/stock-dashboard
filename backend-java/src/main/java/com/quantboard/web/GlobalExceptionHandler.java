package com.quantboard.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常出口。
 *
 * 之前所有 Controller 都没有兜底：任何未捕获异常（DB 掉线、外部源超时、NumberFormatException）
 * 都直接落到 Spring 默认的 500 HTML/空响应，前端 `await r.json()` 抛 SyntaxError，
 * 界面上只会看到"加载失败"，拿不到任何可用于定位的信息。
 *
 * 现在统一返回 {ok:false, error, path, ts}，完整堆栈只进日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数类错误 → 400：缺参、类型不匹配、请求体不是合法 JSON、业务校验抛的 IllegalArgumentException。 */
    @ExceptionHandler({IllegalArgumentException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest req) {
        String msg = e instanceof MissingServletRequestParameterException m
                ? "缺少参数：" + m.getParameterName()
                : e instanceof MethodArgumentTypeMismatchException t
                ? "参数 " + t.getName() + " 格式不正确"
                : e instanceof HttpMessageNotReadableException
                ? "请求体不是合法 JSON"
                : humanize(e);
        log.warn("[api] 400 {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.BAD_REQUEST, msg, req, "bad_request");
    }

    /**
     * 兜底：Spring 自带的 ErrorResponse（404 / 405 / 415 等）保留原状态码，
     * 其余未预期异常 → 500。日志留全栈，响应只给一句能看懂的话。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> anyOther(Exception e, HttpServletRequest req) {
        // NoResourceFoundException / ResponseStatusException 等都实现了 ErrorResponse，沿用它们的状态码，
        // 否则访问一个不存在的静态文件会被这条兜底改写成 500。
        if (e instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            String detail = er.getBody().getDetail();
            return build(status, detail != null ? detail : status.getReasonPhrase(), req, "http_" + status.value());
        }
        log.error("[api] 500 {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, humanize(e), req, "internal_error");
    }

    private static String humanize(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        // 堆栈里常带换行/超长 SQL，截断后只留第一行
        String first = m.split("\n", 2)[0].trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String msg,
                                                             HttpServletRequest req, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", msg);
        body.put("code", code);
        body.put("status", status.value());
        body.put("path", req == null ? "" : req.getRequestURI());
        body.put("ts", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return ResponseEntity.status(status).body(body);
    }
}
