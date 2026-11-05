package cn.citprobe.bilibot.live;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.service.PersonaSource;
import cn.citprobe.bilibot.service.ReplyFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 直播弹幕：轮询最近弹幕并回复。受 B 站接口限制，当前不是 WebSocket 实时流。
 */
public final class LiveDanmakuService {

    private static final Logger log = LoggerFactory.getLogger(LiveDanmakuService.class);
    private static final int MAX_REPLIES_PER_POLL = 3;

    private final AppConfig config;
    private final BilibiliClient bilibili;
    private final DeepSeekClient deepSeek;
    private final BotIdentity bot;
    private final DailyStats stats;
    private final LiveStore store;
    private final PersonaSource persona;
    private final ReplyFormatter formatter;
    private boolean firstPoll = true;

    public LiveDanmakuService(AppConfig config, BilibiliClient bilibili,
                              DeepSeekClient deepSeek, BotIdentity bot, DailyStats stats) {
        this.config = config;
        this.bilibili = bilibili;
        this.deepSeek = deepSeek;
        this.bot = bot;
        this.stats = stats;
        Path state = config.app().stateFile();
        this.store = new LiveStore(state.resolveSibling("live-danmaku.json"));
        this.persona = new PersonaSource(config.reply().systemPromptFile());
        this.formatter = new ReplyFormatter(new AppConfig.Reply(
                30, false, "", "本鱼在", config.reply().systemPromptFile(), 1000,
                "direct", 0
        ));
    }

    public void runOnce() {
        AppConfig.Live live = config.live();
        if (!live.enabled() || live.roomId() <= 0) {
            return;
        }
        List<LiveDanmaku> messages = bilibili.fetchLiveDanmaku(live.roomId());
        if (messages.isEmpty()) {
            return;
        }
        int replied = 0;
        for (LiveDanmaku message : messages) {
            String key = message.key();
            if (store.isSeen(key)) {
                continue;
            }
            if (firstPoll) {
                store.markSeen(key);
                continue;
            }
            if (live.onlyOwner() && message.uid() != config.bilibili().ownerUid()) {
                store.markSeen(key);
                continue;
            }
            if (message.uid() <= 0 || message.uid() == bot.mid() || message.text().isBlank()) {
                store.markSeen(key);
                continue;
            }
            boolean owner = message.uid() == config.bilibili().ownerUid();
            String speaker = owner ? "主人" : "网友";
            String reply = generate(message, speaker, owner);
            log.info("[直播] 收到 {} UID={} 弹幕={}", speaker, message.uid(), message.text());
            if (config.bilibili().dryRun()) {
                log.info("[直播] DRY-RUN 不发送 | 拟回复：{}", reply);
            } else {
                try {
                    bilibili.sendLiveDanmaku(live.roomId(), reply);
                    log.info("[直播] 已回复：{}", reply);
                    if (stats != null) {
                        stats.increment("liveReplies");
                    }
                } catch (RuntimeException e) {
                    if (stats != null) {
                        stats.increment("errors");
                    }
                    log.warn("[直播] 发送失败: {}", e.getMessage());
                    store.markSeen(key);
                    continue;
                }
            }
            store.markSeen(key);
            replied++;
            if (replied >= MAX_REPLIES_PER_POLL) {
                break;
            }
        }
        store.save();
        firstPoll = false;
    }

    private String generate(LiveDanmaku message, String speaker, boolean owner) {
        String system = persona.get().replace("{{max_length}}", "30")
                + "\n你正在回复 B 站直播间弹幕，回复必须短于 30 字，只输出一行纯文本，不要 @" + speaker + "。";
        String user = """
                直播间弹幕：
                发言人身份：%s
                昵称：%s
                内容：%s

                请用「蓝色大肥鱼」本鱼的口吻直接回一句，称呼对方为“%s”。
                %s
                """.formatted(speaker, message.nickname(), message.text(), speaker,
                owner ? "" : "对方不是主人，不能叫对方主人。");
        try {
            String output = deepSeek.chat(List.of(
                    ChatMessage.system(system),
                    ChatMessage.user(user)
            ));
            return formatter.format(output, "");
        } catch (RuntimeException e) {
            log.warn("[直播] 生成回复失败: {}", e.getMessage());
            return "本鱼在";
        }
    }
}
