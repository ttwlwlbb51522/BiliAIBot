package cn.citprobe.bilibot.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsSeenKeys() throws Exception {
        Path file = tempDir.resolve("live-danmaku.json");
        LiveStore first = new LiveStore(file);
        first.markSeen("uid:time:text");
        first.save();

        LiveStore second = new LiveStore(file);

        assertTrue(second.isSeen("uid:time:text"));
        assertTrue(Files.readString(file).contains("uid:time:text"));
    }
}
