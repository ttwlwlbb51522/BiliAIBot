package cn.citprobe.bilibot.notify;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.draft.Draft;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通知分发：stdout、文件、Webhook 或外部命令。
 */
public final class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final AppConfig.Notify config;
    private final DailyStats stats;
    private final HttpClient http;

    public NotifyService(AppConfig.Notify config, DailyStats stats) {
        this.config = config;
        this.stats = stats;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public void send(String text) {
        if (text == null || text.isBlank() || "none".equals(config.type())) {
            return;
        }
        switch (config.type()) {
            case "stdout" -> System.out.println(text);
            case "file" -> appendFile(text);
            case "webhook" -> postWebhook(text);
            case "command" -> runCommand(text);
            default -> log.info("[通知] {}", text);
        }
    }

    public void sendDraft(Draft draft) {
        send("【待审草稿】" + draft.authorName() + "（" + draft.authorUid() + "）\n"
                + "评论：" + draft.content() + "\n"
                + "拟回复：" + draft.reply() + "\n"
                + "批准：drafts approve " + draft.id());
    }

    public void maybeDailySummary() {
        if (!config.dailySummaryEnabled() || stats.summarySentToday()) {
            return;
        }
        if (LocalTime.now().getHour() < config.dailySummaryHour()) {
            return;
        }
        try {
            StringBuilder text = new StringBuilder("B站机器人日报 ")
                    .append(LocalDate.now()).append('\n');
            Map<String, Long> values = stats.snapshot();
            text.append("收到评论：").append(value(values, "commentsReceived")).append('\n');
            text.append("回复评论：").append(value(values, "commentsReplied")).append('\n');
            text.append("新增草稿：").append(value(values, "drafts")).append('\n');
            text.append("私聊回复：").append(value(values, "pmReplied")).append('\n');
            text.append("推荐视频：").append(value(values, "recommendations")).append('\n');
            text.append("错误次数：").append(value(values, "errors"));
            if (stats.hasActivity()) {
                send(text.toString());
                log.info("[通知] 已发送每日摘要");
            }
            stats.markSummarySent();
        } catch (RuntimeException e) {
            log.warn("[通知] 每日摘要发送失败: {}", e.getMessage());
        }
    }

    private static long value(Map<String, Long> values, String key) {
        return values.getOrDefault(key, 0L);
    }

    private void appendFile(String text) {
        try {
            Path file = config.file();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = "[" + LocalDate.now() + " " + LocalTime.now().withNano(0) + "] "
                    + text + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[通知] 写入通知文件失败: {}", config.file(), e);
        }
    }

    private void postWebhook(String text) {
        if (config.webhookUrl() == null || config.webhookUrl().isBlank()) {
            log.warn("[通知] notify.type=webhook 但未配置 notify.webhook-url");
            return;
        }
        try {
            String body = MAPPER.writeValueAsString(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                log.warn("[通知] Webhook 返回 HTTP {}", response.statusCode());
            }
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[通知] Webhook 发送失败: {}", e.getMessage());
        }
    }

    private void runCommand(String text) {
        if (config.command() == null || config.command().isBlank()) {
            log.warn("[通知] notify.type=command 但未配置 notify.command");
            return;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(config.command().trim().split("\\s+"))
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().write((text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("[通知] 外部命令超时");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[通知] 外部命令执行失败: {}", e.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
