package cn.citprobe.bilibot.bilibili;

import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;
import cn.citprobe.bilibot.config.AppConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesReplyFeedWithReplyTime() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reply", exchange -> respond(exchange, """
                {
                  "code": 0,
                  "message": "0",
                  "data": {
                    "items": [{
                      "id": 456,
                      "user": {"mid": 7, "nickname": "主人"},
                      "item": {
                        "subject_id": 999,
                        "root_id": 100,
                        "source_id": 101,
                        "target_id": 42,
                        "type": "reply",
                        "business_id": 1,
                        "title": "标题",
                        "uri": "https://www.bilibili.com/video/BV1xx#reply101",
                        "source_content": "本鱼继续说",
                        "at_details": []
                      },
                      "reply_time": 1700000003
                    }]
                  }
                }
                """));
        server.start();

        AppConfig.Bilibili config = new AppConfig.Bilibili(
                "SESSDATA=abc; bili_jct=def", "", "", "auto", 7L, "ua", "https://www.bilibili.com/",
                45, 5, "http://127.0.0.1:" + server.getAddress().getPort() + "/at",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/reply",
                true, false, true, false, 120, 120, 12, true, List.of(), 3
        );
        BilibiliClient client = new BilibiliClient(config);

        BilibiliDtos.MsgFeedItem item = client.fetchReplyMessages().get(0);

        assertEquals(101L, item.item().sourceId());
        assertEquals(42L, item.item().targetId());
        assertEquals(1700000003L, item.eventTime());
        assertEquals("本鱼继续说", item.item().sourceContent());
    }

    @Test
    void parsesAtFeedWithAtTimeAndSnakeCaseFields() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/at", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            String body = """
                    {
                      "code": 0,
                      "message": "0",
                      "data": {
                        "cursor": {"is_end": true, "id": 123, "time": 1700000002},
                        "items": [{
                          "id": 123,
                          "user": {"mid": 7, "nickname": "主人"},
                          "item": {
                            "subject_id": 999,
                            "root_id": 0,
                            "source_id": 100,
                            "target_id": 0,
                            "type": "reply",
                            "business_id": 1,
                            "business": "视频",
                            "title": "标题",
                            "uri": "https://www.bilibili.com/video/BV1xx#reply100",
                            "source_content": "@DeepSeek 你好",
                            "at_details": [{"mid": 42, "nickname": "DeepSeek"}]
                          },
                          "counts": 1,
                          "is_multi": 0,
                          "at_time": 1700000001
                        }],
                        "last_view_at": 1700000002
                      }
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AppConfig.Bilibili config = new AppConfig.Bilibili(
                "SESSDATA=abc; bili_jct=def", "", "", "auto", 7L, "ua", "https://www.bilibili.com/",
                45, 5, "http://127.0.0.1:" + server.getAddress().getPort() + "/at",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/reply",
                true, false, true, false, 120, 120, 12, true, List.of(), 3
        );
        BilibiliClient client = new BilibiliClient(config);

        List<BilibiliDtos.MsgFeedItem> items = client.fetchAtMessages();

        assertEquals(1, items.size());
        BilibiliDtos.MsgFeedItem item = items.get(0);
        assertEquals(123L, item.id());
        assertEquals(1_700_000_001L, item.eventTime());
        assertEquals(100L, item.item().sourceId());
        assertEquals(1L, item.item().businessId());
        assertEquals(42L, item.item().atDetails().get(0).mid());
        assertEquals("@DeepSeek 你好", item.item().sourceContent());
        assertTrue(query.get().contains("mobi_app=web"));
        assertFalse(query.get().isBlank());
    }

    @Test
    void sendsCorrectFormWhenReplyingAndReadsThreadContext() throws Exception {
        AtomicReference<String> replyForm = new AtomicReference<>();
        AtomicReference<String> replyCookie = new AtomicReference<>();
        AtomicReference<String> replyReferer = new AtomicReference<>();
        AtomicReference<String> threadQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nav", exchange -> {
            respond(exchange, """
                    {"code":0,"data":{"isLogin":true,"mid":42,"uname":"DeepSeek"}}
                    """);
        });
        server.createContext("/reply/add", exchange -> {
            replyForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            replyCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            replyReferer.set(exchange.getRequestHeaders().getFirst("Referer"));
            respond(exchange, """
                    {"code":0,"data":{"rpid":555,"rpid_str":"555"}}
                    """);
        });
        server.createContext("/thread", exchange -> {
            threadQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                    {
                      "code": 0,
                      "data": {
                        "replies": [
                          {"rpid": 101, "mid": "7", "ctime": 20, "member": {"uname": "主人"}, "content": {"message": "后来的话"}},
                          {"rpid": 100, "mid": "7", "ctime": 10, "member": {"uname": "主人"}, "content": {"message": "之前的话"}}
                        ]
                      }
                    }
                    """);
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AppConfig.Bilibili config = new AppConfig.Bilibili(
                "SESSDATA=abc; bili_jct=def", "", "", "auto", 7L, "ua", "https://www.bilibili.com/",
                45, 5, base + "/at", base + "/reply", true, false, true, false, 120, 120, 12, true, List.of(), 3
        );
        BilibiliClient client = new BilibiliClient(config, base + "/nav", base + "/reply/add", base + "/thread");

        assertEquals(42L, client.whoAmI().mid());
        CommentTarget target = new CommentTarget(999L, 1, 100L, 100L,
                "https://www.bilibili.com/video/BV1xx#reply100");
        long rpid = client.addReply(target, "你好，主人");
        List<ThreadComment> context = client.fetchThreadContext(target, 2);

        assertEquals(555L, rpid);
        String decodedForm = URLDecoder.decode(replyForm.get(), StandardCharsets.UTF_8);
        assertTrue(decodedForm.contains("oid=999"));
        assertTrue(decodedForm.contains("type=1"));
        assertTrue(decodedForm.contains("root=100"));
        assertTrue(decodedForm.contains("parent=100"));
        assertTrue(decodedForm.contains("message=你好，主人"));
        assertTrue(decodedForm.contains("csrf=def"));
        assertEquals("SESSDATA=abc; bili_jct=def", replyCookie.get());
        assertTrue(replyReferer.get().contains("/video/BV1xx"));

        assertEquals(2, context.size());
        assertEquals(100L, context.get(0).rpid());
        assertEquals(101L, context.get(1).rpid());
        assertTrue(threadQuery.get().contains("root=100"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
