package cn.citprobe.bilibot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCommentedTemplateWhenFileMissing() throws Exception {
        Path file = tempDir.resolve("application.properties");

        ConfigFileManager.PrepareResult result = ConfigFileManager.prepare(file);

        assertTrue(result.created());
        assertTrue(Files.isRegularFile(file));
        String content = Files.readString(file);
        assertTrue(content.contains("bilibili.cookie="));
        assertTrue(content.contains("bilibili.owner-uid=0"));
        assertTrue(content.contains("bilibili.first-run-grace-seconds=120"));
        assertTrue(content.contains("reply.fallback-text="));

        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        assertEquals("45", properties.getProperty("bilibili.poll-interval-seconds"));
        assertEquals("120", properties.getProperty("bilibili.first-run-grace-seconds"));
    }

    @Test
    void backfillsMissingKeysAndBlankValuesWithoutOverwritingUserValues() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, """
                # 用户自己的注释
                bilibili.cookie=SESSDATA=a; bili_jct=b
                bilibili.owner-uid=123
                deepseek.api-key=sk-test
                bilibili.poll-interval-seconds=60
                bilibili.dry-run=
                reply.fallback-text=
                """);

        ConfigFileManager.PrepareResult result = ConfigFileManager.prepare(file);

        assertFalse(result.created());
        assertTrue(result.addedKeys() > 0);
        assertTrue(result.filledKeys() >= 2);

        String content = Files.readString(file);
        assertTrue(content.contains("bilibili.poll-interval-seconds=60"), "不能覆盖用户值");
        assertTrue(content.contains("bilibili.dry-run=true"), "空值应补默认值");
        String expectedFallback = ConfigFileManager.templateDefaults().getProperty("reply.fallback-text");
        assertTrue(content.contains("reply.fallback-text=" + expectedFallback));
        assertTrue(content.contains("deepseek.model=deepseek-flash"), "缺失键应补全");
        assertTrue(content.contains("# 自动刷视频：每 30 分钟检查并处理一条新视频。"),
                "补全项应带模板注释");
        assertTrue(content.indexOf("deepseek.model=deepseek-flash")
                        < content.indexOf("browse.auto-interval-minutes=30"),
                "补全项应按模板位置插入，而不是堆到文件末尾");
        assertFalse(content.contains("以下配置项由程序自动补全"));

        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        assertEquals("60", properties.getProperty("bilibili.poll-interval-seconds"));
        assertEquals("true", properties.getProperty("bilibili.dry-run"));
        assertEquals("deepseek-flash", properties.getProperty("deepseek.model"));
    }

    @Test
    void secondPrepareIsIdempotent() throws Exception {
        Path file = tempDir.resolve("application.properties");
        ConfigFileManager.prepare(file);
        String first = Files.readString(file);

        ConfigFileManager.PrepareResult second = ConfigFileManager.prepare(file);

        assertFalse(second.created());
        assertEquals(0, second.addedKeys());
        assertEquals(0, second.filledKeys());
        assertEquals(first, Files.readString(file));
    }

    @Test
    void loadResolvedGeneratesThenBackfillsAndValidates() throws Exception {
        Path file = tempDir.resolve("application.properties");

        assertThrows(ConfigException.class, () -> ConfigLoader.loadResolved(file));
        assertTrue(Files.isRegularFile(file), "即使必填项为空也应先生成配置文件");

        Files.writeString(file, """
                bilibili.cookie=SESSDATA=a; bili_jct=b
                bilibili.owner-uid=123
                deepseek.api-key=sk-test
                """);

        AppConfig config = ConfigLoader.loadResolved(file);

        assertEquals(123L, config.bilibili().ownerUid());
        assertEquals("auto", config.bilibili().loginType());
        assertEquals(file.getParent().resolve("persona.txt").toAbsolutePath().normalize(),
                config.reply().systemPromptFile());
        assertEquals(120, config.bilibili().firstRunGraceSeconds());
        assertEquals(ConfigFileManager.templateDefaults().getProperty("reply.fallback-text"),
                config.reply().fallbackText());
        assertTrue(config.bilibili().dryRun());
    }

    @Test
    void loadsFileWithUtf8Bom() throws Exception {
        Path file = tempDir.resolve("application.properties");
        Files.writeString(file, "\uFEFFbilibili.cookie=SESSDATA=a; bili_jct=b\n"
                + "bilibili.owner-uid=123\n"
                + "deepseek.api-key=sk-test\n");

        AppConfig config = ConfigLoader.loadResolved(file);

        assertEquals(123L, config.bilibili().ownerUid());
    }

    @Test
    void resolvesExplicitPathToAbsolutePath() {
        Path explicit = tempDir.resolve("custom.properties");

        assertEquals(explicit.toAbsolutePath().normalize(), ConfigFileManager.resolvePath(explicit));
    }
}
