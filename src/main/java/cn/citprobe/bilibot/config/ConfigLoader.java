package cn.citprobe.bilibot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 加载配置：内置模板默认值 -> 自动生成/补全后的 application.properties -> 环境变量。
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {
    }

    /**
     * 解析配置文件路径：显式指定 > jar 同级目录 > 当前工作目录。
     */
    public static Path resolvePath(Path requested) {
        return ConfigFileManager.resolvePath(requested);
    }

    public static AppConfig load(Path requestedFile) {
        return loadResolved(resolvePath(requestedFile));
    }

    /**
     * 加载已确定路径的配置。首次运行会生成模板；已有文件会自动补齐缺失键和空值。
     */
    public static AppConfig loadResolved(Path file) {
        Path path = file.toAbsolutePath().normalize();
        ConfigFileManager.prepare(path);

        Properties props = new Properties();
        props.putAll(ConfigFileManager.templateDefaults());
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            props.load(new StringReader(content));
            log.info("已加载配置文件: {}", path);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("读取配置文件失败: " + path + "（" + e.getMessage() + "）", e);
        }

        applyEnvironmentOverrides(props);
        return parse(props, path.getParent());
    }

    static AppConfig parse(Properties props) {
        return parse(props, Paths.get("").toAbsolutePath());
    }

    static AppConfig parse(Properties props, Path baseDir) {
        String cookie = sanitizeCookie(string(props, "bilibili.cookie", ""));
        String username = string(props, "bilibili.username", "");
        String password = rawString(props, "bilibili.password", "");
        String loginType = string(props, "bilibili.login-type", "auto").toLowerCase(Locale.ROOT);
        boolean hasUsername = !username.isBlank();
        boolean hasPassword = !password.isBlank();

        if (!List.of("auto", "cookie", "password", "qrcode").contains(loginType)) {
            throw new ConfigException("bilibili.login-type 只能是 auto、cookie、password 或 qrcode");
        }
        if (hasUsername != hasPassword) {
            throw new ConfigException("bilibili.username 和 bilibili.password 必须同时填写");
        }
        if ("password".equals(loginType) && !hasUsername) {
            throw new ConfigException("bilibili.login-type=password 时必须配置 bilibili.username 和 bilibili.password");
        }
        if ("cookie".equals(loginType) && cookie.isBlank()) {
            throw new ConfigException("bilibili.login-type=cookie 时必须配置 bilibili.cookie");
        }
        if (!cookie.isBlank() && (!cookie.contains("SESSDATA=") || !cookie.contains("bili_jct="))) {
            throw new ConfigException("bilibili.cookie 必须包含 SESSDATA 和 bili_jct（建议复制浏览器中的完整 Cookie）");
        }

        long ownerUid = positiveLong(props, "bilibili.owner-uid", "主人账号 UID");
        if (ownerUid <= 0) {
            throw new ConfigException("bilibili.owner-uid 必须填写主人账号的 UID");
        }

        String apiKey = required(props, "deepseek.api-key", "DeepSeek API Key");
        String baseUrl = trimTrailingSlash(string(props, "deepseek.base-url", "https://api.deepseek.com"));
        validateHttpUrl(baseUrl, "deepseek.base-url");

        String atFeedUrl = string(props, "bilibili.at-feed-url", "https://api.bilibili.com/x/msgfeed/at");
        validateHttpUrl(atFeedUrl, "bilibili.at-feed-url");
        String replyFeedUrl = string(props, "bilibili.reply-feed-url",
                "https://api.bilibili.com/x/msgfeed/reply");
        validateHttpUrl(replyFeedUrl, "bilibili.reply-feed-url");

        int pollInterval = intInRange(props, "bilibili.poll-interval-seconds", 45, 10, 3600);
        int requestTimeout = intInRange(props, "bilibili.request-timeout-seconds", 20, 3, 300);
        int firstRunGrace = intInRange(props, "bilibili.first-run-grace-seconds", 120, 0, 3600);
        int maxAgeMinutes = intInRange(props, "bilibili.max-mention-age-minutes", 120, 1, 10080);
        int contextSize = intInRange(props, "bilibili.thread-context-size", 12, 0, 50);
        int maxAttempts = intInRange(props, "bilibili.max-attempts", 3, 1, 10);

        int maxLength = intInRange(props, "reply.max-length", 900, 50, 1000);
        int maxContextChars = intInRange(props, "reply.max-context-chars", 3000, 0, 20000);
        String fallbackText = string(props, "reply.fallback-text", "嗯嗯，我在听，你继续说～");
        if (fallbackText.isBlank()) {
            throw new ConfigException("reply.fallback-text 不能为空");
        }
        int maxTokens = intInRange(props, "deepseek.max-tokens", 800, 64, 8192);
        int deepSeekTimeout = intInRange(props, "deepseek.timeout-seconds", 90, 5, 600);
        int maxRetries = intInRange(props, "deepseek.max-retries", 2, 0, 5);
        double temperature = doubleInRange(props, "deepseek.temperature", 0.8, 0.0, 2.0);

        List<String> aliases = parseAliases(string(props, "bilibili.trigger-aliases", ""));
        String systemPromptPath = string(props, "reply.system-prompt-file", "persona.txt");
        Path systemPromptFile = resolveRelative(baseDir, systemPromptPath);
        Path stateFile = Paths.get(string(props, "app.state-file", "data/state.json")).toAbsolutePath().normalize();
        Path sessionFile = Paths.get(string(props, "app.session-file", "data/session.json"))
                .toAbsolutePath().normalize();
        List<Long> favoriteFolderIds = parseLongList(
                string(props, "recommend.favorite-folders", ""));
        Path recommendationFile = Paths.get(
                string(props, "recommend.output-file", "data/recommendations.json"))
                .toAbsolutePath().normalize();

        AppConfig config = new AppConfig(
                new AppConfig.Bilibili(
                        cookie,
                        username,
                        password,
                        loginType,
                        ownerUid,
                        string(props, "bilibili.user-agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
                        string(props, "bilibili.referer", "https://www.bilibili.com/"),
                        pollInterval,
                        requestTimeout,
                        atFeedUrl,
                        replyFeedUrl,
                        bool(props, "bilibili.reply-feed-enabled", true),
                        bool(props, "bilibili.allow-other-users", false),
                        bool(props, "bilibili.private-message-enabled", false),
                        intInRange(props, "bilibili.private-message-poll-seconds", 60, 10, 3600),
                        intInRange(props, "bilibili.private-message-history-size", 8, 1, 30),
                        intInRange(props, "bilibili.private-message-max-attempts", 3, 1, 10),
                        intInRange(props, "bilibili.private-message-max-backoff-seconds", 900, 60, 3600),
                        bool(props, "bilibili.require-bot-mention", true),
                        bool(props, "bilibili.process-existing", false),
                        firstRunGrace,
                        maxAgeMinutes,
                        contextSize,
                        bool(props, "bilibili.dry-run", true),
                        aliases,
                        maxAttempts
                ),
                new AppConfig.DeepSeek(
                        apiKey,
                        baseUrl,
                        string(props, "deepseek.model", "deepseek-flash"),
                        temperature,
                        maxTokens,
                        deepSeekTimeout,
                        maxRetries
                ),
                new AppConfig.Reply(
                        maxLength,
                        bool(props, "reply.mention-owner", false),
                        string(props, "reply.prefix", ""),
                        fallbackText,
                        systemPromptFile,
                        maxContextChars,
                        otherUsersMode(props),
                        intInRange(props, "reply.same-person-cooldown-days", 0, 0, 3650)
                ),
                new AppConfig.Recommendation(
                        bool(props, "recommend.enabled", false),
                        intInRange(props, "recommend.interval-minutes", 720, 10, 10080),
                        intInRange(props, "recommend.max-folders", 5, 1, 50),
                        intInRange(props, "recommend.samples-per-folder", 5, 1, 20),
                        intInRange(props, "recommend.related-limit", 10, 1, 30),
                        intInRange(props, "recommend.max-results", 3, 1, 10),
                        intInRange(props, "recommend.min-score", 70, 0, 100),
                        favoriteFolderIds,
                        recommendationFile,
                        bool(props, "recommend.video-insight-enabled", false)
                ),
                new AppConfig.Risk(
                        bool(props, "risk.enabled", true),
                        intInRange(props, "risk.min-gap-ms", 1500, 200, 10000),
                        intInRange(props, "risk.read-hourly", 600, 10, 100000),
                        intInRange(props, "risk.read-daily", 3000, 10, 1000000),
                        intInRange(props, "risk.write-hourly", 12, 1, 10000),
                        intInRange(props, "risk.write-daily", 30, 1, 100000),
                        intInRange(props, "risk.write-gap-seconds", 120, 0, 3600),
                        intInRange(props, "risk.dedup-hours", 24, 0, 720),
                        Paths.get(string(props, "risk.state-file", "data/traffic.json"))
                                .toAbsolutePath().normalize()
                ),
                new AppConfig.Notify(
                        notifyType(props),
                        Paths.get(string(props, "notify.file", "data/notifications.log"))
                                .toAbsolutePath().normalize(),
                        string(props, "notify.webhook-url", ""),
                        string(props, "notify.command", ""),
                        bool(props, "notify.daily-summary-enabled", true),
                        intInRange(props, "notify.daily-summary-hour", 23, 0, 23)
                ),
                new AppConfig.Video(
                        bool(props, "video.frames-enabled", false),
                        string(props, "video.ffmpeg-path", "ffmpeg"),
                        intInRange(props, "video.frame-count", 6, 1, 12),
                        intInRange(props, "video.max-seconds", 30, 5, 300),
                        string(props, "video.vision-base-url", ""),
                        string(props, "video.vision-api-key", ""),
                        string(props, "video.vision-model", "")
                ),
                new AppConfig.Live(
                        bool(props, "live.enabled", false),
                        positiveLongOrZero(props, "live.room-id"),
                        intInRange(props, "live.poll-seconds", 30, 10, 600),
                        bool(props, "live.only-owner", true)
                ),
                new AppConfig.Browse(
                        string(props, "browse.keyword", "蓝色大肥鱼"),
                        intInRange(props, "browse.max-videos", 5, 1, 20),
                        intInRange(props, "browse.max-results", 2, 1, 5),
                        intInRange(props, "browse.min-score", 70, 0, 100),
                        browseMode(props),
                        Paths.get(string(props, "browse.seen-file", "data/browse-seen.json"))
                                .toAbsolutePath().normalize(),
                        bool(props, "browse.auto-enabled", true),
                        intInRange(props, "browse.auto-interval-minutes", 5, 1, 1440)
                ),
                new AppConfig.App(stateFile, sessionFile)
        );

        validateSafety(config);
        return config;
    }

    private static void validateSafety(AppConfig config) {
        if (config.deepseek().apiKey().isBlank()) {
            throw new ConfigException("deepseek.api-key 不能为空");
        }
        if (config.bilibili().userAgent().contains("\n") || config.bilibili().userAgent().contains("\r")) {
            throw new ConfigException("bilibili.user-agent 不能包含换行符");
        }
        if (config.bilibili().ownerUid() <= 0) {
            throw new ConfigException("bilibili.owner-uid 必须大于 0");
        }
    }

    private static void applyEnvironmentOverrides(Properties props) {
        override(props, "BILI_COOKIE", "bilibili.cookie");
        override(props, "BILI_USERNAME", "bilibili.username");
        override(props, "BILI_PASSWORD", "bilibili.password");
        override(props, "BILI_LOGIN_TYPE", "bilibili.login-type");
        override(props, "BILI_OWNER_UID", "bilibili.owner-uid");
        override(props, "BILI_POLL_INTERVAL_SECONDS", "bilibili.poll-interval-seconds");
        override(props, "BILI_DRY_RUN", "bilibili.dry-run");
        override(props, "BILI_PROCESS_EXISTING", "bilibili.process-existing");
        override(props, "BILI_TRIGGER_ALIASES", "bilibili.trigger-aliases");
        override(props, "DEEPSEEK_API_KEY", "deepseek.api-key");
        override(props, "DEEPSEEK_BASE_URL", "deepseek.base-url");
        override(props, "DEEPSEEK_MODEL", "deepseek.model");
        override(props, "APP_STATE_FILE", "app.state-file");
        override(props, "APP_SESSION_FILE", "app.session-file");
        override(props, "BOT_SYSTEM_PROMPT_FILE", "reply.system-prompt-file");
    }

    private static void override(Properties props, String envName, String key) {
        String value = System.getenv(envName);
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private static String sanitizeCookie(String raw) {
        String cookie = raw.trim();
        if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) {
            cookie = cookie.substring(7).trim();
        }
        if (cookie.contains("\r") || cookie.contains("\n")) {
            throw new ConfigException("bilibili.cookie 不能包含换行符");
        }
        return cookie;
    }

    private static void validateHttpUrl(String value, String key) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new ConfigException(key + " 不是合法的 HTTP(S) 地址: " + value);
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key + " 不是合法的 URL: " + value, e);
        }
    }

    private static String required(Properties props, String key, String label) {
        String value = string(props, key, "");
        if (value.isBlank()) {
            throw new ConfigException("缺少配置 " + key + "（" + label + "）。"
                    + "请编辑 application.properties 或设置对应环境变量。");
        }
        return value;
    }

    private static String string(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }

    private static String rawString(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private static int intInRange(Properties props, String key, int defaultValue, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new ConfigException(key + " 必须在 " + min + " 到 " + max + " 之间，当前为 " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " 不是合法整数: " + raw, e);
        }
    }

    private static long positiveLong(Properties props, String key, String label) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            throw new ConfigException("缺少配置 " + key + "（" + label + "）");
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new ConfigException(key + " 必须大于 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " 不是合法 UID: " + raw, e);
        }
    }

    private static double doubleInRange(Properties props, String key, double defaultValue, double min, double max) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < min || value > max) {
                throw new ConfigException(key + " 必须在 " + min + " 到 " + max + " 之间");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " 不是合法数字: " + raw, e);
        }
    }

    private static boolean bool(Properties props, String key, boolean defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw new ConfigException(key + " 只能是 true 或 false，当前为 " + raw);
    }

    private static List<String> parseAliases(String raw) {
        List<String> aliases = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty() && !aliases.contains(value)) {
                aliases.add(value);
            }
        }
        return List.copyOf(aliases);
    }

    private static long positiveLongOrZero(Properties props, String key) {
        String value = string(props, key, "0");
        try {
            long parsed = Long.parseLong(value);
            return Math.max(0, parsed);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " 必须是数字");
        }
    }

    private static String browseMode(Properties props) {
        String value = string(props, "browse.share-mode", "dm").toLowerCase(Locale.ROOT);
        if (!value.equals("dm") && !value.equals("comment") && !value.equals("none")) {
            throw new ConfigException("browse.share-mode 只能是 dm、comment 或 none");
        }
        return value;
    }

    private static String notifyType(Properties props) {
        String value = string(props, "notify.type", "none").toLowerCase(Locale.ROOT);
        if (!List.of("none", "stdout", "file", "webhook", "command").contains(value)) {
            throw new ConfigException("notify.type 只能是 none、stdout、file、webhook 或 command");
        }
        return value;
    }

    private static String otherUsersMode(Properties props) {
        String value = string(props, "reply.other-users-mode", "direct")
                .toLowerCase(Locale.ROOT);
        if (!value.equals("direct") && !value.equals("draft")) {
            throw new ConfigException("reply.other-users-mode 只能是 direct 或 draft");
        }
        return value;
    }

    private static List<Long> parseLongList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(text);
                if (id > 0) {
                    result.add(id);
                }
            } catch (NumberFormatException e) {
                throw new ConfigException("recommend.favorite-folders 只能填写数字收藏夹 ID");
            }
        }
        return List.copyOf(result);
    }

    private static Path resolveRelative(Path baseDir, String value) {
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        Path base = baseDir == null ? Paths.get("").toAbsolutePath() : baseDir;
        return base.resolve(path).toAbsolutePath().normalize();
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
