package com.quantboard.service;

import com.quantboard.db.DbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 定时采集：把「行情 → 日线 → 评分 → 快照 → 净值」的沉淀，从「用户打开页面」上摘下来。
 *
 * 背景：BoardService 只在 /api/board 被请求时才构建，落库（persistAsync）是它的副作用。
 * 于是不打开页面的日子就没有当日日线/评分，nav_daily 也只在访问收益页时才写，
 * 净值曲线不得不靠 K 线反推（estimated=1）。收益曲线一旦靠估算，
 * 中间有买卖就会失真 —— 所以采集必须由服务自己按点跑。
 *
 * 两个采集点：
 *   - 15:35 A 股收盘后（顺带把港股当日盘中数据补齐）
 *   - 04:35 美股收盘后（美东 16:00 收盘，夏令时对应北京 04:00，冬令时 05:00；取 04:35 覆盖两种）
 *
 * buildBoard(true) 会强制刷新报价并触发异步落库（instrument / kline_daily / quote_snapshot /
 * daily_score 四张表），perf.snapshot() 再补一条当日 nav_daily 真实快照。
 */
@Service
public class CollectService {
    private static final Logger log = LoggerFactory.getLogger(CollectService.class);

    private final BoardService board;
    private final DbService db;
    private final PerformanceService perf;
    private final int keepDays;

    public CollectService(BoardService board, DbService db, PerformanceService perf,
                          @Value("${quantboard.snapshot-keep-days:90}") int keepDays) {
        this.board = board;
        this.db = db;
        this.perf = perf;
        this.keepDays = keepDays;
    }

    /** A 股收盘后：日线落库 + 当日净值快照。港股的当日行情此时也已在盘中更新。 */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void collectAfterCnClose() {
        collect("A 股收盘");
    }

    /** 美股收盘后：周二至周六凌晨对应美东周一至周五收盘。 */
    @Scheduled(cron = "0 35 4 * * TUE-SAT", zone = "Asia/Shanghai")
    public void collectAfterUsClose() {
        collect("美股收盘");
    }

    /** 快照保留策略：每天清一次 90 天前的 quote_snapshot（80 只标的约 6400 行/天，不清会无限涨）。 */
    @Scheduled(cron = "0 10 5 * * *", zone = "Asia/Shanghai")
    public void purgeDaily() {
        db.purgeSnapshots(keepDays);
    }

    /**
     * 启动补一次净值快照。日线/评分由 BoardService 的启动预热负责（它会触发落库），
     * 这里只补 nav_daily —— 否则晚上重启后当天就没有真实净值点，曲线上会多一个估算值。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void snapshotOnBoot() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000);   // 让启动预热先把行情拉回来，避免重复打外部接口
                perf.snapshot();
                log.info("[collect] 启动补写当日净值快照完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[collect] 启动补写净值快照失败: {}", e.getMessage());
            }
        }, "collect-boot");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unchecked")
    private void collect(String why) {
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> payload = board.buildBoard(true);
            int n = 0;
            Object groups = payload.get("groups");
            if (groups instanceof Map<?, ?> gm) {
                for (Object g : gm.values()) {
                    if (g instanceof Map<?, ?> gmap && gmap.get("items") instanceof List<?> items) n += items.size();
                }
            }
            perf.snapshot();
            log.info("[collect] {} 采集完成：{} 个标的，用时 {} 秒", why, n,
                    String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0));
        } catch (Exception e) {
            log.warn("[collect] {} 采集失败: {}", why, e.getMessage());
        }
    }
}
