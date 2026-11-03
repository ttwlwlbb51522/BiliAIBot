package cn.citprobe.bilibot.deepseek;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek Chat Completions 客户端，带有限次数的指数退避重试。
 */
public final class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final AppConfig.DeepSeek config;
    private final HttpClient http;
    private final ObjectMapper mapper = Json.DEEPSEEK;
    private final URI endpoint;

    public DeepSeekClient(AppConfig.DeepSeek config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(config.timeoutSeconds(), 20)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.endpoint = URI.create(buildEndpoint(config.baseUrl()));
    }

    public String chat(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        int totalAttempts = config.maxRetries() + 1;
        DeepSeekException lastError = null;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            try {
                return callOnce(messages);
            } catch (DeepSeekException e) {
                lastError = e;
                if (!e.isRetryable() || attempt >= totalAttempts - 1) {
                    throw e;
                }
                long delayMs = Math.min(8_000L, 1_000L * (1L << attempt));
                log.warn("DeepSeek 请求失败（{}），{}ms 后重试（{}/{}）：{}",
                        e.httpStatus(), delayMs, attempt + 1, config.maxRetries(), e.getMessage());
                sleep(delayMs);
            }
        }
        throw lastError == null ? new DeepSeekException("DeepSeek 请求失败", 0, false) : lastError;
    }

    private String callOnce(List<ChatMessage> messages) {
        ChatRequest payload = new ChatRequest(
                config.model(),
                messages,
                config.temperature(),
                config.maxTokens(),
                false
        );

        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new DeepSeekException("序列化 DeepSeek 请求失败", 0, false, e);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new DeepSeekException("DeepSeek 请求超时", 0, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeepSeekException("DeepSeek 请求被中断", 0, false, e);
        } catch (IOException e) {
            throw new DeepSeekException("访问 DeepSeek 失败: " + e.getMessage(), 0, true, e);
        }

        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new DeepSeekException("DeepSeek 返回空响应（HTTP " + status + "）", status, status >= 500);
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new DeepSeekException("DeepSeek 返回非 JSON 内容（HTTP " + status + "）", status,
                    status == 429 || status >= 500, e);
        }

        if (status >= 400) {
            String message = root.path("error").path("message").asText("");
            if (message.isBlank()) {
                message = root.path("message").asText("未知错误");
            }
            boolean retryable = status == 408 || status == 429 || status >= 500;
            throw new DeepSeekException("DeepSeek HTTP " + status + ": " + message, status, retryable);
        }

        if (root.has("error") && !root.path("error").isNull()) {
            String message = root.path("error").path("message").asText("未知错误");
            throw new DeepSeekException("DeepSeek 业务错误: " + message, status, false);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new DeepSeekException("DeepSeek 响应中没有 choices", status, true);
        }
        JsonNode messageNode = choices.get(0).path("message");
        String content = messageNode.path("content").asText("");
        if (content.isBlank()) {
            throw new DeepSeekException("DeepSeek 返回了空内容", status, true);
        }

        String finishReason = choices.get(0).path("finish_reason").asText("");
        if ("length".equals(finishReason)) {
            log.warn("DeepSeek 回复因 max_tokens 被截断，当前 max-tokens={}", config.maxTokens());
        }
        return content.trim();
    }

    private static String buildEndpoint(String baseUrl) {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeepSeekException("重试等待被中断", 0, false, e);
        }
    }

    private record ChatRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            int maxTokens,
            boolean stream
    ) {
    }
}
