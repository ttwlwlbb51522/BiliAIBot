package cn.citprobe.bilibot.browse;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.draft.DraftService;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.notify.NotifyService;
import cn.citprobe.bilibot.service.PersonaSource;
import cn.citprobe.bilibot.service.ReplyFormatter;
import cn.citprobe.bilibot.util.Json;
import cn.citprobe.bilibot.video.VideoInsight;
import cn.citprobe.bilibot.video.VideoInsightService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手动刷视频：搜索“蓝色大肥鱼”相关视频，文字/画面理解后评分，按配置私聊主人或发评论。
 * 只由控制台 browse 命令触发，不自动运行。
 */
public final class SelfBrowseService {

    private static final Logger log = LoggerFactory.getLogger(SelfBrowseService.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DeepSeekClient deepSeek;
    private final BotIdentity bot;
    private final DailyStats stats;
    private final NotifyService notify;
    private final DraftService drafts;
    private final VideoInsightService videoInsight;
    private final PersonaSource persona;
    private final ReplyFormatter formatter;
    private final Set<String> seen = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int nextSearchPage = 1;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bili-deepseek-browse");
        thread.setDaemon(true);
        return thread;
    });

    public SelfBrowseService(AppConfig config, BilibiliClient bilibili, DeepSeekClient deepSeek,
                             BotIdentity bot, DailyStats stats, NotifyService notify,
                             DraftService drafts) {
        this.config = config;
        this.bilibili = bilibili;
        this.deepSeek = deepSeek;
        this.bot = bot;
        this.stats = stats;
        this.notify = notify;
        this.drafts = drafts;
        this.videoInsight = new VideoInsightService(bilibili, config.video());
        this.persona = new PersonaSource(config.reply().systemPromptFile());
        this.formatter = new ReplyFormatter(new AppConfig.Reply(
                300, false, "", "本鱼觉得这个挺有意思", config.reply().systemPromptFile(), 2000,
                "direct", 0
        ));
        loadSeen();
    }

    public void trigger(String keyword) {
        String query = keyword == null || keyword.isBlank()
                ? config.browse().keyword() : keyword.trim();
        if (!running.compareAndSet(false, true)) {
            log.info("[刷视频] 已有任务在运行，忽略本次请求");
            return;
        }
        int page = takePage();
        log.info("[刷视频] 收到手动请求，关键词：{}，起始页={}", query, page);
        executor.submit(() -> {
            try {
                browse(query, config.browse().maxVideos(), config.browse().maxResults(), page, false);
            } catch (Exception e) {
                log.warn("[刷视频] 执行失败: {}", e.getMessage());
                log.debug("刷视频异常详情", e);
            } finally {
                running.set(false);
            }
        });
    }

    public void triggerAuto() {
        if (!config.browse().autoEnabled()) {
            log.debug("[刷视频] 自动任务未启用");
            return;
        }
        String query = config.browse().keyword();
        if (!running.compareAndSet(false, true)) {
            log.info("[刷视频] 上一轮尚未结束，跳过本轮自动任务");
            return;
        }
        int page = takePage();
        executor.submit(() -> {
            try {
                log.info("[刷视频] 自动任务开始，页码={}，关键词={}", page, query);
                browse(query, config.browse().maxVideos(), 1, page, true);
            } catch (Exception e) {
                log.warn("[刷视频] 自动任务失败: {}", e.getMessage());
                log.debug("自动刷视频异常详情", e);
            } finally {
                running.set(false);
            }
        });
    }

    private void browse(String keyword, int searchLimit, int maxResults, int startPage, boolean auto) {
        int page = startPage;
        int shared = 0;
        int processed = 0;
        int pagesTried = 0;
        int searched = 0;
        while (pagesTried < 5) {
            pagesTried++;
            List<SearchVideo> videos = bilibili.searchVideos(keyword, searchLimit, page);
            searched += videos.size();
            if (videos.isEmpty()) {
                log.info("[刷视频] 第 {} 页没有搜索到视频", page);
                page = nextPage();
                continue;
            }
            int before = processed;
            for (SearchVideo video : videos) {
                if (seen.contains(video.bvid())) {
                    continue;
                }
                if (auto && processed >= 1) {
                    break;
                }
                processed++;
                seen.add(video.bvid());
                log.info("[刷视频] 查看：{}（{}）", video.title(), video.bvid());
                String summary;
                try {
                    VideoInsight insight = videoInsight.analyze(video.bvid());
                    summary = insight.summary();
                } catch (RuntimeException e) {
                    log.warn("[刷视频] 内容读取失败 {}: {}", video.bvid(), e.getMessage());
                    summary = "标题：" + video.title() + "\nUP：" + video.author()
                            + "\n简介：" + video.description();
                }
                ScoreResult result = score(video, summary);
                log.info("[刷视频] 评分={} 理由={}", result.score(), result.reason());
                if (result.score() < config.browse().minScore() || shared >= maxResults) {
                    continue;
                }
                share(video, result, summary);
                shared++;
            }
            if (processed > before) {
                // 本轮已经找到新视频；下次从下一页继续，避免一直卡在第一页。
                page = nextPage();
                break;
            }
            log.info("[刷视频] 第 {} 页没有新视频，继续下一页", page);
            page = nextPage();
        }
        saveSeen();
        log.info("[刷视频] 本轮完成：起始页={} 尝试 {} 页，搜索 {} 条，实际查看 {} 条，分享 {} 条",
                startPage, pagesTried, searched, processed, shared);
    }

    private synchronized int takePage() {
        int page = nextSearchPage;
        nextSearchPage = nextSearchPage >= 50 ? 1 : nextSearchPage + 1;
        return page;
    }

    private synchronized int nextPage() {
        return takePage();
    }

    private ScoreResult score(SearchVideo video, String summary) {
        String system = persona.get() + """

                你现在是“蓝色大肥鱼”，正在按自己的喜好刷 B 站视频，不是通用推荐助手。
                请完全按本鱼的性格、兴趣和说话方式判断：
                - 这个视频本鱼会不会觉得有意思、想分享给主人或想吐槽？
                - 是否符合本鱼的兴趣和价值观，而不是只看播放量或标题？
                - 低质广告、标题党、搬运、争议或让本鱼不舒服的内容要低分。
                只输出 JSON 对象，不要 Markdown：
                {"score":85,"reason":"不超过40字的理由，用本鱼视角","comment":"不超过120字的评论，本鱼口吻"}
                score 0-100；评论不要 @ 主人，不要编造视频里没有的内容。
                """;
        String user = """
                视频标题：%s
                UP：%s
                播放：%d
                简介：%s
                内容摘要：
                %s
                """.formatted(video.title(), video.author(), video.play(),
                truncate(video.description(), 500), truncate(summary, 5000));
        try {
            String output = deepSeek.chat(List.of(
                    ChatMessage.system(system),
                    ChatMessage.user(user)
            ));
            JsonNode node = MAPPER.readTree(stripFences(output));
            int score = Math.max(0, Math.min(100, node.path("score").asInt(0)));
            String reason = node.path("reason").asText("本鱼觉得有意思");
            String comment = node.path("comment").asText("本鱼觉得这个挺有意思");
            return new ScoreResult(score, reason, formatter.format(comment, ""));
        } catch (Exception e) {
            log.warn("[刷视频] 评分失败 {}: {}", video.bvid(), e.getMessage());
            return new ScoreResult(0, "评分失败", "本鱼觉得这个挺有意思");
        }
    }

    private void share(SearchVideo video, ScoreResult result, String summary) {
        String text = "主人，本鱼刷到一个有意思的视频：\n"
                + video.title() + "\n" + video.url() + "\n理由：" + result.reason();
        if (config.bilibili().dryRun()) {
            log.info("[刷视频] DRY-RUN 不发送 | 方式={} | {}", config.browse().shareMode(), text);
            return;
        }
        try {
            switch (config.browse().shareMode()) {
                case "dm" -> {
                    bilibili.sendPrivateMessage(bot.mid(), config.bilibili().ownerUid(), text);
                    log.info("[刷视频] 已私聊主人：{}", video.title());
                }
                case "comment" -> {
                    drafts.saveBrowseDraft(video, result.comment(), result.reason());
                    log.info("[刷视频] 评论已进入待审队列，请用 drafts approve 审核：{}",
                            video.title());
                }
                default -> log.info("[刷视频] share-mode=none，不发送");
            }
            if (stats != null) {
                stats.increment("browseShared");
            }
            if (notify != null) {
                notify.send("[刷视频] " + video.title() + "\n" + video.url()
                        + "\n理由：" + result.reason());
            }
        } catch (RuntimeException e) {
            if (stats != null) {
                stats.increment("errors");
            }
            log.warn("[刷视频] 分享失败 {}: {}", video.bvid(), e.getMessage());
        }
    }

    private void loadSeen() {
        Path file = config.browse().seenFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            SeenState state = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    SeenState.class);
            if (state != null && state.seen() != null) {
                seen.addAll(state.seen());
            }
        } catch (IOException e) {
            log.warn("[刷视频] 读取已看记录失败: {}", file, e);
        }
    }

    private void saveSeen() {
        try {
            Path file = config.browse().seenFile();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new SeenState(new ArrayList<>(seen))),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[刷视频] 写入已看记录失败: {}", config.browse().seenFile(), e);
        }
    }

    private static String stripFences(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(newline + 1);
            }
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end);
            }
        }
        return text.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private record ScoreResult(int score, String reason, String comment) {
    }

    public record SeenState(List<String> seen) {
    }
}
