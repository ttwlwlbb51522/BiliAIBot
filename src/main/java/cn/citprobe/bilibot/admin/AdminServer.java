package cn.citprobe.bilibot.admin;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.browse.SelfBrowseService;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.config.ConfigException;
import cn.citprobe.bilibot.config.ConfigLoader;
import cn.citprobe.bilibot.draft.Draft;
import cn.citprobe.bilibot.draft.DraftService;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 给 Android 控制台使用的轻量 HTTP 管理接口。
 * 只暴露状态、草稿审核、日志、配置摘要和手动刷视频，不提供修改密钥或停止进程。
 */
public final class AdminServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);
    private static final ObjectMapper MAPPER = Json.STATE;
    private static final int MAX_LOG_LINES = 500;

    private static final Map<String, String> FIELD_DESCRIPTIONS = Map.ofEntries(
            Map.entry("bilibili.private-message-history-size", "每轮读取的私信历史条数"),
            Map.entry("bilibili.private-message-max-backoff-seconds", "私信失败退避上限（秒）"),
            Map.entry("deepseek.base-url", "DeepSeek API 地址"),
            Map.entry("deepseek.model", "DeepSeek 模型名称，例如 deepseek-flash"),
            Map.entry("deepseek.temperature", "回复随机性，越高越发散"),
            Map.entry("deepseek.max-tokens", "单次回复最大 token 数"),
            Map.entry("deepseek.timeout-seconds", "DeepSeek 请求超时（秒）"),
            Map.entry("deepseek.max-retries", "DeepSeek 失败重试次数"),
            Map.entry("live.room-id", "直播间房间号，0 表示未启用"),
            Map.entry("live.poll-seconds", "直播弹幕轮询间隔（秒）"),
            Map.entry("live.only-owner", "是否只回复主人弹幕"),
            Map.entry("video.ffmpeg-path", "ffmpeg 可执行文件路径"),
            Map.entry("video.frame-count", "每次抽取的帧数"),
            Map.entry("video.max-seconds", "最多分析视频前多少秒"),
            Map.entry("video.vision-base-url", "视觉接口地址"),
            Map.entry("video.vision-api-key", "视觉接口 Key"),
            Map.entry("video.vision-model", "视觉模型名称"),
            Map.entry("notify.file", "通知日志文件路径"),
            Map.entry("notify.webhook-url", "Webhook 地址"),
            Map.entry("notify.command", "通知命令"),
            Map.entry("notify.daily-summary-hour", "每日摘要发送小时（0-23）"),
            Map.entry("risk.min-gap-ms", "两次请求之间的最小间隔（毫秒）"),
            Map.entry("risk.read-hourly", "每小时读请求上限"),
            Map.entry("risk.read-daily", "每天读请求上限"),
            Map.entry("risk.write-hourly", "每小时写请求上限"),
            Map.entry("risk.write-daily", "每天写请求上限"),
            Map.entry("risk.write-gap-seconds", "两次写操作冷却（秒）"),
            Map.entry("risk.dedup-hours", "重复内容去重时间（小时）"),
            Map.entry("risk.state-file", "请求保护状态文件"),
            Map.entry("recommend.min-score", "推荐视频最低评分"),
            Map.entry("browse.max-videos", "每次搜索读取的视频数"),
            Map.entry("browse.max-results", "每轮最多分享视频数"),
            Map.entry("browse.min-score", "刷视频分享最低评分"),
            Map.entry("browse.seen-file", "已看视频记录文件"),
            Map.entry("browse.auto-interval-minutes", "自动刷视频间隔（分钟）"),
            Map.entry("admin.bind", "管理接口监听地址"),
            Map.entry("admin.port", "管理接口端口"),
            Map.entry("admin.token", "Android 管理 Token（敏感）"),
            Map.entry("admin.log-file", "管理接口读取的日志文件")
    );

    private final AppConfig config;
    private final BotIdentity bot;
    private final DraftService drafts;
    private final SelfBrowseService browse;
    private final DailyStats stats;
    private final Path configFile;
    private final Object configLock = new Object();
    private final Instant startedAt = Instant.now();
    private final HttpServer server;
    private final ExecutorService executor;

    public AdminServer(AppConfig config, BotIdentity bot, DraftService drafts,
                       SelfBrowseService browse, DailyStats stats, Path configFile) throws IOException {
        if (config.admin().token() == null || config.admin().token().isBlank()) {
            throw new IllegalStateException("admin.enabled=true 时必须设置 admin.token");
        }
        this.config = config;
        this.bot = bot;
        this.drafts = drafts;
        this.browse = browse;
        this.stats = stats;
        this.configFile = configFile.toAbsolutePath().normalize();
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "bili-deepseek-admin");
            thread.setDaemon(true);
            return thread;
        });
        this.server = HttpServer.create(
                new InetSocketAddress(config.admin().bind(), config.admin().port()), 64);
        this.server.setExecutor(executor);
        this.server.createContext("/api/status", this::handleStatus);
        this.server.createContext("/api/drafts", this::handleDrafts);
        this.server.createContext("/api/logs", this::handleLogs);
        this.server.createContext("/api/browse", this::handleBrowse);
        this.server.createContext("/api/config", this::handleConfig);
        this.server.createContext("/api/config/raw", this::handleRawConfig);
        this.server.createContext("/api/config/fields", this::handleConfigFields);
        this.server.createContext("/api/config/set", this::handleSetConfigField);
    }

    public void start() {
        server.start();
        log.info("[管理] Android 控制接口已启动: http://{}:{}/api/status",
                config.admin().bind(), config.admin().port());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("version", packageVersion());
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
        body.put("bot", Map.of("mid", bot.mid(), "name", bot.uname()));
        body.put("ownerUid", config.bilibili().ownerUid());
        body.put("dryRun", config.bilibili().dryRun());
        body.put("model", config.deepseek().model());
        body.put("draftCount", drafts.list().size());
        body.put("autoBrowse", config.browse().autoEnabled());
        body.put("autoBrowseIntervalMinutes", config.browse().autoIntervalMinutes());
        body.put("browseShareMode", config.browse().shareMode());
        body.put("pollSeconds", config.bilibili().pollIntervalSeconds());
        body.put("stats", stats == null ? Map.of() : stats.snapshot());
        sendJson(exchange, 200, body);
    }

    private void handleDrafts(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/drafts") || path.equals("/api/drafts/")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "只支持 GET");
                return;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Draft draft : drafts.list()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", draft.id());
                item.put("authorUid", draft.authorUid());
                item.put("authorName", draft.authorName());
                item.put("title", draft.title());
                item.put("uri", draft.uri());
                item.put("content", draft.content());
                item.put("reply", draft.reply());
                item.put("createdAt", draft.createdAt());
                item.put("sourceRpid", draft.sourceRpid());
                item.put("subjectId", draft.subjectId());
                items.add(item);
            }
            sendJson(exchange, 200, Map.of("ok", true, "drafts", items));
            return;
        }
        String prefix = "/api/drafts/";
        if (!path.startsWith(prefix)) {
            sendError(exchange, 404, "接口不存在");
            return;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) {
            sendError(exchange, 404, "接口不存在");
            return;
        }
        String id = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8);
        String action = rest.substring(slash + 1);
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 POST");
            return;
        }
        boolean ok;
        String message;
        if ("approve".equals(action)) {
            ok = drafts.approve(id);
            message = ok ? "已批准并发送" : "未发送：草稿不存在、dry-run 或发送失败";
        } else if ("drop".equals(action)) {
            ok = drafts.drop(id);
            message = ok ? "已丢弃" : "草稿不存在";
        } else {
            sendError(exchange, 404, "未知操作");
            return;
        }
        sendJson(exchange, ok ? 200 : 409, Map.of("ok", ok, "message", message));
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        int lines = 200;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("lines=")) {
                    try {
                        lines = Math.max(20, Math.min(MAX_LOG_LINES,
                                Integer.parseInt(part.substring("lines=".length()))));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        String text = tail(config.admin().logFile(), lines);
        sendJson(exchange, 200, Map.of("ok", true, "logs", text));
    }

    private void handleBrowse(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 POST");
            return;
        }
        String keyword = "";
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("keyword=")) {
                    keyword = URLDecoder.decode(part.substring("keyword=".length()),
                            StandardCharsets.UTF_8);
                }
            }
        }
        browse.trigger(keyword);
        sendJson(exchange, 200, Map.of("ok", true, "message", "已开始刷视频，请查看日志"));
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("model", config.deepseek().model());
        body.put("dryRun", config.bilibili().dryRun());
        body.put("pollSeconds", config.bilibili().pollIntervalSeconds());
        body.put("replyMode", config.reply().otherUsersMode());
        body.put("browseKeyword", config.browse().keyword());
        body.put("browseShareMode", config.browse().shareMode());
        body.put("browseAutoEnabled", config.browse().autoEnabled());
        body.put("browseAutoIntervalMinutes", config.browse().autoIntervalMinutes());
        body.put("notifyType", config.notification().type());
        sendJson(exchange, 200, body);
    }

    private void handleConfigFields(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 GET");
            return;
        }
        if (!Files.isRegularFile(configFile)) {
            sendError(exchange, 404, "配置文件不存在");
            return;
        }
        Map<String, String> descriptions = templateDescriptions();
        List<Map<String, Object>> fields = new ArrayList<>();
        String text = Files.readString(configFile, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1);
            boolean sensitive = isSensitiveKey(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("description", descriptions.getOrDefault(key,
                    FIELD_DESCRIPTIONS.getOrDefault(key, key)));
            item.put("category", categoryOf(key));
            item.put("sensitive", sensitive);
            item.put("value", sensitive ? (value.isBlank() ? "" : "已设置") : value);
            fields.add(item);
        }
        sendJson(exchange, 200, Map.of("ok", true, "fields", fields));
    }

    private void handleSetConfigField(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 POST");
            return;
        }
        if (!Files.isRegularFile(configFile)) {
            sendError(exchange, 404, "配置文件不存在");
            return;
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        } catch (Exception e) {
            sendError(exchange, 400, "JSON 格式错误");
            return;
        }
        String key = body.path("key").asText("").trim();
        String value = body.path("value").asText("");
        if (key.isEmpty() || key.contains("=") || key.contains("\n") || key.contains("\r")) {
            sendError(exchange, 400, "配置键名无效");
            return;
        }
        if (value.contains("\n") || value.contains("\r")) {
            sendError(exchange, 400, "配置值不能包含换行");
            return;
        }
        Set<String> knownKeys = new LinkedHashSet<>(templateDescriptions().keySet());
        String original = Files.readString(configFile, StandardCharsets.UTF_8);
        for (String line : original.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                knownKeys.add(trimmed.substring(0, eq).trim());
            }
        }
        if (!knownKeys.contains(key)) {
            sendError(exchange, 400, "未知配置项：" + key);
            return;
        }
        if (isSensitiveKey(key) && value.isBlank()) {
            sendError(exchange, 400, "敏感配置项不能设置为空");
            return;
        }
        String updated = replaceProperty(original, key, value);
        try {
            writeConfigText(updated);
            log.info("[管理] Android 修改配置项: {}={}", key, isSensitiveKey(key) ? "***" : value);
            sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "message", "已保存 " + key + "，重启电脑端机器人后生效"
            ));
        } catch (ConfigException e) {
            sendError(exchange, 400, "配置校验失败：" + e.getMessage());
        } catch (IOException e) {
            sendError(exchange, 500, "写入失败：" + e.getMessage());
        }
    }

    private Map<String, String> templateDescriptions() {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream input = AdminServer.class.getResourceAsStream("/config-template.properties")) {
            if (input == null) {
                return result;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            List<String> comments = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    comments.clear();
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    String comment = trimmed.substring(1).trim();
                    if (comment.startsWith("----") || comment.startsWith("====")
                            || comment.contains("以下配置项")) {
                        comments.clear();
                    } else if (!comment.isEmpty()) {
                        comments.add(comment);
                    }
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String key = trimmed.substring(0, eq).trim();
                    if (!comments.isEmpty()) {
                        result.put(key, String.join("；", comments));
                    }
                    comments.clear();
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private static String categoryOf(String key) {
        if (key.startsWith("bilibili.")) {
            return "B站账号";
        }
        if (key.startsWith("deepseek.")) {
            return "DeepSeek";
        }
        if (key.startsWith("reply.")) {
            return "回复与人设";
        }
        if (key.startsWith("risk.")) {
            return "请求保护";
        }
        if (key.startsWith("notify.")) {
            return "通知";
        }
        if (key.startsWith("recommend.")) {
            return "收藏推荐";
        }
        if (key.startsWith("video.")) {
            return "视频画面";
        }
        if (key.startsWith("live.")) {
            return "直播";
        }
        if (key.startsWith("browse.")) {
            return "刷视频";
        }
        if (key.startsWith("admin.")) {
            return "手机管理";
        }
        if (key.startsWith("app.")) {
            return "文件路径";
        }
        return "其他";
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("cookie")
                || lower.contains("api-key") || lower.contains("token");
    }

    private static String replaceProperty(String text, String key, String value) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (lines.isEmpty() || !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            lines.add(key + "=" + value);
        }
        return String.join("\n", lines);
    }

    private void writeConfigText(String text) throws ConfigException, IOException {
        Properties props = new Properties();
        props.load(new StringReader(text));
        ConfigLoader.parse(props, configFile.getParent());
        synchronized (configLock) {
            Path backup = configFile.resolveSibling(configFile.getFileName() + ".bak");
            Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            try {
                if (Files.isRegularFile(configFile)) {
                    Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(temp, text, StandardCharsets.UTF_8);
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configFile);
                    Files.setPosixFilePermissions(temp, permissions);
                } catch (Exception ignored) {
                }
                try {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleRawConfig(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!Files.isRegularFile(configFile)) {
                sendError(exchange, 404, "配置文件不存在：" + configFile);
                return;
            }
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("path", configFile.toString());
            body.put("content", text);
            sendJson(exchange, 200, body);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "只支持 GET 或 POST");
            return;
        }
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length > 2_000_000) {
            sendError(exchange, 413, "配置文件过大");
            return;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            sendError(exchange, 400, "配置内容不能为空");
            return;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(text));
            ConfigLoader.parse(props, configFile.getParent());
        } catch (ConfigException e) {
            sendError(exchange, 400, "配置校验失败：" + e.getMessage());
            return;
        } catch (Exception e) {
            sendError(exchange, 400, "配置格式错误：" + e.getMessage());
            return;
        }
        synchronized (configLock) {
            Path backup = configFile.resolveSibling(configFile.getFileName() + ".bak");
            Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            try {
                if (Files.isRegularFile(configFile)) {
                    Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(temp, text, StandardCharsets.UTF_8);
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configFile);
                    Files.setPosixFilePermissions(temp, permissions);
                } catch (Exception ignored) {
                    // Windows 或不支持 POSIX 权限的文件系统。
                }
                try {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("[管理] 配置文件已通过 Android 更新: {}", configFile);
                sendJson(exchange, 200, Map.of(
                        "ok", true,
                        "message", "配置已保存，重启电脑端机器人后生效",
                        "backup", backup.toString()
                ));
            } catch (IOException e) {
                log.warn("[管理] 写入配置文件失败: {}", e.getMessage());
                sendError(exchange, 500, "写入失败：" + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String supplied = exchange.getRequestHeaders().getFirst("X-Admin-Token");
        if (supplied == null) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                supplied = authorization.substring("Bearer ".length());
            }
        }
        if (supplied != null && constantTimeEquals(config.admin().token(), supplied)) {
            return true;
        }
        sendJson(exchange, 401, Map.of("ok", false, "error", "token 无效"));
        return false;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            diff |= (i < a.length ? a[i] : 0) ^ (i < b.length ? b[i] : 0);
        }
        return diff == 0;
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("ok", false, "error", message));
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String tail(Path file, int lines) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return "日志文件不存在：" + (file == null ? "" : file);
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    private static String packageVersion() {
        String version = AdminServer.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
