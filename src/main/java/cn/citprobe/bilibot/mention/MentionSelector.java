package cn.citprobe.bilibot.mention;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;

import java.util.Locale;

/**
 * 判断一条 @ 通知是否满足回复条件：主人发出、确实 @ 了机器人、评论类型可识别。
 */
public final class MentionSelector {

    private MentionSelector() {
    }

    public static boolean isEligible(MentionEvent event, BotIdentity bot, AppConfig.Bilibili config) {
        if (event == null || bot == null) {
            return false;
        }
        if (event.sourceRpid() <= 0 || event.subjectId() <= 0 || event.commentType() <= 0) {
            return false;
        }
        if (event.authorUid() != config.ownerUid()) {
            return false;
        }
        if (event.authorUid() == bot.mid()) {
            return false;
        }
        if (event.content() == null || event.content().isBlank()) {
            return false;
        }
        if (!config.requireBotMention()) {
            return true;
        }

        if (event.mentions(bot.mid())) {
            return true;
        }
        if (containsMention(event.content(), bot.uname())) {
            return true;
        }
        for (String alias : config.triggerAliases()) {
            if (containsMention(event.content(), alias)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsMention(String content, String name) {
        if (content == null || name == null || name.isBlank()) {
            return false;
        }
        String trigger = name.trim();
        if (trigger.startsWith("@")) {
            trigger = trigger.substring(1);
        }
        if (trigger.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains(("@" + trigger).toLowerCase(Locale.ROOT));
    }
}
