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
        return parse(props);
    }

    static AppConfig parse(Properties props) {
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
        String systemPromptPath = string(props, "reply.system-prompt-file", "prompt/system.txt");
        Path systemPromptFile = Paths.get(systemPromptPath).toAbsolutePath().normalize();
        Path stateFile = Paths.get(string(props, "app.state-file", "data/state.json")).toAbsolutePath().normalize();
        Path sessionFile = Paths.get(string(props, "app.session-file", "data/session.json"))
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
                        string(props, "deepseek.model", "deepseek-chat"),
                        temperature,
                        maxTokens,
                        deepSeekTimeout,
                        maxRetries
                ),
                new AppConfig.Reply(
                        maxLength,
                        bool(props, "reply.mention-owner", true),
                        string(props, "reply.prefix", ""),
                        fallbackText,
                        systemPromptFile,
                        maxContextChars
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

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
