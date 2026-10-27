package com.quantboard.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 截图 OCR：优先用 macOS 自带 Vision 框架（中文识别质量远高于 tesseract），
 * 首次调用时用 clang 把内嵌 Objective-C 源码编译为 CLI 并缓存（swiftc 在部分
 * CLT 环境有 modulemap 冲突，clang + ObjC 最稳）；不可用时回退 tesseract（chi_sim+eng）。
 *
 * 输出约定：每行一条文本记录；Vision 模式下行内词以 \t 分隔（已按 x 排序、按 y 聚行），
 * 供解析器重建表格列；tesseract 模式只有整行文本。
 */
@Service
public class OcrService {

    private static final String OBJC_SRC = """
            #import <Foundation/Foundation.h>
            #import <AppKit/AppKit.h>
            #import <Vision/Vision.h>

            static void flushLine(NSMutableArray *line) {
                [line sortUsingComparator:^NSComparisonResult(NSArray *a, NSArray *b) {
                    return [a[0] doubleValue] < [b[0] doubleValue] ? NSOrderedAscending : NSOrderedDescending;
                }];
                NSMutableString *out = [NSMutableString string];
                for (NSArray *lw in line) { [out appendString:lw[3]]; [out appendString:@"\\t"]; }
                printf("%s\\n", out.UTF8String);
            }

            int main(int argc, char *argv[]) {
                @autoreleasepool {
                    if (argc < 2) return 2;
                    NSImage *img = [[NSImage alloc] initWithContentsOfFile:
                            [NSString stringWithUTF8String:argv[1]]];
                    if (!img) return 3;
                    CGImageRef cg = [img CGImageForProposedRect:nil context:nil hints:nil];
                    if (!cg) return 3;

                    VNRecognizeTextRequest *req =
                            [[VNRecognizeTextRequest alloc] initWithCompletionHandler:nil];
                    req.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
                    req.recognitionLanguages = @[@"zh-Hans", @"en-US"];
                    req.usesLanguageCorrection = NO;
                    VNImageRequestHandler *handler =
                            [[VNImageRequestHandler alloc] initWithCGImage:cg options:@{}];
                    NSError *err = nil;
                    if (![handler performRequests:@[req] error:&err]) return 4;

                    NSMutableArray *words = [NSMutableArray array];
                    for (VNRecognizedTextObservation *o in req.results) {
                        VNRecognizedText *top = [[o topCandidates:1] firstObject];
                        if (!top) continue;
                        CGRect b = o.boundingBox;
                        [words addObject:@[@(b.origin.x + b.size.width / 2), @(b.origin.y),
                                           @(b.size.height), top.string]];
                    }
                    [words sortUsingComparator:^NSComparisonResult(NSArray *a, NSArray *b) {
                        double ya = [a[1] doubleValue], yb = [b[1] doubleValue];
                        return ya > yb ? NSOrderedAscending
                             : ya < yb ? NSOrderedDescending : NSOrderedSame;
                    }];
                    double lastY = 999, lastH = 0;
                    NSMutableArray *line = [NSMutableArray array];
                    for (NSArray *w in words) {
                        double y = [w[1] doubleValue], h = [w[2] doubleValue];
                        if (line.count && lastY - y > MAX(0.006, lastH * 0.65)) {
                            flushLine(line); [line removeAllObjects];
                        }
                        [line addObject:w]; lastY = y; lastH = h;
                    }
                    if (line.count) flushLine(line);
                }
                return 0;
            }
            """;

    private volatile Path cliPath;
    private volatile boolean cliTried = false;

    /** 返回 OCR 文本行；Vision 行内以 \t 分词。失败返回空列表。 */
    public List<String> recognize(byte[] imageBytes, String ext) {
        if (imageBytes == null || imageBytes.length == 0) return List.of();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("qb-ocr-", "." + (ext == null || ext.isBlank() ? "png" : ext));
            Files.write(tmp, imageBytes);
            List<String> out = runVision(tmp);
            if (!out.isEmpty()) return out;
            return runTesseract(tmp);
        } catch (Exception e) {
            System.out.println("[ocr] 识别失败：" + e.getMessage());
            return List.of();
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
        }
    }

    /** Vision CLI：懒编译 + 缓存。 */
    private List<String> runVision(Path image) throws Exception {
        Path cli = ensureCli();
        if (cli == null) return List.of();
        Process p = new ProcessBuilder(cli.toString(), image.toString())
                .redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return List.of(); }
        if (p.exitValue() != 0) return List.of();
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\n", -1)) lines.add(l.stripTrailing());
        return lines;
    }

    private Path ensureCli() {
        if (cliTried) return cliPath;
        synchronized (this) {
            if (cliTried) return cliPath;
            cliTried = true;
            try {
                Path dir = Path.of(System.getProperty("java.io.tmpdir"), "quantboard-ocr");
                Files.createDirectories(dir);
                Path src = dir.resolve("OcrCLI.m");
                Path bin = dir.resolve("qb-ocr");
                boolean need = !Files.exists(bin)
                        || Files.getLastModifiedTime(bin).compareTo(Files.getLastModifiedTime(src)) < 0;
                if (need) {
                    Files.writeString(src, OBJC_SRC);
                    Process p = new ProcessBuilder(
                            "clang", "-fobjc-arc", "-fmodules", "-O2",
                            "-framework", "Foundation", "-framework", "AppKit", "-framework", "Vision",
                            "-o", bin.toString(), src.toString())
                            .redirectErrorStream(true).start();
                    String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (!p.waitFor(180, TimeUnit.SECONDS) || p.exitValue() != 0) {
                        System.out.println("[ocr] clang 编译失败（将回退 tesseract）："
                                + log.substring(0, Math.min(300, log.length())));
                        return null;
                    }
                }
                cliPath = bin;
                System.out.println("[ocr] 使用 macOS Vision OCR: " + bin);
                return bin;
            } catch (Exception e) {
                System.out.println("[ocr] Vision CLI 不可用（将回退 tesseract）：" + e.getMessage());
                return null;
            }
        }
    }

    private List<String> runTesseract(Path image) throws Exception {
        Path te = Path.of("/opt/homebrew/bin/tesseract");
        if (!Files.isExecutable(te)) te = Path.of("/usr/local/bin/tesseract");
        if (!Files.isExecutable(te)) return List.of();
        Process p = new ProcessBuilder(te.toString(), image.toString(), "stdout",
                "-l", "chi_sim+eng", "--psm", "6").redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return List.of(); }
        List<String> lines = new ArrayList<>();
        for (String l : out.split("\n", -1)) {
            String s = l.strip();
            if (!s.isEmpty()) lines.add(s);
        }
        System.out.println("[ocr] 使用 tesseract 兜底，识别 " + lines.size() + " 行");
        return lines;
    }
}
