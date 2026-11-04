package cn.citprobe.bilibot.pm;

import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 私信消息去重状态，避免重启后重复回复同一条私信。
 */
final class PrivateMessageStore {

    private static final Logger log = LoggerFactory.getLogger(PrivateMessageStore.class);
    private static final int MAX_SEEN = 10000;
    private static final ObjectMapper MAPPER = Json.STATE;

    private final Path file;
    private final Set<String> seen = new LinkedHashSet<>();

    PrivateMessageStore(Path file) {
        this.file = file;
        load();
    }

    synchronized boolean isSeen(String key) {
        return key != null && seen.contains(key);
    }

    synchronized void markSeen(String key) {
        if (key != null && !key.isBlank()) {
            seen.add(key);
        }
        while (seen.size() > MAX_SEEN) {
            seen.remove(seen.iterator().next());
        }
    }

    synchronized void save() {
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new StoredState(new ArrayList<>(seen))),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入私信状态文件失败: {}", file, e);
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            StoredState state = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    StoredState.class);
            if (state != null && state.seen() != null) {
                for (String key : state.seen()) {
                    if (key != null && !key.isBlank()) {
                        seen.add(key);
                    }
                }
            }
            log.info("已加载私信去重记录: {} 条", seen.size());
        } catch (IOException e) {
            log.warn("读取私信状态文件失败，将重新记录: {}", file, e);
        }
    }

    record StoredState(List<String> seen) {
    }
}
