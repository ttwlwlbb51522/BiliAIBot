package cn.citprobe.bilibot.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置。所有字段均来自 application.properties 或环境变量。
 */
public record AppConfig(Bilibili bilibili, DeepSeek deepseek, Reply reply, App app) {

    public record Bilibili(
            String cookie,
            String username,
            String password,
            String loginType,
            long ownerUid,
            String userAgent,
            String referer,
            int pollIntervalSeconds,
            int requestTimeoutSeconds,
            String atFeedUrl,
            boolean requireBotMention,
            boolean processExisting,
            int firstRunGraceSeconds,
            int maxMentionAgeMinutes,
            int threadContextSize,
            boolean dryRun,
            List<String> triggerAliases,
            int maxAttempts
    ) {
        public Bilibili withCookie(String runtimeCookie) {
            return new Bilibili(runtimeCookie, username, password, loginType, ownerUid, userAgent,
                    referer, pollIntervalSeconds, requestTimeoutSeconds, atFeedUrl, requireBotMention,
                    processExisting, firstRunGraceSeconds, maxMentionAgeMinutes, threadContextSize,
                    dryRun, triggerAliases, maxAttempts);
        }
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            int maxTokens,
            int timeoutSeconds,
            int maxRetries
    ) {
    }

    public record Reply(
            int maxLength,
            boolean mentionOwner,
            String prefix,
            String fallbackText,
            Path systemPromptFile,
            int maxContextChars
    ) {
    }

    public record App(Path stateFile, Path sessionFile) {
    }
}
