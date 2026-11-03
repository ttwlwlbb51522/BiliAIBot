package cn.citprobe.bilibot.mention;

import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionEventFactoryTest {

    private static BilibiliDtos.MsgFeedItem item(long sourceId, String uri, String content, long atTime) {
        BilibiliDtos.MsgUser owner = new BilibiliDtos.MsgUser(7L, "主人", "avatar");
        BilibiliDtos.MsgUser bot = new BilibiliDtos.MsgUser(42L, "DeepSeek", "avatar");
        BilibiliDtos.MsgItem detail = new BilibiliDtos.MsgItem(
                999L, 0L, sourceId, 0L, "reply", 1L, "视频", "标题", "", "",
                uri, "", "", content, "", List.of(bot), false
        );
        return new BilibiliDtos.MsgFeedItem(123L, owner, detail, 1, 0, 0L, atTime);
    }

    @Test
    void mapsAtFeedItem() {
        BilibiliDtos.MsgFeedItem raw = item(100L, "https://www.bilibili.com/video/BV1xx#reply100",
                "@DeepSeek 你好", 1_700_000_000L);

        MentionEvent event = MentionEventFactory.from(raw).orElseThrow();

        assertEquals(123L, event.eventId());
        assertEquals(100L, event.sourceRpid());
        assertEquals(999L, event.subjectId());
        assertEquals(1, event.commentType());
        assertEquals(1_700_000_000L, event.replyTime());
        assertEquals(7L, event.authorUid());
        assertTrue(event.mentions(42L));
        assertEquals(100L, event.toTarget().parent());
        assertEquals(100L, event.toTarget().root());
    }

    @Test
    void fallsBackToReplyIdInUri() {
        BilibiliDtos.MsgFeedItem raw = item(0L, "https://www.bilibili.com/video/BV1xx#reply321",
                "@DeepSeek 你好", 1_700_000_000L);

        MentionEvent event = MentionEventFactory.from(raw).orElseThrow();

        assertEquals(321L, event.sourceRpid());
    }

    @Test
    void derivesRootForNestedReply() {
        BilibiliDtos.MsgFeedItem raw = item(100L, "https://www.bilibili.com/video/BV1xx", "回复", 1L);
        BilibiliDtos.MsgItem nested = new BilibiliDtos.MsgItem(
                999L, 88L, 100L, 0L, "reply", 1L, "视频", "", "", "",
                "https://www.bilibili.com/video/BV1xx", "", "", "回复", "", List.of(), false
        );
        BilibiliDtos.MsgFeedItem nestedRaw = new BilibiliDtos.MsgFeedItem(
                123L, raw.user(), nested, 1, 0, 0L, 1_700_000_000L
        );

        MentionEvent event = MentionEventFactory.from(nestedRaw).orElseThrow();

        assertEquals(88L, event.toTarget().root());
        assertEquals(100L, event.toTarget().parent());
    }

    @Test
    void rejectsItemWithoutSourceComment() {
        BilibiliDtos.MsgFeedItem raw = item(0L, "https://example.com/no-reply-id",
                "@DeepSeek 你好", 1L);

        assertFalse(MentionEventFactory.from(raw).isPresent());
    }
}
