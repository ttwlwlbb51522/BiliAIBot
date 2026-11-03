package cn.citprobe.bilibot.mention;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionSelectorTest {

    private final BotIdentity bot = new BotIdentity(42L, "DeepSeek");
    private final AppConfig.Bilibili config = config(true, List.of("小深"));

    private static AppConfig.Bilibili config(boolean requireMention, List<String> aliases) {
        return new AppConfig.Bilibili(
                "SESSDATA=x; bili_jct=y",
                "",
                "",
                "auto",
                7L,
                "ua",
                "https://www.bilibili.com/",
                45,
                20,
                "https://api.bilibili.com/x/msgfeed/at",
                requireMention,
                false,
                120,
                120,
                12,
                true,
                aliases,
                3
        );
    }

    private MentionEvent event(long author, String content, Set<Long> atUids) {
        return new MentionEvent(
                1L, 100L, 0L, 999L, 1, 1_700_000_000L,
                author, "主人", content, "标题", "https://www.bilibili.com/video/BV1xx",
                atUids
        );
    }

    @Test
    void acceptsOwnerWhoMentionsBotByUid() {
        assertTrue(MentionSelector.isEligible(event(7L, "@DeepSeek 你好", Set.of(42L)), bot, config));
    }

    @Test
    void acceptsOwnerWhoMentionsBotByNameWhenAtDetailsMissing() {
        assertTrue(MentionSelector.isEligible(event(7L, "@DeepSeek 你好", Set.of()), bot, config));
    }

    @Test
    void acceptsConfiguredAlias() {
        assertTrue(MentionSelector.isEligible(event(7L, "@小深 你好", Set.of()), bot, config));
    }

    @Test
    void rejectsOtherUsers() {
        assertFalse(MentionSelector.isEligible(event(8L, "@DeepSeek 你好", Set.of(42L)), bot, config));
    }

    @Test
    void rejectsMissingMention() {
        assertFalse(MentionSelector.isEligible(event(7L, "你好", Set.of()), bot, config));
    }

    @Test
    void ownerOnlyModeDoesNotNeedMention() {
        AppConfig.Bilibili ownerOnly = config(false, List.of());
        assertTrue(MentionSelector.isEligible(event(7L, "你好", Set.of()), bot, ownerOnly));
    }

    @Test
    void rejectsUnsupportedCommentType() {
        MentionEvent event = new MentionEvent(
                1L, 100L, 0L, 999L, 0, 1_700_000_000L,
                7L, "主人", "@DeepSeek 你好", "标题", "https://example.com/unknown", Set.of(42L)
        );
        assertFalse(MentionSelector.isEligible(event, bot, config));
    }
}
