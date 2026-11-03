package cn.citprobe.bilibot.state;

import cn.citprobe.bilibot.config.ConfigException;
import cn.citprobe.bilibot.mention.MentionEvent;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 轻量去重状态。持久化最近处理过的事件/源评论，避免程序重启后重复回复。
 */
public final class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);
    private static final int MAX_ENTRIES = 5000;

    private final Path file;
    private final ObjectMapper mapper = Json.STATE;
    private final LinkedHashSet<Long> processedEventIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> processedSourceIds = new LinkedHashSet<>();
    private boolean initialized;

    public StateStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public synchronized boolean wasHandled(MentionEvent event) {
        return processedEventIds.contains(event.eventId())
                || processedSourceIds.contains(event.sourceRpid());
    }

    public synchronized void initialize(Collection<MentionEvent> skippedEvents) {
        initialized = true;
        for (MentionEvent event : skippedEvents) {
            addEvent(event);
        }
        save();
    }

    public synchronized void initialize() {
        initialized = true;
        save();
    }

    public synchronized void markHandled(MentionEvent event) {
        initialized = true;
        addEvent(event);
        save();
    }

    private void addEvent(MentionEvent event) {
        processedEventIds.add(event.eventId());
        processedSourceIds.add(event.sourceRpid());
        trim(processedEventIds);
        trim(processedSourceIds);
    }

    private static void trim(LinkedHashSet<Long> set) {
        while (set.size() > MAX_ENTRIES) {
            Long first = set.iterator().next();
            set.remove(first);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            BotState state = mapper.readValue(file.toFile(), BotState.class);
            initialized = state.initialized();
            if (state.processedEventIds() != null) {
                processedEventIds.addAll(state.processedEventIds());
            }
            if (state.processedSourceIds() != null) {
                processedSourceIds.addAll(state.processedSourceIds());
            }
            log.info("已加载状态文件 {}，已处理事件 {} 条", file, processedEventIds.size());
        } catch (IOException e) {
            throw new ConfigException("状态文件损坏，无法读取: " + file
                    + "。请备份后删除该文件再启动。", e);
        }
    }

    private void save() {
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BotState snapshot = new BotState(
                    1,
                    initialized,
                    List.copyOf(processedEventIds),
                    List.copyOf(processedSourceIds),
                    Instant.now().toString()
            );
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshot);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ConfigException("保存状态文件失败: " + file, e);
        }
    }

}
