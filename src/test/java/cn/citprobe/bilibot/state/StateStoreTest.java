package cn.citprobe.bilibot.state;

import cn.citprobe.bilibot.config.ConfigException;
import cn.citprobe.bilibot.mention.MentionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    @TempDir
    Path tempDir;

    private static MentionEvent event(long eventId, long sourceRpid) {
        return new MentionEvent(
                eventId, sourceRpid, 0, 999, 1, 1_700_000_000L,
                7, "主人", "@DeepSeek 你好", "标题", "https://www.bilibili.com/video/BV1xx",
                Set.of(42L)
        );
    }

    @Test
    void persistsHandledEventsAcrossRestart() {
        Path file = tempDir.resolve("data/state.json");
        StateStore store = new StateStore(file);
        assertFalse(store.isInitialized());

        store.initialize(List.of());
        store.markHandled(event(1L, 100L));

        assertTrue(Files.isRegularFile(file));

        StateStore reloaded = new StateStore(file);
        assertTrue(reloaded.isInitialized());
        assertTrue(reloaded.wasHandled(event(1L, 100L)));
        assertTrue(reloaded.wasHandled(event(2L, 100L)), "同一源评论的新通知也应视为已处理");
        assertFalse(reloaded.wasHandled(event(3L, 101L)));
    }

    @Test
    void recordsSkippedEventsOnInitialization() {
        Path file = tempDir.resolve("state.json");
        StateStore store = new StateStore(file);

        store.initialize(List.of(event(10L, 200L)));

        assertTrue(store.wasHandled(event(10L, 200L)));
        assertTrue(store.isInitialized());
    }

    @Test
    void rejectsCorruptStateFile() throws Exception {
        Path file = tempDir.resolve("state.json");
        Files.writeString(file, "{not-json");

        assertThrows(ConfigException.class, () -> new StateStore(file));
    }
}
