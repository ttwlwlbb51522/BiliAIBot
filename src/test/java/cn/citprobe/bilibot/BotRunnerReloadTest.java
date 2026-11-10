package cn.citprobe.bilibot;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotRunnerReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadsBrowseSettingsFromConfigFile() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, """
                bilibili.cookie=SESSDATA=a; bili_jct=b; buvid3=c
                bilibili.owner-uid=12345
                deepseek.api-key=sk-test
                browse.auto-interval-minutes=30
                """);
        AppConfig config = ConfigLoader.loadResolved(file);
        BotRunner runner = new BotRunner(config, null);
        runner.setConfigPath(file);
        try {
            Files.writeString(file, Files.readString(file)
                    .replace("browse.auto-interval-minutes=30",
                            "browse.auto-interval-minutes=7"));
            AppConfig reloaded = runner.reloadConfig();
            assertEquals(7, reloaded.browse().autoIntervalMinutes());
        } finally {
            runner.close();
        }
    }
}
