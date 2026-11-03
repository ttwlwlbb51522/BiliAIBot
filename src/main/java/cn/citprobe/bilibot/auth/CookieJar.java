package cn.citprobe.bilibot.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 登录过程中手工维护的 Cookie 容器。
 */
final class CookieJar {

    private static final Set<String> LOGIN_COOKIES = Set.of(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"
    );

    private final Map<String, String> values = new LinkedHashMap<>();

    void put(String name, String value) {
        if (name != null && value != null && !name.isBlank() && !value.isBlank()) {
            values.put(name, value);
        }
    }

    void mergeHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }
        for (String pair : cookieHeader.split(";")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
            }
        }
    }

    void capture(HttpResponse<?> response) {
        for (String header : response.headers().allValues("set-cookie")) {
            String first = header.split(";", 2)[0].trim();
            int index = first.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String name = first.substring(0, index).trim();
            String value = first.substring(index + 1).trim();
            if (header.toLowerCase().contains("max-age=0")) {
                values.remove(name);
            } else {
                put(name, value);
            }
        }
    }

    void captureUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null) {
                return;
            }
            for (String pair : query.split("&")) {
                int index = pair.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                String name = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
                if (!LOGIN_COOKIES.contains(name)) {
                    continue;
                }
                String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
                put(name, value);
            }
        } catch (IllegalArgumentException ignored) {
            // 登录 URL 异常时仍以 Set-Cookie 为准。
        }
    }

    boolean contains(String name) {
        return values.containsKey(name);
    }

    String header() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
