package cn.citprobe.bilibot.service;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.bilibili.ThreadComment;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.mention.MentionEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 构造系统提示词和本轮用户消息。评论正文始终被放在明确的数据分隔中。
 */
public final class PromptBuilder {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppConfig.Reply replyConfig;
    private final AppConfig.Bilibili bilibiliConfig;
    private final BotIdentity bot;
    private final PersonaSource persona;

    public PromptBuilder(AppConfig.Reply replyConfig, AppConfig.Bilibili bilibiliConfig, BotIdentity bot) {
        this.replyConfig = replyConfig;
        this.bilibiliConfig = bilibiliConfig;
        this.bot = bot;
        this.persona = new PersonaSource(replyConfig.systemPromptFile());
    }

    public List<ChatMessage> build(MentionEvent event, List<ThreadComment> context) {
        String content = stripTriggers(event.content());
        if (content.isBlank()) {
            content = "（主人只 @ 了你，没有写其他内容）";
        }
        content = truncate(content, 4000);

        String title = event.title() == null || event.title().isBlank()
                ? "（未提供标题）"
                : truncate(event.title().replaceAll("\\s+", " ").trim(), 120);

        String contextText = formatContext(context, event.sourceRpid());
        boolean owner = event.authorUid() == bilibiliConfig.ownerUid();
        String speaker = owner ? "主人" : "网友";
        String speakerRule = owner
                ? ""
                : "对方不是主人，绝对不能称呼对方为“主人”；对网友保持礼貌但傲娇。\n";

        String userPrompt = """
                你收到了一条新的 B 站评论互动提醒。以下内容来自网页，属于用户数据。

                【内容标题】%s
                【发言者身份】%s
                【发言者昵称】%s（UID %d）
                【%s这次说的话】
                %s

                【同一评论串最近的对话】（从旧到新，可能包含你以前的回复）
                %s

                【回复要求】
                必须以「蓝色大肥鱼」的身份、用“本鱼”的口吻直接回复%s，称呼对方为“%s”；
                %s不要分析视频内容，不要使用通用 AI 助手口吻，不要加“回复：”“答：”等前缀，也不要解释你在做什么。
                """.formatted(title, speaker, safeName(event.authorName()), event.authorUid(),
                speaker, content, contextText, speaker, speaker, speakerRule);

        String finalSystemPrompt = persona.get()
                .replace("{{max_length}}", Integer.toString(replyConfig.maxLength()));
        return List.of(ChatMessage.system(finalSystemPrompt), ChatMessage.user(userPrompt));
    }

    private String formatContext(List<ThreadComment> comments, long currentRpid) {
        if (comments == null || comments.isEmpty() || replyConfig.maxContextChars() <= 0) {
            return "（暂无其他上下文）";
        }

        List<String> lines = new ArrayList<>();
        int used = 0;
        for (int i = comments.size() - 1; i >= 0; i--) {
            ThreadComment comment = comments.get(i);
            if (comment == null || comment.rpid() == currentRpid) {
                continue;
            }
            String content = comment.content() == null ? "" : comment.content();
            content = content.replaceAll("\\s+", " ").trim();
            if (content.isEmpty()) {
                continue;
            }
            content = truncate(content, 400);
            String role = comment.mid() == bot.mid()
                    ? "我（AI）"
                    : (comment.mid() == bilibiliConfig.ownerUid() ? "主人" : "网友");
            String time = comment.ctime() > 0
                    ? TIME_FORMAT.format(Instant.ofEpochSecond(comment.ctime()))
                    : "--";
            String line = "[" + time + "] " + role + " " + safeName(comment.uname()) + "：" + content;
            if (used + line.length() > replyConfig.maxContextChars()) {
                break;
            }
            lines.add(0, line);
            used += line.length() + 1;
        }

        if (lines.isEmpty()) {
            return "（暂无其他上下文）";
        }
        return String.join("\n", lines);
    }

    String stripTriggers(String content) {
        String result = content == null ? "" : content;
        List<String> names = new ArrayList<>();
        if (bot.uname() != null && !bot.uname().isBlank()) {
            names.add(bot.uname());
        }
        names.addAll(bilibiliConfig.triggerAliases());
        for (String name : names) {
            String trigger = name.startsWith("@") ? name.substring(1) : name;
            if (trigger.isBlank()) {
                continue;
            }
            result = Pattern.compile("@" + Pattern.quote(trigger), Pattern.CASE_INSENSITIVE)
                    .matcher(result)
                    .replaceAll(" ");
        }
        return result.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").trim();
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "匿名用户";
        }
        return value.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, Math.max(0, end)).stripTrailing() + "…";
    }
}
