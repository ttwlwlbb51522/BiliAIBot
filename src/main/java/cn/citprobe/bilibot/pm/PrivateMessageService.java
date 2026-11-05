package cn.citprobe.bilibot.pm;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.pm.PrivateMessageModels.PrivateMessage;
import cn.citprobe.bilibot.pm.PrivateMessageModels.PrivateSession;
import cn.citprobe.bilibot.service.PersonaSource;
import cn.citprobe.bilibot.service.ReplyFormatter;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轮询 B 站私信，任何人都可以私聊机器人并获得角色化回复。
 * 私信接口为非公开接口，失败时只记录日志。
 */
public final class PrivateMessageService {

    private static final Logger log = LoggerFactory.getLogger(PrivateMessageService.class);
    private static final ObjectMapper MAPPER = Json.STATE;
    private static final int MAX_SESSIONS = 10;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DeepSeekClient deepSeek;
    private final BotIdentity bot;
    private final DailyStats stats;
    private final PrivateMessageStore store;
    private final PersonaSource persona;
    private final ReplyFormatter formatter;
    private final long startEpoch = Instant.now().getEpochSecond();
    private final java.util.Map<String, Integer> attempts = new java.util.HashMap<>();
    private boolean firstPoll = true;
    private boolean foundNew;
    private boolean lastError;
    private long nextDelaySeconds;

    public PrivateMessageService(AppConfig config, BilibiliClient bilibili,
                                 DeepSeekClient deepSeek, BotIdentity bot, DailyStats stats) {
        this.config = config;
        this.bilibili = bilibili;
        this.deepSeek = deepSeek;
        this.bot = bot;
        this.stats = stats;
        Path stateFile = config.app().stateFile();
        Path pmState = stateFile.resolveSibling("private-messages.json");
        this.store = new PrivateMessageStore(pmState);
        this.persona = new PersonaSource(config.reply().systemPromptFile());
        AppConfig.Reply pmReply = new AppConfig.Reply(
                config.reply().maxLength(),
                false,
                config.reply().prefix(),
                config.reply().fallbackText(),
                config.reply().systemPromptFile(),
                config.reply().maxContextChars(),
                config.reply().otherUsersMode(),
                config.reply().samePersonCooldownDays()
        );
        this.formatter = new ReplyFormatter(pmReply);
        this.nextDelaySeconds = config.bilibili().privateMessagePollSeconds();
    }

    public long nextDelaySeconds() {
        return nextDelaySeconds;
    }

    public void runOnce() {
        if (!config.bilibili().privateMessageEnabled()) {
            return;
        }
        foundNew = false;
        lastError = false;
        List<PrivateSession> sessions = bilibili.fetchPrivateSessions();
        if (sessions.isEmpty()) {
            firstPoll = false;
            updateBackoff();
            return;
        }
        if (firstPoll) {
            int marked = 0;
            for (PrivateSession session : sessions) {
                if (session.talkerId() == bot.mid()) {
                    continue;
                }
                try {
                    marked += processSession(session);
                } catch (RuntimeException e) {
                    log.debug("初始化私聊会话 {} 失败: {}", session.talkerId(), e.getMessage());
                }
            }
            store.save();
            firstPoll = false;
            log.info("[私聊] 首次启动已跳过历史私聊，之后只回复新消息（初始化会话 {} 个）",
                    sessions.size());
            updateBackoff();
            return;
        }
        sessions = sessions.stream()
                .filter(s -> s.talkerId() != bot.mid())
                .sorted(Comparator.comparingLong(PrivateSession::atTime).reversed())
                .limit(MAX_SESSIONS)
                .toList();

        int replied = 0;
        for (PrivateSession session : sessions) {
            try {
                replied += processSession(session);
            } catch (RuntimeException e) {
                log.warn("[私聊] 处理会话 {} 失败: {}", session.talkerId(), e.getMessage());
                log.debug("私聊处理异常详情", e);
            }
        }
        store.save();
        firstPoll = false;
        updateBackoff();
        if (replied > 0) {
            log.info("[私聊] 本轮回复 {} 条私信", replied);
        }
    }

    private void updateBackoff() {
        int base = config.bilibili().privateMessagePollSeconds();
        int max = config.bilibili().privateMessageMaxBackoffSeconds();
        if (foundNew) {
            nextDelaySeconds = base;
        } else {
            nextDelaySeconds = Math.min(max, Math.max(base, nextDelaySeconds * 2));
        }
        if (lastError) {
            nextDelaySeconds = Math.min(max, Math.max(base, nextDelaySeconds * 2));
        }
        log.debug("[私聊] 下次轮询间隔: {}s", nextDelaySeconds);
    }

