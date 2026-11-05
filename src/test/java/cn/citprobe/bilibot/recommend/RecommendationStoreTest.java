package cn.citprobe.bilibot.recommend;

import cn.citprobe.bilibot.recommend.RecommendationModels.Recommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsSeenRecommendations() throws Exception {
        Path file = tempDir.resolve("recommendations.json");
        RecommendationStore first = new RecommendationStore(file);
        Recommendation item = new Recommendation(
                1L, "BV1xx", "标题", "UP", "https://www.bilibili.com/video/BV1xx",
                88, "有趣", "收藏样本", "内容"
        );

        first.save("2026-11-04T00:00:00Z", List.of(item));

        RecommendationStore second = new RecommendationStore(file);
        assertTrue(second.isSeen("BV1xx"));
        String json = Files.readString(file);
        assertTrue(json.contains("BV1xx"));
        assertTrue(json.contains("recommendations"));
    }
}
