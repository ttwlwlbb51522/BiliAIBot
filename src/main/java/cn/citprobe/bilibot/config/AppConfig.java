package cn.citprobe.bilibot.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置。所有字段均来自 application.properties 或环境变量。
 */
public record AppConfig(Bilibili bilibili, DeepSeek deepseek, Reply reply,
                        Recommendation recommendation, Risk risk, Notify notification, App app) {

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
            String replyFeedUrl,
            boolean replyFeedEnabled,
            boolean allowOtherUsers,
            boolean privateMessageEnabled,
            int privateMessagePollSeconds,
            int privateMessageHistorySize,
            int privateMessageMaxAttempts,
            int privateMessageMaxBackoffSeconds,
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
                    referer, pollIntervalSeconds, requestTimeoutSeconds, atFeedUrl, replyFeedUrl,
                    replyFeedEnabled, allowOtherUsers, privateMessageEnabled,
                    privateMessagePollSeconds, privateMessageHistorySize,
                    privateMessageMaxAttempts, privateMessageMaxBackoffSeconds, requireBotMention,
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
            int maxContextChars,
            String otherUsersMode,
            int samePersonCooldownDays
    ) {
    }

    public record Recommendation(
            boolean enabled,
            int intervalMinutes,
            int maxFolders,
            int samplesPerFolder,
            int relatedLimit,
            int maxResults,
            int minScore,
            List<Long> favoriteFolderIds,
            Path outputFile
    ) {
    }

    public record Risk(
            boolean enabled,
            long minGapMs,
            int readHourly,
            int readDaily,
            int writeHourly,
            int writeDaily,
            int writeGapSeconds,
            int dedupHours,
            Path stateFile
    ) {
    }

    public record Notify(
            String type,
            Path file,
            String webhookUrl,
            String command,
            boolean dailySummaryEnabled,
            int dailySummaryHour
    ) {
    }

    public record App(Path stateFile, Path sessionFile) {
    }
}