    private int processSession(PrivateSession session) {
        List<PrivateMessage> messages = bilibili.fetchPrivateMessages(
                session.talkerId(), config.bilibili().privateMessageHistorySize());
        if (messages.isEmpty()) {
            return 0;
        }
        messages = new ArrayList<>(messages);
        messages.sort(Comparator.comparingLong(PrivateMessage::timestamp));

        int replied = 0;
        for (PrivateMessage message : messages) {
            if (message.senderUid() <= 0 || message.senderUid() == bot.mid()) {
                continue;
            }
            String key = message.msgKey().isBlank()
                    ? message.senderUid() + ":" + message.timestamp()
                    : message.msgKey();
            if (store.isSeen(key)) {
                continue;
            }
            if (message.timestamp() > 0 && message.timestamp() < startEpoch) {
                store.markSeen(key);
                continue;
            }
            if (firstPoll) {
                store.markSeen(key);
                continue;
            }
            String text = extractText(message);
            if (text.isBlank()) {
                store.markSeen(key);
                if (message.msgType() != 1) {
                    log.info("[私聊] 忽略非文本消息: 用户={}, type={}", message.senderUid(), message.msgType());
                }
                continue;
            }
            foundNew = true;
            log.info("[私聊] 收到 用户={} UID={} 内容={}",
                    displayName(message.senderUid()), message.senderUid(), preview(text));
            String reply = generateReply(message, messages, text);
            boolean sent = config.bilibili().dryRun();
            if (config.bilibili().dryRun()) {
                log.info("[私聊] DRY-RUN 不发送 | 用户={} | 拟回复：{}",
                        message.senderUid(), reply);
            } else {
                try {
                    bilibili.sendPrivateMessage(bot.mid(), session.talkerId(), reply);
                    sent = true;
                    log.info("[私聊] 已回复 用户={} UID={} 内容={}",
                            displayName(session.talkerId()), session.talkerId(), preview(reply));
                } catch (RuntimeException e) {
                    lastError = true;
                    if (stats != null) {
                        stats.increment("errors");
                    }
                    int count = attempts.merge(key, 1, Integer::sum);
                    if (count >= config.bilibili().privateMessageMaxAttempts()) {
                        store.markSeen(key);
                        attempts.remove(key);
                        log.warn("[私聊] 发送失败 {} 次，放弃该消息: 用户={}：{}",
                                count, session.talkerId(), e.getMessage());
                    } else {
                        log.warn("[私聊] 发送失败，下轮重试（{}/{}）用户={}：{}",
                                count, config.bilibili().privateMessageMaxAttempts(),
                                session.talkerId(), e.getMessage());
                    }
                }
            }
            if (sent) {
                store.markSeen(key);
                attempts.remove(key);
                if (stats != null) {
                    stats.increment("pmReplied");
                }
                replied++;
            }
        }
        return replied;
    }

    private String generateReply(PrivateMessage current, List<PrivateMessage> messages, String text) {
        boolean owner = current.senderUid() == config.bilibili().ownerUid();
        String speaker = owner ? "主人" : "网友";
        String system = persona.get()
                .replace("{{max_length}}", Integer.toString(config.reply().maxLength()))
                + "\n你正在 B 站私聊中回复" + speaker
                + "。不要使用评论区的 @ 语法，不要提到这是私聊接口。";
        String context = formatContext(messages, current);
        String user = """
                你收到了一条 B 站私聊。
                【发言人身份】%s
                【发言人 UID】%d
                【内容】
                %s

                【最近私聊】
                %s

                【回复要求】
                以「蓝色大肥鱼」的身份、用“本鱼”的口吻直接回复%s，称呼对方为“%s”。
                %s
                只输出私聊正文，不要加“回复：”“答：”等前缀。
                """.formatted(
                speaker, current.senderUid(), text, context, speaker, speaker,
                owner ? "" : "对方不是主人，绝对不能称呼对方为“主人”。\n"
        );
        try {
            String output = deepSeek.chat(List.of(
                    ChatMessage.system(system),
                    ChatMessage.user(user)
            ));
            return formatter.format(output, "");
        } catch (RuntimeException e) {
            log.warn("[私聊] DeepSeek 生成失败: {}", e.getMessage());
            return formatter.format("", "");
        }
    }

    private String formatContext(List<PrivateMessage> messages, PrivateMessage current) {
        List<String> lines = new ArrayList<>();
        for (PrivateMessage message : messages) {
            if (message == current) {
                continue;
            }
            String text = extractText(message);
            if (text.isBlank()) {
                continue;
            }
            String role = message.senderUid() == bot.mid()
                    ? "本鱼"
                    : (message.senderUid() == config.bilibili().ownerUid() ? "主人" : "网友");
            lines.add(role + "：" + preview(text, 200));
            if (lines.size() >= config.bilibili().privateMessageHistorySize()) {
                break;
            }
        }
        return lines.isEmpty() ? "（暂无）" : String.join("\n", lines);
    }

    private static String extractText(PrivateMessage message) {
        if (message.msgType() != 1 || message.content() == null) {
            return "";
        }
        String content = message.content();
        try {
            JsonNode node = MAPPER.readTree(content);
            String text = node.path("content").asText("");
            if (!text.isBlank()) {
                return text.trim();
            }
        } catch (IOException ignored) {
            // 兼容直接返回纯文本的情况。
        }
        return content.trim();
    }

    private String displayName(long uid) {
        return uid == config.bilibili().ownerUid() ? "主人" : "网友";
    }

    private static String preview(String value) {
        return preview(value, 120);
    }

    private static String preview(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
