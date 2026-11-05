package cn.citprobe.bilibot.deepseek;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesAnswer() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = """
                    {
                      "choices": [{
                        "message": {"role": "assistant", "content": "你好，我是 DeepSeek。"},
                        "finish_reason": "stop"
                      }]
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppConfig.DeepSeek config = new AppConfig.DeepSeek(
                "sk-test",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "deepseek-flash",
                0.7,
                345,
                10,
                0
        );
        DeepSeekClient client = new DeepSeekClient(config);

        String answer = client.chat(List.of(
                ChatMessage.system("你是助手"),
                ChatMessage.user("你好")
        ));

        assertEquals("你好，我是 DeepSeek。", answer);
        assertEquals("Bearer sk-test", authorization.get());
        JsonNode sent = Json.DEEPSEEK.readTree(requestBody.get());
        assertEquals("deepseek-flash", sent.path("model").asText());
        assertEquals(345, sent.path("max_tokens").asInt());
        assertEquals(0.7, sent.path("temperature").asDouble());
        assertTrue(!sent.path("stream").asBoolean());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
        assertEquals("你好", sent.path("messages").get(1).path("content").asText());
    }
}
