package cn.citprobe.bilibot.notify;

import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每日运行计数，用于通知摘要。
 */
public final class DailyStats {

    private static final Logger log = LoggerFactory.getLogger(DailyStats.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final Path file;
    private String date;
    private String lastSummaryDate;
    private final Map<String, Long> counts = new LinkedHashMap<>();

    public DailyStats(Path file) {
        this.file = file;
        this.date = LocalDate.now().toString();
        load();
        if (!LocalDate.now().toString().equals(date)) {
            date = LocalDate.now().toString();
            counts.clear();
        }
    }

    public synchronized void increment(String key) {
        rollover();
        counts.merge(key, 1L, Long::sum);
        save();
    }

    public synchronized void add(String key, long value) {
        rollover();
        counts.merge(key, value, Long::sum);
        save();
    }

    public synchronized Map<String, Long> snapshot() {
        rollover();
        return new LinkedHashMap<>(counts);
    }

    public synchronized boolean summarySentToday() {
        return LocalDate.now().toString().equals(lastSummaryDate);
    }

    public synchronized void markSummarySent() {
        lastSummaryDate = LocalDate.now().toString();
        save();
    }

    public synchronized boolean hasActivity() {
        rollover();
        return counts.values().stream().anyMatch(v -> v != null && v > 0);
    }

    private void rollover() {
        String today = LocalDate.now().toString();
        if (!today.equals(date)) {
            date = today;
            counts.clear();
            save();
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            StatsState state = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    StatsState.class);
            if (state != null) {
                date = state.date() == null ? date : state.date();
                lastSummaryDate = state.lastSummaryDate();
                if (state.counts() != null) {
                    counts.putAll(state.counts());
                }
            }
        } catch (IOException e) {
            log.warn("读取每日统计失败: {}", file, e);
        }
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new StatsState(date, new LinkedHashMap<>(counts), lastSummaryDate)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入每日统计失败: {}", file, e);
        }
    }

    public record StatsState(String date, Map<String, Long> counts, String lastSummaryDate) {
    }
}
