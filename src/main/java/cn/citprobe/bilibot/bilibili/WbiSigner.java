package cn.citprobe.bilibot.bilibili;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * B 站 WBI 签名。密钥来自 nav 接口的 wbi_img，签名参数按 key 排序后计算 MD5。
 */
final class WbiSigner {

    private static final int[] MIXIN = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private WbiSigner() {
    }

    static String mixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for (int index : MIXIN) {
            if (index < raw.length()) {
                sb.append(raw.charAt(index));
            }
        }
        return sb.toString();
    }

    static String sign(Map<String, String> params, String mixinKey) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return md5(query + mixinKey);
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
