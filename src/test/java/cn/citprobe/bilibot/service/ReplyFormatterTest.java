package cn.citprobe.bilibot.service;

import cn.citprobe.bilibot.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyFormatterTest {

    private static AppConfig.Reply reply(int maxLength, boolean mentionOwner) {
        return new AppConfig.Reply(maxLength, mentionOwner, "", "兜底回复", Path.of("prompt/system.txt"), 3000,
                "direct", 0);
    }

    @Test
    void formatsMarkdownAndAddsMention() {
        ReplyFormatter formatter = new ReplyFormatter(reply(500, true));

        String result = formatter.format("**你好**\n\n- 第一\n- 第二\n[链接](https://example.com)", "小明");

        assertTrue(result.startsWith("@小明 "));
        assertTrue(result.contains("你好"));
        assertTrue(result.contains("・ 第一"));
        assertTrue(result.contains("链接（https://example.com）"));
        assertFalse(result.contains("**"));
    }

    @Test
    void doesNotDuplicateExistingMention() {
        ReplyFormatter formatter = new ReplyFormatter(reply(500, true));

        String result = formatter.format("@小明 收到啦", "小明");

        assertEquals("@小明 收到啦", result);
        assertEquals(1, result.split("@小明", -1).length - 1);
    }

    @Test
    void truncatesWithinConfiguredLimit() {
        ReplyFormatter formatter = new ReplyFormatter(reply(50, true));

        String result = formatter.format("啊".repeat(200), "一个名字很长的主人");

        assertTrue(result.length() <= 50);
        assertTrue(result.endsWith("…"));
    }

    @Test
    void neverSplitsSurrogatePair() {
        ReplyFormatter formatter = new ReplyFormatter(reply(51, false));

        String result = formatter.format("😀".repeat(100), "主人");

        assertTrue(result.length() <= 51);
        for (int i = 0; i < result.length(); i++) {
            char ch = result.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertTrue(i + 1 < result.length(), "结尾不能是高代理项");
                assertTrue(Character.isLowSurrogate(result.charAt(i + 1)));
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(ch), "孤立低代理项");
            }
        }
    }

    @Test
    void emptyOutputUsesFallback() {
        ReplyFormatter formatter = new ReplyFormatter(reply(500, false));

        String result = formatter.format("   ", "主人");

        assertEquals("兜底回复", result);
    }
}
