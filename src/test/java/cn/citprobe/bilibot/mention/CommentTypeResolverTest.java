package cn.citprobe.bilibot.mention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommentTypeResolverTest {

    @Test
    void resolvesVideoFromUri() {
        assertEquals(1, CommentTypeResolver.resolve(0, "https://www.bilibili.com/video/BV1xx"));
        assertEquals(1, CommentTypeResolver.resolve(1, "https://www.bilibili.com/video/BV1xx"));
    }

    @Test
    void resolvesDynamicFromUri() {
        assertEquals(17, CommentTypeResolver.resolve(11, "https://www.bilibili.com/opus/123"));
        assertEquals(17, CommentTypeResolver.resolve(17, "https://www.bilibili.com/opus/123"));
        assertEquals(17, CommentTypeResolver.resolve(0, "https://t.bilibili.com/123"));
    }

    @Test
    void resolvesArticleAndAudio() {
        assertEquals(12, CommentTypeResolver.resolve(0, "https://www.bilibili.com/read/cv123"));
        assertEquals(14, CommentTypeResolver.resolve(0, "https://www.bilibili.com/audio/au123"));
    }

    @Test
    void fallsBackToKnownBusinessId() {
        assertEquals(1, CommentTypeResolver.resolve(1, ""));
        assertEquals(17, CommentTypeResolver.resolve(17, ""));
    }

    @Test
    void returnsZeroForUnknown() {
        assertEquals(0, CommentTypeResolver.resolve(0, "https://example.com/x"));
        assertEquals(0, CommentTypeResolver.resolve(999, ""));
    }
}
