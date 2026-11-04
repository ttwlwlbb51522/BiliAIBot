package cn.citprobe.bilibot.service;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.bilibili.ThreadComment;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.deepseek.ChatMessage;
import cn.citprobe.bilibot.mention.MentionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @TempDir
    Path tempDir;

    private AppConfig.Bilibili bilibiliConfig() {
        return new AppConfig.Bilibili(
                "SESSDATA=x; bili_jct=y", "", "", "auto", 7L, "ua", "https://www.bilibili.com/",
                45, 20, "https://api.bilibili.com/x/msgfeed/at",
                "https://api.bilibili.com/x/msgfeed/reply",
                true, false, false, 60, 8, true, false, 120, 120, 12, true, List.of("小深"), 3
        );
    }

    private AppConfig.Reply replyConfig(Path promptFile) {
        return new AppConfig.Reply(500, true, "", "兜底回复", promptFile, 3000);
    }

    @Test
    void buildsSystemAndUserMessagesWithContext() throws Exception {
        Path promptFile = tempDir.resolve("system.txt");
        Files.writeString(promptFile, "最大 {{max_length}} 字。");

        PromptBuilder builder = new PromptBuilder(
                replyConfig(promptFile), bilibiliConfig(), new BotIdentity(42L, "DeepSeek")
        );
        MentionEvent event = new MentionEvent(
                1L, 100L, 0L, 999L, 1, 1_700_000_000L,
                7L, "主人", "@DeepSeek 你好，介绍一下你自己", "测试视频",
                "https://www.bilibili.com/video/BV1xx", Set.of(42L)
        );
        List<ThreadComment> context = List.of(
                new ThreadComment(88L, 7L, "主人", "之前的问题", 1_700_000_000L),
                new ThreadComment(89L, 42L, "DeepSeek", "之前的回答", 1_700_000_001L),
                new ThreadComment(100L, 7L, "主人", "@DeepSeek 你好，介绍一下你自己", 1_700_000_002L)
        );

        List<ChatMessage> messages = builder.build(event, context);

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("最大 500 字"));

        String user = messages.get(1).content();
        assertTrue(user.contains("你好，介绍一下你自己"));
        assertFalse(user.contains("@DeepSeek"), "触发用的 @ 不应原样传给模型");
        assertTrue(user.contains("之前的回答"));
        assertTrue(user.contains("我（AI）"));
        assertEquals(1, user.split("你好，介绍一下你自己", -1).length - 1,
                "当前评论不应在上下文中重复");
    }

    @Test
    void stripsAliasesAndInvisibleCharacters() {
        PromptBuilder builder = new PromptBuilder(
                replyConfig(tempDir.resolve("missing.txt")), bilibiliConfig(), new BotIdentity(42L, "DeepSeek")
        );

        String stripped = builder.stripTriggers("@小深 @DeepSeek\u200B 在吗");

        assertFalse(stripped.contains("@小深"));
        assertFalse(stripped.contains("@DeepSeek"));
        assertTrue(stripped.contains("在吗"));
    }

    @Test
    void addressesNonOwnerAsNetizen() {
        PromptBuilder builder = new PromptBuilder(
                replyConfig(tempDir.resolve("missing.txt")), bilibiliConfig(), new BotIdentity(42L, "DeepSeek")
        );
        MentionEvent event = new MentionEvent(
                2L, 101L, 0L, 999L, 1, 1_700_000_000L,
                8L, "路人", "@DeepSeek 这鱼怎么卖", "标题",
                "https://www.bilibili.com/video/BV1xx", Set.of(42L)
        );

        String user = builder.build(event, List.of()).get(1).content();

        assertTrue(user.contains("【发言者身份】网友"));
        assertTrue(user.contains("【发言者昵称】路人"));
        assertTrue(user.contains("称呼对方为“网友”"));
        assertTrue(user.contains("对方不是主人"));
        assertFalse(user.contains("直接回复主人"));
    }

    @Test
    void fallsBackWhenPromptFileMissing() {
        PromptBuilder builder = new PromptBuilder(
                replyConfig(tempDir.resolve("missing.txt")), bilibiliConfig(), new BotIdentity(42L, "DeepSeek")
        );
        MentionEvent event = new MentionEvent(
                1L, 100L, 0L, 999L, 1, 1_700_000_000L,
                7L, "主人", "@DeepSeek 你好", "标题", "https://www.bilibili.com/video/BV1xx", Set.of(42L)
        );

        List<ChatMessage> messages = builder.build(event, List.of());

        assertTrue(messages.get(0).content().contains("蓝色大肥鱼"));
        assertTrue(messages.get(0).content().contains("本鱼"));
        assertTrue(messages.get(1).content().contains("暂无其他上下文"));
    }
}
