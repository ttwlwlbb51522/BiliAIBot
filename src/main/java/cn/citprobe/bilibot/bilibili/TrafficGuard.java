package cn.citprobe.bilibot.bilibili;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 全局请求保护：最小间隔、读写额度、写冷却、重复写指纹和 412/429 暂停。
 * 状态保存在普通 JSON 文件中，进程意外退出后仍会保留暂停/额度。
 */
public final class TrafficGuard {

    private static final Logger log = LoggerFactory.getLogger(TrafficGuard.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final AppConfig.Risk config;
    private final Path stateFile;
    private State state;

    public TrafficGuard(AppConfig.Risk config) {
        this.config = config;
        this.stateFile = config.stateFile();
        this.state = load();
    }

    public synchronized void beforeRead() {
        if (!config.enabled()) {
            return;
        }
        checkPause();
        waitGap(0);
        resetWindows();
        if (state.readHour >= config.readHourly() || state.readDay >= config.readDaily()) {
            throw new BilibiliApiException(-429, 429,
                    "读取额度已用完（小时 " + state.readHour + "/" + config.readHourly()
                            + "，天 " + state.readDay + "/" + config.readDaily() + "）");
        }
        state.readHour++;
        state.readDay++;
        state.lastRequestAt = now();
        save();
    }

    public synchronized void beforeWrite(String fingerprint) {
        if (!config.enabled()) {
            return;
        }
        checkPause();
        waitGap(config.writeGapSeconds() * 1000L);
        resetWindows();
        if (state.writeHour >= config.writeHourly() || state.writeDay >= config.writeDaily()) {
            throw new BilibiliApiException(-429, 429,
                    "写入额度已用完（小时 " + state.writeHour + "/" + config.writeHourly()
                            + "，天 " + state.writeDay + "/" + config.writeDaily() + "）");
        }
        pruneFingerprints();
        if (fingerprint != null && !fingerprint.isBlank()
                && state.fingerprints.containsKey(fingerprint)) {
            throw new BilibiliApiException(-429, 429,
                    "重复写操作已被请求保护拦截：" + fingerprint);
        }
        state.writeHour++;
        state.writeDay++;
        state.lastWriteAt = now();
        state.lastRequestAt = now();
        if (fingerprint != null && !fingerprint.isBlank()) {
            state.fingerprints.put(fingerprint, now());
        }
        save();
    }

    public synchronized void afterResponse(int status, long retryAfterSeconds) {
        if (!config.enabled()) {
            return;
        }
        if (status == 412 || status == 429) {
            state.pauseLevel = Math.min(4, state.pauseLevel + 1);
            long minutes = retryAfterSeconds > 0
                    ? Math.min(3600, retryAfterSeconds / 60)
                    : Math.min(30, 5L * (1L << (state.pauseLevel - 1)));
            state.pauseUntil = now() + minutes * 60_000L;
            state.pauseReason = "HTTP " + status;
            save();
            log.warn("[请求保护] 检测到 HTTP {}，暂停 {} 分钟", status, minutes);
        } else if (status < 400) {
            if (state.pauseLevel > 0) {
                state.pauseLevel = 0;
                save();
            }
        }
    }

    public synchronized boolean paused() {
        return state.pauseUntil > now();
    }

    public synchronized long pauseUntilEpochMillis() {
        return state.pauseUntil;
    }

    private void checkPause() {
        long now = now();
        if (state.pauseUntil > now) {
            long seconds = Math.max(1, (state.pauseUntil - now) / 1000);
            throw new BilibiliApiException(-429, 429,
                    "请求保护暂停中（" + state.pauseReason + "），还需 " + seconds + " 秒");
        }
    }

    private void waitGap(long extraGapMs) {
        long gap = Math.max(config.minGapMs(), extraGapMs);
        long wait = state.lastRequestAt + gap - now();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BilibiliApiException(-1, 0, "请求保护等待被中断", e);
            }
        }
    }

    private void resetWindows() {
        long now = now();
        if (now - state.hourStart >= 3_600_000L) {
            state.hourStart = now;
            state.readHour = 0;
            state.writeHour = 0;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate stateDay = Instant.ofEpochMilli(state.dayStart).atZone(zone).toLocalDate();
        if (!stateDay.equals(today)) {
            state.dayStart = now;
            state.readDay = 0;
            state.writeDay = 0;
        }
    }

    private void pruneFingerprints() {
        if (config.dedupHours() <= 0) {
            state.fingerprints.clear();
            return;
        }
        long cutoff = now() - config.dedupHours() * 3_600_000L;
        Iterator<Map.Entry<String, Long>> it = state.fingerprints.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }

    private State load() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return new State();
        }
        try {
            State loaded = MAPPER.readValue(Files.readString(stateFile, StandardCharsets.UTF_8),
                    State.class);
            if (loaded != null) {
                return loaded;
            }
        } catch (IOException e) {
            // 状态损坏时拒绝请求，避免静默重置后放大请求量。
            log.error("[请求保护] 状态文件损坏，拒绝自动重置: {}", stateFile, e);
            State broken = new State();
            broken.pauseUntil = now() + 3_600_000L;
            broken.pauseReason = "状态文件损坏，人工检查";
            return broken;
        }
        return new State();
    }

    private void save() {
        if (stateFile == null) {
            return;
        }
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[请求保护] 写入状态文件失败: {}", stateFile, e);
        }
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    public static final class State {
        public long hourStart = now();
        public long dayStart = now();
        public int readHour;
        public int readDay;
        public int writeHour;
        public int writeDay;
        public long lastRequestAt;
        public long lastWriteAt;
        public long pauseUntil;
        public int pauseLevel;
        public String pauseReason = "";
        public Map<String, Long> fingerprints = new HashMap<>();
    }
}
