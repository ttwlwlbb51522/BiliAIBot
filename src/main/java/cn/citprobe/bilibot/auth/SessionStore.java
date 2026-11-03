package cn.citprobe.bilibot.auth;

import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;

/**
 * 账号密码登录成功后的 Cookie 缓存，避免每次启动都重新做人机验证。
 * 文件含登录凭据，默认位于 data/session.json。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private final Path file;
    private final ObjectMapper mapper = Json.STATE;

    public SessionStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Optional<String> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionState state = mapper.readValue(file.toFile(), SessionState.class);
            if (state.cookie() == null || state.cookie().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(state.cookie());
        } catch (IOException e) {
            log.warn("登录会话缓存损坏，将忽略并重新登录: {}", file);
            return Optional.empty();
        }
    }

    public void save(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return;
        }
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SessionState state = new SessionState(cookie, Instant.now().getEpochSecond());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions();
            log.info("已保存登录会话缓存: {}", file);
        } catch (IOException e) {
            log.warn("保存登录会话缓存失败: {}（不影响本次运行）", file, e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删除登录会话缓存失败: {}", file, e);
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("当前文件系统不支持设置会话文件权限: {}", e.getMessage());
        }
    }
}
