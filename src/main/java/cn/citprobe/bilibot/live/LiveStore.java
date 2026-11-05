package cn.citprobe.bilibot.live;

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
 * 直播弹幕去重状态。
 */
final class LiveStore {

    private static final Logger log = LoggerFactory.getLogger(LiveStore.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final Path file;
    private final Set<String> seen = new LinkedHashSet<>();

    LiveStore(Path file) {
        this.file = file;
        load();
    }

    synchronized boolean isSeen(String key) {
        return seen.contains(key);
    }

    synchronized void markSeen(String key) {
        seen.add(key);
        while (seen.size() > 5000) {
            seen.remove(seen.iterator().next());
        }
    }

    synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new State(new ArrayList<>(seen))),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入直播弹幕状态失败: {}", file, e);
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            State state = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (state != null && state.seen() != null) {
                seen.addAll(state.seen());
            }
        } catch (IOException e) {
            log.warn("读取直播弹幕状态失败: {}", file, e);
        }
    }

    public record State(List<String> seen) {
    }
}
