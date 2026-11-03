package cn.citprobe.bilibot;

import cn.citprobe.bilibot.bilibili.BilibiliApiException;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.service.BotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定间隔轮询。所有异常都在任务内部消化，避免 ScheduledExecutorService 因异常停止后续调度。
 */
public final class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    private final AppConfig config;
    private final BotService service;
    private final ScheduledExecutorService scheduler;
    private volatile long lastAuthLogAt;
    private volatile long lastRiskLogAt;

    public BotRunner(AppConfig config, BotService service) {
        this.config = config;
        this.service = service;
        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-poll-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::pollSafely,
                0,
                config.bilibili().pollIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    private void pollSafely() {
        try {
            service.runOnce();
        } catch (BilibiliApiException e) {
            if (e.isAuthError()) {
                if (shouldLog(lastAuthLogAt)) {
                    lastAuthLogAt = System.currentTimeMillis();
                    log.error("B 站登录态失效，请重新获取 Cookie。{}", e.getMessage());
                }
            } else if (e.isRateLimited()) {
                if (shouldLog(lastRiskLogAt)) {
                    lastRiskLogAt = System.currentTimeMillis();
                    log.warn("B 站限流/风控，稍后自动重试。{}", e.getMessage());
                }
            } else {
                log.warn("拉取 B 站 @ 消息失败：{}", e.getMessage());
                log.debug("B 站轮询异常详情", e);
            }
        } catch (Exception e) {
            log.error("本轮轮询发生异常：{}", e.getMessage());
            log.debug("轮询异常详情", e);
        }
    }

    private static boolean shouldLog(long lastAt) {
        return System.currentTimeMillis() - lastAt >= 300_000L;
    }

    public void close() {
        scheduler.shutdownNow();
    }
}
