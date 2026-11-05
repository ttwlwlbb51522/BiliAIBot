package cn.citprobe.bilibot.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static Properties validProperties() {
        Properties props = new Properties();
        props.setProperty("bilibili.cookie", "SESSDATA=abc; bili_jct=def; buvid3=xyz");
        props.setProperty("bilibili.owner-uid", "12345");
        props.setProperty("deepseek.api-key", "sk-test");
        return props;
    }

    @Test
    void parsesDefaultsAndOverrides() {
        Properties props = validProperties();
        props.setProperty("bilibili.poll-interval-seconds", "60");
        props.setProperty("bilibili.trigger-aliases", "小深, DeepSeek ,小深");
        props.setProperty("deepseek.temperature", "0.5");

        AppConfig config = ConfigLoader.parse(props);

        assertEquals(12345L, config.bilibili().ownerUid());
        assertEquals(60, config.bilibili().pollIntervalSeconds());
        assertEquals(2, config.bilibili().triggerAliases().size());
        assertEquals(0.5, config.deepseek().temperature());
        assertTrue(config.bilibili().dryRun(), "默认必须是安全的 dry-run 模式");
        assertFalse(config.bilibili().processExisting());
        assertEquals(120, config.bilibili().firstRunGraceSeconds());
        assertEquals("auto", config.bilibili().loginType());
        assertTrue(config.bilibili().replyFeedEnabled());
        assertFalse(config.bilibili().allowOtherUsers());
        assertFalse(config.bilibili().privateMessageEnabled());
        assertEquals("none", config.notification().type());
        assertTrue(config.notification().dailySummaryEnabled());
        assertEquals(60, config.bilibili().privateMessagePollSeconds());
        assertEquals(8, config.bilibili().privateMessageHistorySize());
        assertEquals(3, config.bilibili().privateMessageMaxAttempts());
        assertEquals(900, config.bilibili().privateMessageMaxBackoffSeconds());
        assertEquals("https://api.bilibili.com/x/msgfeed/reply", config.bilibili().replyFeedUrl());
        assertFalse(config.reply().mentionOwner(), "默认回复不应 @ 主人");
        assertFalse(config.recommendation().enabled());
        assertEquals(720, config.recommendation().intervalMinutes());
        assertEquals(5, config.recommendation().maxFolders());
        assertTrue(config.recommendation().favoriteFolderIds().isEmpty());
        assertFalse(config.recommendation().videoInsightEnabled());
        assertFalse(config.video().framesEnabled());
        assertEquals(6, config.video().frameCount());
        assertFalse(config.live().enabled());
        assertEquals(0, config.live().roomId());
        assertEquals("蓝色大肥鱼", config.browse().keyword());
        assertEquals("dm", config.browse().shareMode());
        assertTrue(config.browse().autoEnabled());
        assertEquals(5, config.browse().autoIntervalMinutes());
        assertEquals("嗯嗯，我在听，你继续说～", config.reply().fallbackText());
        assertTrue(config.app().stateFile().isAbsolute());
        assertTrue(config.app().sessionFile().isAbsolute());
    }

    @Test
    void acceptsUsernamePasswordWithoutCookie() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");
        props.setProperty("bilibili.username", "bot@example.com");
        props.setProperty("bilibili.password", "p@ss word");

        AppConfig config = ConfigLoader.parse(props);

        assertEquals("bot@example.com", config.bilibili().username());
        assertEquals("p@ss word", config.bilibili().password());
        assertTrue(config.bilibili().cookie().isBlank());
    }

    @Test
    void rejectsIncompleteUsernamePasswordPair() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");
        props.setProperty("bilibili.username", "bot@example.com");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsUnknownLoginType() {
        Properties props = validProperties();
        props.setProperty("bilibili.login-type", "token");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void allowsQrLoginWithoutCredentials() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");

        AppConfig config = ConfigLoader.parse(props);

        assertEquals("auto", config.bilibili().loginType());
        assertTrue(config.bilibili().cookie().isBlank());
    }

    @Test
    void acceptsExplicitQrMode() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");
        props.setProperty("bilibili.login-type", "qrcode");

        assertEquals("qrcode", ConfigLoader.parse(props).bilibili().loginType());
    }

    @Test
    void rejectsPasswordModeWithoutCredentials() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");
        props.setProperty("bilibili.login-type", "password");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsCookieModeWithoutCookie() {
        Properties props = validProperties();
        props.remove("bilibili.cookie");
        props.setProperty("bilibili.login-type", "cookie");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsCookieWithoutCsrf() {
        Properties props = validProperties();
        props.setProperty("bilibili.cookie", "SESSDATA=abc");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsMissingOwnerUid() {
        Properties props = validProperties();
        props.setProperty("bilibili.owner-uid", "0");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsMissingApiKey() {
        Properties props = validProperties();
        props.remove("deepseek.api-key");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsTooLongReply() {
        Properties props = validProperties();
        props.setProperty("reply.max-length", "2000");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void rejectsInvalidBoolean() {
        Properties props = validProperties();
        props.setProperty("bilibili.dry-run", "yes");

        assertThrows(ConfigException.class, () -> ConfigLoader.parse(props));
    }

    @Test
    void acceptsApiKeyThatDoesNotLookLikeOpenAiKey() {
        Properties props = validProperties();
        props.setProperty("deepseek.api-key", "local-proxy-token");

        assertEquals("local-proxy-token", ConfigLoader.parse(props).deepseek().apiKey());
    }
}
