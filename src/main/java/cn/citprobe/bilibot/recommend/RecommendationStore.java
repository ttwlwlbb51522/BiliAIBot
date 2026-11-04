package cn.citprobe.bilibot.recommend;

import cn.citprobe.bilibot.recommend.RecommendationModels.Recommendation;
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
 * 推荐结果与已推荐视频的去重存储。
 */
final class RecommendationStore {

    private static final Logger log = LoggerFactory.getLogger(RecommendationStore.class);
    private static final int MAX_SEEN = 5000;
    private static final ObjectMapper MAPPER = Json.STATE;

    private final Path file;
    private final Set<String> seen = new LinkedHashSet<>();

    RecommendationStore(Path file) {
        this.file = file;
        load();
    }

    synchronized boolean isSeen(String bvid) {
        return seen.contains(bvid);
    }

    synchronized void save(String generatedAt, List<Recommendation> recommendations) {
        for (Recommendation recommendation : recommendations) {
            seen.add(recommendation.bvid());
        }
        while (seen.size() > MAX_SEEN) {
            String oldest = seen.iterator().next();
            seen.remove(oldest);
        }
        StoredState state = new StoredState(generatedAt, new ArrayList<>(seen), recommendations);
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入推荐文件失败: {}", file, e);
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
                for (String bvid : state.seen()) {
                    if (bvid != null && !bvid.isBlank()) {
                        seen.add(bvid);
                    }
                }
            }
            log.info("已加载推荐历史: {} 条", seen.size());
        } catch (IOException e) {
            log.warn("读取推荐文件失败，将重新开始记录: {}", file, e);
        }
    }

    record StoredState(String generatedAt, List<String> seen, List<Recommendation> recommendations) {
    }
}
