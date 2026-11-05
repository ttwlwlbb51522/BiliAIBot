package cn.citprobe.bilibot.draft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsDrafts() throws Exception {
        Path file = tempDir.resolve("drafts.json");
        DraftStore first = new DraftStore(file);
        Draft draft = new Draft("1", 10L, 8L, "网友", 100L, 0L, 999L, 1,
                "标题", "https://www.bilibili.com/video/BV1xx",
                "你好", "本鱼在", "2026-11-05T00:00:00Z");
        first.add(draft);

        DraftStore second = new DraftStore(file);

        List<Draft> drafts = second.list();
        assertEquals(1, drafts.size());
        assertEquals("本鱼在", drafts.get(0).reply());
        assertNotNull(second.get("1"));
        assertEquals(100L, drafts.get(0).target().parent());
        assertTrue(Files.readString(file).contains("drafts"));
    }
}
