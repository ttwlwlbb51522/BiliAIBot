package cn.citprobe.bilibot.recommend;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.recommend.RecommendationModels.FavoriteFolder;
import cn.citprobe.bilibot.recommend.RecommendationModels.FavoriteVideo;
import cn.citprobe.bilibot.recommend.RecommendationModels.Recommendation;
import cn.citprobe.bilibot.recommend.RecommendationModels.RelatedVideo;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描主人公开收藏夹，找相关视频，用 DeepSeek 评分后写入推荐文件。
 * 当前只写日志和 JSON，不发送私聊。
 */
public final class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final ObjectMapper MAPPER = Json.STATE;
    private static final int MAX_SEEDS = 12;
    private static final int MAX_CANDIDATES = 40;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DeepSeekClient deepSeek;
    private final RecommendationStore store;

    public RecommendationService(AppConfig config, BilibiliClient bilibili, DeepSeekClient deepSeek) {
        this.config = config;
        this.bilibili = bilibili;
        this.deepSeek = deepSeek;
        this.store = new RecommendationStore(config.recommendation().outputFile());
    }

    public void runOnce() {
        AppConfig.Recommendation recommendation = config.recommendation();
        if (!recommendation.enabled()) {
            return;
        }
        log.info("[推荐] 开始扫描主人公开收藏夹...");
        List<FavoriteFolder> folders = loadFolders(recommendation);
        if (folders.isEmpty()) {
            log.info("[推荐] 没有可扫描的公开收藏夹");
            return;
        }

        List<FavoriteVideo> seeds = new ArrayList<>();
        Set<Long> favoriteAids = new HashSet<>();
        for (FavoriteFolder folder : folders) {
            try {
                List<FavoriteVideo> videos = bilibili.fetchFavoriteVideos(
                        folder.id(), recommendation.samplesPerFolder());
                for (FavoriteVideo video : videos) {
                    favoriteAids.add(video.aid());
                    seeds.add(video);
                }
                log.info("[推荐] 收藏夹「{}」读取 {} 个样本", folder.title(), videos.size());
            } catch (RuntimeException e) {
                log.warn("[推荐] 读取收藏夹「{}」失败: {}", folder.title(), e.getMessage());
            }
        }
        if (seeds.isEmpty()) {
            log.info("[推荐] 收藏夹没有视频样本");
            return;
        }
        if (seeds.size() > MAX_SEEDS) {
            seeds = new ArrayList<>(seeds.subList(0, MAX_SEEDS));
        }

        Map<String, RelatedVideo> candidates = new LinkedHashMap<>();
        for (FavoriteVideo seed : seeds) {
            try {
                for (RelatedVideo related : bilibili.fetchRelatedVideos(
                        seed.aid(), recommendation.relatedLimit(), seed.title())) {
                    if (favoriteAids.contains(related.aid())
                            || store.isSeen(related.bvid())
                            || candidates.containsKey(related.bvid())) {
                        continue;
                    }
                    candidates.put(related.bvid(), related);
                    if (candidates.size() >= MAX_CANDIDATES) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[推荐] 读取视频 {} 的相关推荐失败: {}", seed.bvid(), e.getMessage());
            }
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            log.info("[推荐] 没有新的相关视频");
            return;
        }

        List<Scored> scored = score(seeds, new ArrayList<>(candidates.values()));
        List<Recommendation> recommendations = new ArrayList<>();
        for (Scored item : scored) {
            if (recommendations.size() >= recommendation.maxResults()) {
                break;
            }
            if (item.score() < recommendation.minScore()) {
                continue;
            }
            RelatedVideo video = item.video();
            recommendations.add(new Recommendation(
                    video.aid(),
                    video.bvid(),
                    video.title(),
                    video.author(),
                    "https://www.bilibili.com/video/" + video.bvid(),
                    item.score(),
                    item.reason(),
                    video.sourceTitle()
            ));
        }

        Instant now = Instant.now();
        store.save(now.toString(), recommendations);
        log.info("[推荐] 扫描完成: 收藏夹={} 样本={} 候选={} 推荐={} 文件={}",
                folders.size(), seeds.size(), candidates.size(), recommendations.size(),
                recommendation.outputFile());
        for (Recommendation item : recommendations) {
            log.info("[推荐] {}分 | {} | UP: {} | {} | 理由: {}",
                    item.score(), item.title(), item.author(), item.url(), item.reason());
        }
    }

    private List<FavoriteFolder> loadFolders(AppConfig.Recommendation recommendation) {
        List<FavoriteFolder> result = new ArrayList<>();
        if (!recommendation.favoriteFolderIds().isEmpty()) {
            for (Long id : recommendation.favoriteFolderIds()) {
                result.add(new FavoriteFolder(id, "收藏夹 " + id, 0));
            }
        } else {
            result.addAll(bilibili.fetchFavoriteFolders(config.bilibili().ownerUid()));
        }
        if (result.size() > recommendation.maxFolders()) {
            return new ArrayList<>(result.subList(0, recommendation.maxFolders()));
        }
        return result;
    }

    private List<Scored> score(List<FavoriteVideo> seeds, List<RelatedVideo> candidates) {
        try {
            String favorites = seeds.stream()
                    .limit(20)
                    .map(v -> "- " + safe(v.title()) + "（UP: " + safe(v.author()) + "）")
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("（无）");
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                RelatedVideo v = candidates.get(i);
                list.append('[').append(i).append("] ")
                        .append(safe(v.title()))
                        .append(" | UP: ").append(safe(v.author()))
                        .append(" | 播放: ").append(v.play())
                        .append(" | 简介: ").append(truncate(safe(v.description()), 120))
                        .append('\n');
            }
            String system = """
                    你是 B 站视频推荐助手。根据主人的收藏偏好，从候选视频中挑选最可能让主人感兴趣的视频。
                    只输出 JSON 数组，不要 Markdown，不要解释。格式：
                    [{"index":0,"score":85,"reason":"不超过40字的推荐理由"}]
                    score 为 0-100 的整数，最多返回 %d 个；不要推荐收藏夹里已有的视频。
                    """.formatted(config.recommendation().maxResults());
            String user = """
                    主人收藏的视频：
                    %s

                    候选视频：
                    %s
                    请严格按 JSON 数组输出。
                    """.formatted(favorites, list);
            String output = deepSeek.chat(List.of(
                    ChatMessage.system(system),
                    ChatMessage.user(user)
            ));
            return parseScores(output, candidates);
        } catch (RuntimeException e) {
            log.warn("[推荐] DeepSeek 评分失败，改用热度排序: {}", e.getMessage());
            return fallbackScores(candidates);
        }
    }

    private List<Scored> parseScores(String output, List<RelatedVideo> candidates) {
        try {
            String json = stripFences(output);
            JsonNode root = MAPPER.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("recommendations");
            if (!array.isArray()) {
                throw new IOException("返回值不是 JSON 数组");
            }
            List<Scored> result = new ArrayList<>();
            for (JsonNode node : array) {
                int index = node.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                int score = Math.max(0, Math.min(100, node.path("score").asInt(0)));
                String reason = truncate(node.path("reason").asText(""), 200);
                result.add(new Scored(candidates.get(index), score, reason));
            }
            result.sort(Comparator.comparingInt(Scored::score).reversed());
            return result.isEmpty() ? fallbackScores(candidates) : result;
        } catch (IOException e) {
            log.warn("[推荐] 解析 DeepSeek 评分失败，改用热度排序: {}", e.getMessage());
            return fallbackScores(candidates);
        }
    }

    private List<Scored> fallbackScores(List<RelatedVideo> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(RelatedVideo::play).reversed())
                .limit(config.recommendation().maxResults())
                .map(v -> new Scored(v, config.recommendation().minScore(), "按热度推荐"))
                .toList();
    }

    private static String stripFences(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end);
            }
        }
        return text.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max)) + "…";
    }

    private record Scored(RelatedVideo video, int score, String reason) {
    }
}
