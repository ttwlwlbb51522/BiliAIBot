package cn.citprobe.bilibot;

import cn.citprobe.bilibot.bilibili.BilibiliApiException;
import cn.citprobe.bilibot.browse.SelfBrowseService;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.live.LiveDanmakuService;
import cn.citprobe.bilibot.notify.NotifyService;
import cn.citprobe.bilibot.pm.PrivateMessageService;
import cn.citprobe.bilibot.recommend.RecommendationService;
import cn.citprobe.bilibot.service.BotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定间隔轮询。所有异常都在任务内部消化，避免 ScheduledExecutorService 因异常停止后续调度。
 */
public final class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    private final AppConfig config;
    private final BotService service;
    private final RecommendationService recommendationService;
    private final PrivateMessageService privateMessageService;
    private final NotifyService notifyService;
    private final LiveDanmakuService liveService;
    private final SelfBrowseService browseService;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService recommendationScheduler;
    private final ScheduledExecutorService privateMessageScheduler;
    private final ScheduledExecutorService notifyScheduler;
    private final ScheduledExecutorService liveScheduler;
    private final ScheduledExecutorService browseScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastAuthLogAt;
    private volatile long lastRiskLogAt;

    public BotRunner(AppConfig config, BotService service) {
        this(config, service, null, null, null, null, null);
    }

    public BotRunner(AppConfig config, BotService service, RecommendationService recommendationService) {
        this(config, service, recommendationService, null, null, null, null);
    }

    public BotRunner(AppConfig config, BotService service, RecommendationService recommendationService,
                     PrivateMessageService privateMessageService) {
        this(config, service, recommendationService, privateMessageService, null, null, null);
    }

    public BotRunner(AppConfig config, BotService service, RecommendationService recommendationService,
                     PrivateMessageService privateMessageService, NotifyService notifyService) {
        this(config, service, recommendationService, privateMessageService, notifyService, null, null);
    }

    public BotRunner(AppConfig config, BotService service, RecommendationService recommendationService,
                     PrivateMessageService privateMessageService, NotifyService notifyService,
                     LiveDanmakuService liveService) {
        this(config, service, recommendationService, privateMessageService, notifyService, liveService, null);
    }

    public BotRunner(AppConfig config, BotService service, RecommendationService recommendationService,
                     PrivateMessageService privateMessageService, NotifyService notifyService,
                     LiveDanmakuService liveService, SelfBrowseService browseService) {
        this.config = config;
        this.service = service;
        this.recommendationService = recommendationService;
        this.privateMessageService = privateMessageService;
        this.notifyService = notifyService;
        this.liveService = liveService;
        this.browseService = browseService;
        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-poll-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        this.recommendationScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-recommend");
            thread.setDaemon(false);
            return thread;
        });
        this.privateMessageScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-private-message");
            thread.setDaemon(false);
            return thread;
        });
        this.notifyScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-notify");
            thread.setDaemon(false);
            return thread;
        });
        this.liveScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-live");
            thread.setDaemon(false);
            return thread;
        });
        this.browseScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-browse-auto");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::pollSafely,
                0,
                config.bilibili().pollIntervalSeconds(),
                TimeUnit.SECONDS
        );
        if (recommendationService != null && config.recommendation().enabled()) {
            recommendationScheduler.scheduleWithFixedDelay(
                    this::recommendSafely,
                    60,
                    config.recommendation().intervalMinutes(),
                    TimeUnit.MINUTES
            );
        }
        if (privateMessageService != null && config.bilibili().privateMessageEnabled()) {
            schedulePrivateMessage(30);
        }
        if (notifyService != null) {
            notifyScheduler.scheduleWithFixedDelay(
                    this::summarySafely,
                    60,
                    600,
                    TimeUnit.SECONDS
            );
        }
        if (liveService != null && config.live().enabled() && config.live().roomId() > 0) {
            liveScheduler.scheduleWithFixedDelay(
                    this::liveSafely,
                    30,
                    config.live().pollSeconds(),
                    TimeUnit.SECONDS
            );
        }
        if (browseService != null && config.browse().autoEnabled()) {
            log.info("[刷视频] 自动任务已启用：每 {} 分钟一条", config.browse().autoIntervalMinutes());
            browseScheduler.scheduleWithFixedDelay(
                    this::browseSafely,
                    60,
                    config.browse().autoIntervalMinutes(),
                    TimeUnit.MINUTES
            );
        } else if (browseService != null) {
            log.info("[刷视频] 自动任务未启用（browse.auto-enabled=false）");
        }
    }

    public boolean isRunning() {
        return running.get();
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

    private void recommendSafely() {
        try {
            recommendationService.runOnce();
        } catch (Exception e) {
            log.warn("[推荐] 本轮扫描失败: {}", e.getMessage());
            log.debug("推荐扫描异常详情", e);
        }
    }

    private void schedulePrivateMessage(long delaySeconds) {
        if (!running.get() || privateMessageService == null
                || !config.bilibili().privateMessageEnabled()) {
            return;
        }
        privateMessageScheduler.schedule(this::privateMessageSafely,
                Math.max(1, delaySeconds), TimeUnit.SECONDS);
    }

    private void privateMessageSafely() {
        try {
            privateMessageService.runOnce();
        } catch (Exception e) {
            log.warn("[私聊] 本轮拉取失败: {}", e.getMessage());
            log.debug("私聊轮询异常详情", e);
        } finally {
            schedulePrivateMessage(privateMessageService.nextDelaySeconds());
        }
    }

    private void browseSafely() {
        try {
            browseService.triggerAuto();
        } catch (Exception e) {
            log.warn("[刷视频] 自动任务触发失败: {}", e.getMessage());
        }
    }

    private void liveSafely() {
        try {
            liveService.runOnce();
        } catch (Exception e) {
            log.warn("[直播] 弹幕轮询失败: {}", e.getMessage());
            log.debug("直播弹幕异常详情", e);
        }
    }

    private void summarySafely() {
        try {
            notifyService.maybeDailySummary();
        } catch (Exception e) {
            log.warn("[通知] 每日摘要检查失败: {}", e.getMessage());
        }
    }

    private static boolean shouldLog(long lastAt) {
        return System.currentTimeMillis() - lastAt >= 300_000L;
    }

    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdownNow();
        recommendationScheduler.shutdownNow();
        privateMessageScheduler.shutdownNow();
        notifyScheduler.shutdownNow();
        liveScheduler.shutdownNow();
        browseScheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("轮询线程未在 5 秒内停止");
            }
            recommendationScheduler.awaitTermination(5, TimeUnit.SECONDS);
            privateMessageScheduler.awaitTermination(5, TimeUnit.SECONDS);
            notifyScheduler.awaitTermination(5, TimeUnit.SECONDS);
            liveScheduler.awaitTermination(5, TimeUnit.SECONDS);
            browseScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("轮询已停止");
    }
}
