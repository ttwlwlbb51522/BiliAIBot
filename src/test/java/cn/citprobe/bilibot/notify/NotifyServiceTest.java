package cn.citprobe.bilibot.notify;

import cn.citprobe.bilibot.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesFileNotificationAndPersistsDailyStats() throws Exception {
        Path statsFile = tempDir.resolve("daily-stats.json");
        DailyStats stats = new DailyStats(statsFile);
        stats.increment("commentsReplied");
        AppConfig.Notify config = new AppConfig.Notify(
                "file", tempDir.resolve("notifications.log"), "", "", true, 23);
        NotifyService notify = new NotifyService(config, stats);

        notify.send("测试通知");

        String text = Files.readString(config.file());
        assertTrue(text.contains("测试通知"));
        assertTrue(Files.readString(statsFile).contains("commentsReplied"));
    }
}
