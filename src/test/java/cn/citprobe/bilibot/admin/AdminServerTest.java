package cn.citprobe.bilibot.admin;

import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.config.ConfigLoader;
import cn.citprobe.bilibot.draft.DraftService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void servesStatusAndDraftsWithToken() throws Exception {
        Properties props = new Properties();
        props.setProperty("bilibili.cookie", "SESSDATA=abc; bili_jct=def; buvid3=xyz");
        props.setProperty("bilibili.owner-uid", "12345");
        props.setProperty("deepseek.api-key", "sk-test");
        props.setProperty("admin.enabled", "true");
        props.setProperty("admin.bind", "127.0.0.1");
        props.setProperty("admin.token", "test-token");
        int port = ThreadLocalRandom.current().nextInt(20000, 30000);
        props.setProperty("admin.port", Integer.toString(port));
        props.setProperty("admin.log-file", tempDir.resolve("bot.log").toString());
        Path configFile = tempDir.resolve("application.properties");
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            props.store(writer, "test");
        }
        AppConfig config = ConfigLoader.load(configFile);
        DraftService drafts = new DraftService(config, null, null, null);
        AdminServer server = new AdminServer(config, new BotIdentity(1, "机器人"), drafts, null, null);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                            .header("X-Admin-Token", "test-token")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            JsonNode body = MAPPER.readTree(status.body());
            assertTrue(body.path("ok").asBoolean());
            assertEquals("机器人", body.path("bot").path("name").asText());

            HttpResponse<String> draftsResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/drafts"))
                            .header("X-Admin-Token", "test-token")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, draftsResponse.statusCode());
            assertTrue(MAPPER.readTree(draftsResponse.body()).path("drafts").isArray());
        } finally {
            server.close();
        }
    }
}
