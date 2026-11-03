package cn.citprobe.bilibot.service;

import cn.citprobe.bilibot.bilibili.BilibiliApiException;
import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.bilibili.CommentTarget;
import cn.citprobe.bilibot.bilibili.ThreadComment;
import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.deepseek.DeepSeekException;
import cn.citprobe.bilibot.mention.MentionEvent;
import cn.citprobe.bilibot.mention.MentionEventFactory;
import cn.citprobe.bilibot.mention.MentionSelector;
import cn.citprobe.bilibot.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一次轮询的完整业务逻辑：拉取 @ 通知 -> 过滤主人 -> 调用 DeepSeek -> 回复。
 */
public final class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    private static final int MAX_LOGGED_EVENTS = 2000;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DeepSeekClient deepSeek;
    private final StateStore state;
    private final BotIdentity bot;
    private final PromptBuilder promptBuilder;
    private final ReplyFormatter replyFormatter;
    private final Map<Long, Integer> attempts = new HashMap<>();
    private final Set<Long> loggedEventIds = new LinkedHashSet<>();
    private final long firstRunCutoff;

    public BotService(AppConfig config,
                      BilibiliClient bilibili,
                      DeepSeekClient deepSeek,
                      StateStore state,
                      BotIdentity bot,
                      PromptBuilder promptBuilder) {
        this.config = config;
        this.bilibili = bilibili;
        this.deepSeek = deepSeek;
        this.state = state;
        this.bot = bot;
        this.promptBuilder = promptBuilder;
        this.replyFormatter = new ReplyFormatter(config.reply());
        this.firstRunCutoff = Instant.now().getEpochSecond() - config.bilibili().firstRunGraceSeconds();
    }

    public void runOnce() {
        List<BilibiliDtos.MsgFeedItem> rawItems = bilibili.fetchAtMessages();
        List<MentionEvent> eligible = selectMentions(rawItems);
        eligible = initializeIfNeeded(eligible);

        eligible.sort(Comparator
                .comparingLong((MentionEvent e) -> e.replyTime() > 0 ? e.replyTime() : Long.MAX_VALUE)
                .thenComparingLong(MentionEvent::eventId));

        for (MentionEvent event : eligible) {
            process(event);
        }
    }

    private List<MentionEvent> selectMentions(List<BilibiliDtos.MsgFeedItem> rawItems) {
        List<MentionEvent> result = new ArrayList<>();
        for (BilibiliDtos.MsgFeedItem raw : rawItems) {
            Optional<MentionEvent> mapped = MentionEventFactory.from(raw);
            logReceived(raw, mapped.orElse(null));
            if (mapped.isEmpty()) {
                log.warn("[过滤] 无法解析 @ 通知: event={}，可能缺少 source_id/subject_id/正文",
                        raw == null ? 0 : raw.id());
                continue;
            }
            MentionEvent event = mapped.get();
            if (event.authorUid() != config.bilibili().ownerUid()) {
                log.info("[过滤] 非主人 @，忽略: event={}, 用户={}({}), 内容={}",
                        event.eventId(), event.authorName(), event.authorUid(), preview(event.content()));
                continue;
            }
            if (!MentionSelector.isEligible(event, bot, config.bilibili())) {
                if (event.commentType() <= 0) {
                    log.warn("[过滤] 无法识别评论区类型，忽略: event={}, uri={}",
                            event.eventId(), event.uri());
                } else {
                    log.info("[过滤] 未满足触发条件，忽略: event={}, 内容={}",
                            event.eventId(), preview(event.content()));
                }
                continue;
            }
            log.info("[过滤] 命中主人 @: event={}, 评论={}, 类型={}",
                    event.eventId(), event.sourceRpid(), event.commentType());
            result.add(event);
        }
        return result;
    }

    private void logReceived(BilibiliDtos.MsgFeedItem raw, MentionEvent event) {
        if (raw == null || raw.id() <= 0 || !loggedEventIds.add(raw.id())) {
            return;
        }
        while (loggedEventIds.size() > MAX_LOGGED_EVENTS) {
            Long oldest = loggedEventIds.iterator().next();
            loggedEventIds.remove(oldest);
        }
        BilibiliDtos.MsgItem item = raw.item();
        String content = item == null ? "" : item.sourceContent();
        String title = item == null ? "" : item.title();
        String uri = item == null ? "" : item.uri();
        log.info("[接收] @通知 event={} 用户={}({}) 内容={}",
                raw.id(),
                raw.user() == null ? "未知" : raw.user().nickname(),
                raw.user() == null ? 0 : raw.user().mid(),
                preview(content, 500));
        if (event != null) {
            log.info("[接收] 目标: 标题={}, 评论区类型={}, oid={}, root={}, source={}, 时间={}, uri={}",
                    preview(title, 160), event.commentType(), event.subjectId(),
                    event.rootRpid(), event.sourceRpid(),
                    event.replyTime() > 0 ? Instant.ofEpochSecond(event.replyTime()) : "未知",
                    uri);
        } else {
            log.info("[接收] 原始字段: type={}, businessId={}, subjectId={}, rootId={}, sourceId={}, uri={}",
                    item == null ? "" : item.type(),
                    item == null ? 0 : item.businessId(),
                    item == null ? 0 : item.subjectId(),
                    item == null ? 0 : item.rootId(),
                    item == null ? 0 : item.sourceId(),
                    uri);
        }
    }

    /**
     * 首次启动时默认不处理历史 @，但保留最近两分钟内到达的消息，方便启动后立即测试。
     */
    private List<MentionEvent> initializeIfNeeded(List<MentionEvent> eligible) {
        if (state.isInitialized()) {
            return eligible;
        }
        if (config.bilibili().processExisting()) {
            state.initialize();
            log.info("首次启动：按配置处理已存在的 @ 消息（最多回溯 {} 分钟）",
                    config.bilibili().maxMentionAgeMinutes());
            return eligible;
        }

        Set<Long> oldEventIds = new HashSet<>();
        for (MentionEvent event : eligible) {
            if (event.replyTime() > 0 && event.replyTime() < firstRunCutoff) {
                oldEventIds.add(event.eventId());
            }
        }
        List<MentionEvent> skipped = eligible.stream()
                .filter(e -> oldEventIds.contains(e.eventId()))
                .toList();
        state.initialize(skipped);
        if (!skipped.isEmpty()) {
            log.info("首次启动：已跳过 {} 条历史 @（最近 {} 秒内的消息仍会处理；如需处理全部历史请设置 bilibili.process-existing=true）",
                    skipped.size(), config.bilibili().firstRunGraceSeconds());
        } else {
            log.info("首次启动：开始监听新的 @ 消息");
        }
        return eligible.stream()
                .filter(e -> !oldEventIds.contains(e.eventId()))
                .toList();
    }

    private void process(MentionEvent event) {
        if (state.wasHandled(event)) {
            return;
        }
        if (isStale(event)) {
            state.markHandled(event);
            log.info("跳过过期 @：event={}, comment={}, age={}s",
                    event.eventId(), event.sourceRpid(), ageSeconds(event));
            return;
        }

        CommentTarget target = event.toTarget();
        long start = System.currentTimeMillis();
        log.info("[处理] 开始: event={}, comment={}, type={}, oid={}, root={}, parent={}, owner={}({}), content={}",
                event.eventId(), event.sourceRpid(), target.type(), target.oid(), target.root(),
                target.parent(), event.authorName(), event.authorUid(), preview(event.content()));

        try {
            List<ThreadComment> context = fetchContext(target);
            log.info("[处理] 评论串上下文: {} 条", context.size());

            List<ChatMessage> messages = promptBuilder.build(event, context);
            int promptChars = messages.stream().mapToInt(m -> m.content().length()).sum();
            log.info("[处理] Prompt 构造完成: {} 条消息, {} 字", messages.size(), promptChars);

            long aiStart = System.currentTimeMillis();
            log.info("[处理] 调用 DeepSeek: model={}", config.deepseek().model());
            String modelOutput = deepSeek.chat(messages);
            log.info("[处理] DeepSeek 返回: {} 字, 耗时={}ms",
                    modelOutput.length(), System.currentTimeMillis() - aiStart);

            String reply = replyFormatter.format(modelOutput, event.authorName());
            log.info("[处理] 回复整理完成: {} 字", reply.length());

            if (config.bilibili().dryRun()) {
                log.info("[发送] DRY-RUN 不实际发表评论 | event={} comment={} | 拟回复：\n{}",
                        event.eventId(), event.sourceRpid(), reply);
            } else {
                log.info("[发送] 正在发表评论: oid={}, type={}, root={}, parent={}, 内容={}",
                        target.oid(), target.type(), target.root(), target.parent(),
                        preview(reply, 500));
                long rpid = bilibili.addReply(target, reply);
                log.info("[发送] 成功: event={}, 新评论 rpid={}, 总耗时={}ms, 内容={}",
                        event.eventId(), rpid, System.currentTimeMillis() - start,
                        preview(reply, 500));
            }

            state.markHandled(event);
            attempts.remove(event.eventId());
        } catch (BilibiliApiException e) {
            handleBilibiliFailure(event, e);
        } catch (DeepSeekException e) {
            handleDeepSeekFailure(event, e);
        } catch (RuntimeException e) {
            handleUnexpectedFailure(event, e);
        }
    }

    private List<ThreadComment> fetchContext(CommentTarget target) {
        if (config.bilibili().threadContextSize() <= 0) {
            return List.of();
        }
        try {
            return bilibili.fetchThreadContext(target, config.bilibili().threadContextSize());
        } catch (RuntimeException e) {
            log.warn("读取评论串上下文失败，将只根据当前评论回复：{}", e.getMessage());
            return List.of();
        }
    }

    private void handleBilibiliFailure(MentionEvent event, BilibiliApiException e) {
        if (e.isAuthError()) {
            attempts.remove(event.eventId());
            log.error("B 站登录态或权限异常，暂不标记该评论，修复 Cookie 后会自动重试。event={}, {}",
                    event.eventId(), e.getMessage());
            return;
        }
        if (e.isRateLimited()) {
            log.warn("B 站限流/风控，暂不标记该评论，下轮重试。event={}, {}", event.eventId(), e.getMessage());
            return;
        }
        if (!e.isRetryable()) {
            state.markHandled(event);
            attempts.remove(event.eventId());
            log.error("B 站拒绝发表评论（不可重试），已标记跳过。event={}, comment={}：{}",
                    event.eventId(), event.sourceRpid(), e.getMessage());
            return;
        }
        recordRetryableFailure(event, "B站接口", e);
    }

    private void handleDeepSeekFailure(MentionEvent event, DeepSeekException e) {
        int status = e.httpStatus();
        if (status == 401 || status == 402 || status == 403) {
            attempts.remove(event.eventId());
            log.error("DeepSeek 鉴权/余额异常，暂不标记该评论，修复配置后会自动重试。event={}, {}",
                    event.eventId(), e.getMessage());
            return;
        }
        if (!e.isRetryable()) {
            state.markHandled(event);
            attempts.remove(event.eventId());
            log.error("DeepSeek 请求失败（不可重试），已标记跳过。event={}：{}",
                    event.eventId(), e.getMessage());
            return;
        }
        recordRetryableFailure(event, "DeepSeek", e);
    }

    private void handleUnexpectedFailure(MentionEvent event, RuntimeException e) {
        recordRetryableFailure(event, "未预期异常", e);
    }

    private void recordRetryableFailure(MentionEvent event, String stage, RuntimeException e) {
        int count = attempts.merge(event.eventId(), 1, Integer::sum);
        if (count >= config.bilibili().maxAttempts()) {
            state.markHandled(event);
            attempts.remove(event.eventId());
            log.error("{} 连续失败 {} 次，放弃该 @（评论 {}）：{}",
                    stage, count, event.sourceRpid(), e.getMessage());
        } else {
            log.warn("{} 第 {} 次失败，下轮重试（评论 {}）：{}",
                    stage, count, event.sourceRpid(), e.getMessage());
        }
    }

    private boolean isStale(MentionEvent event) {
        if (event.replyTime() <= 0) {
            return false;
        }
        return ageSeconds(event) > config.bilibili().maxMentionAgeMinutes() * 60L;
    }

    private long ageSeconds(MentionEvent event) {
        return Math.max(0, Instant.now().getEpochSecond() - event.replyTime());
    }

    private static String preview(String value) {
        return preview(value, 80);
    }

    private static String preview(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        int end = Math.max(0, maxLength);
        if (end > 0 && Character.isHighSurrogate(oneLine.charAt(end - 1))) {
            end--;
        }
        return oneLine.substring(0, end) + "…";
    }
}
