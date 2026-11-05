package cn.citprobe.bilibot.bilibili;

import cn.citprobe.bilibot.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TrafficGuardTest {

    @TempDir
    Path tempDir;

    private AppConfig.Risk risk(int readHourly, int writeHourly) {
        return new AppConfig.Risk(true, 1, readHourly, 100, writeHourly, 100,
                0, 24, tempDir.resolve("traffic.json"));
    }

    @Test
    void blocksWhenReadBudgetIsExhausted() {
        TrafficGuard guard = new TrafficGuard(risk(1, 10));
        guard.beforeRead();
        assertThrows(BilibiliApiException.class, guard::beforeRead);
    }

    @Test
    void blocksDuplicateWriteFingerprint() {
        TrafficGuard guard = new TrafficGuard(risk(10, 10));
        guard.beforeWrite("same-comment");
        assertThrows(BilibiliApiException.class, () -> guard.beforeWrite("same-comment"));
    }

    @Test
    void pausesAfter412AndPersistsState() {
        TrafficGuard guard = new TrafficGuard(risk(10, 10));
        guard.afterResponse(412, 0);
        assertThrows(BilibiliApiException.class, guard::beforeRead);

        TrafficGuard reloaded = new TrafficGuard(risk(10, 10));
        assertThrows(BilibiliApiException.class, reloaded::beforeRead);
    }
}
