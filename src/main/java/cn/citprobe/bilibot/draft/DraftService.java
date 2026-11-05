package cn.citprobe.bilibot.draft;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.CommentTarget;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.mention.MentionEvent;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.notify.NotifyService;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿审批与网友回复频率限制。
 */
public final class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DailyStats stats;
    private final NotifyService notify;
    private final DraftStore store;
    private final Path cooldownFile;
    private final Map<Long, Long> lastReplyAt = new LinkedHashMap<>();

    public DraftService(AppConfig config, BilibiliClient bilibili,
                        DailyStats stats, NotifyService notify) {
        this.config = config;
        this.bilibili = bilibili;
        this.stats = stats;
        this.notify = notify;
        Path state = config.app().stateFile();
        this.store = new DraftStore(state.resolveSibling("drafts.json"));
        this.cooldownFile = state.resolveSibling("person-cooldowns.json");
        loadCooldowns();
    }

    public boolean draftEnabled() {
        return "draft".equals(config.reply().otherUsersMode());
    }

    public Draft save(MentionEvent event, String reply) {
        String id = System.currentTimeMillis() + "-" + event.eventId();
        Draft draft = new Draft(
                id,
                event.eventId(),
                event.authorUid(),
                event.authorName(),
                event.sourceRpid(),
                event.rootRpid(),
                event.subjectId(),
                event.commentType(),
                event.title(),
                event.uri(),
                event.content(),
                reply,
                Instant.now().toString()
        );
        store.add(draft);
        if (stats != null) {
            stats.increment("drafts");
        }
        if (notify != null) {
            notify.sendDraft(draft);
        }
        log.info("[草稿] 已保存待审回复: id={}, 作者={}({}), 评论={}, 内容={}",
                id, event.authorName(), event.authorUid(), event.sourceRpid(), preview(event.content()));
        return draft;
    }

    public List<Draft> list() {
        return store.list();
    }

    public boolean approve(String id) {
        Draft draft = store.get(id);
        if (draft == null) {
            log.warn("[草稿] 不存在: {}", id);
            return false;
        }
        if (config.bilibili().dryRun()) {
            log.info("[草稿] DRY-RUN 不发送，草稿保留: id={} 拟回复={}", id, preview(draft.reply()));
            return false;
        }
        try {
            long rpid = bilibili.addReply(draft.target(), draft.reply());
            store.remove(id);
            markReplied(draft.authorUid());
            if (stats != null) {
                stats.increment("commentsReplied");
            }
            log.info("[草稿] 已批准并发送: id={}, 新评论 rpid={}, 内容={}",
                    id, rpid, preview(draft.reply()));
            return true;
        } catch (RuntimeException e) {
            log.warn("[草稿] 发送失败，草稿保留: id={}, {}", id, e.getMessage());
            return false;
        }
    }

    public boolean drop(String id) {
        if (store.get(id) == null) {
            log.warn("[草稿] 不存在: {}", id);
            return false;
        }
        store.remove(id);
        log.info("[草稿] 已丢弃: {}", id);
        return true;
    }

    public boolean isCooling(long uid) {
        int days = config.reply().samePersonCooldownDays();
        if (days <= 0) {
            return false;
        }
        Long last = lastReplyAt.get(uid);
        return last != null && System.currentTimeMillis() - last < days * 86_400_000L;
    }

    public void markReplied(long uid) {
        if (config.reply().samePersonCooldownDays() <= 0) {
            return;
        }
        lastReplyAt.put(uid, System.currentTimeMillis());
        saveCooldowns();
    }

    public String describe(Draft draft) {
        return String.format("id=%s 作者=%s(%d) 评论=%d 内容=%s 拟回复=%s",
                draft.id(), draft.authorName(), draft.authorUid(), draft.sourceRpid(),
                preview(draft.content()), preview(draft.reply()));
    }

    private void loadCooldowns() {
        if (!Files.isRegularFile(cooldownFile)) {
            return;
        }
        try {
            CooldownState state = MAPPER.readValue(Files.readString(cooldownFile, StandardCharsets.UTF_8),
                    CooldownState.class);
            if (state != null && state.users() != null) {
                for (Map.Entry<String, Long> entry : state.users().entrySet()) {
                    try {
                        lastReplyAt.put(Long.parseLong(entry.getKey()), entry.getValue());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取同人限频文件失败: {}", cooldownFile, e);
        }
    }

    private void saveCooldowns() {
        try {
            Path parent = cooldownFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Long> values = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> entry : lastReplyAt.entrySet()) {
                values.put(Long.toString(entry.getKey()), entry.getValue());
            }
            Files.writeString(cooldownFile,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new CooldownState(values)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入同人限频文件失败: {}", cooldownFile, e);
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    public record CooldownState(Map<String, Long> users) {
    }
}
