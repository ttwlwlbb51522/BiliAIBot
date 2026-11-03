package cn.citprobe.bilibot.auth;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieJarTest {

    @Test
    void capturesSetCookieHeaders() {
        CookieJar jar = new CookieJar();
        HttpResponse<String> response = new FakeResponse(Map.of(
                "set-cookie", List.of(
                        "SESSDATA=abc%2C123; Domain=.bilibili.com; Path=/; HttpOnly",
                        "bili_jct=csrf; Path=/",
                        "DedeUserID=42; Path=/"
                )
        ));
        jar.capture(response);

        assertTrue(jar.contains("SESSDATA"));
        assertTrue(jar.contains("bili_jct"));
        assertEquals("SESSDATA=abc%2C123; bili_jct=csrf; DedeUserID=42", jar.header());
    }

    @Test
    void capturesCookiesFromLoginUrl() {
        CookieJar jar = new CookieJar();
        jar.captureUrl("https://passport.biligame.com/crossDomain?SESSDATA=abc%2C123&bili_jct=csrf"
                + "&DedeUserID=42&gourl=https%3A%2F%2Fwww.bilibili.com%2F");

        assertTrue(jar.contains("SESSDATA"));
        assertEquals("SESSDATA=abc,123; bili_jct=csrf; DedeUserID=42", jar.header());
    }

    @Test
    void maxAgeZeroRemovesCookie() {
        CookieJar jar = new CookieJar();
        jar.put("buvid3", "x");
        jar.capture(new FakeResponse(Map.of("set-cookie",
                List.of("buvid3=; Max-Age=0; Path=/"))));

        assertFalse(jar.contains("buvid3"));
    }

    private static final class FakeResponse implements HttpResponse<String> {
        private final HttpHeaders headers;
        private final String body = "{}";

        private FakeResponse(Map<String, List<String>> rawHeaders) {
            this.headers = HttpHeaders.of(rawHeaders, (k, v) -> true);
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public java.net.http.HttpRequest request() {
            return null;
        }

        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return java.net.URI.create("https://example.com");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
