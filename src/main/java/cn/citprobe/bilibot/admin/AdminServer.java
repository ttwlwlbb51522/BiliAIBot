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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
