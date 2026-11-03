package cn.citprobe.bilibot.mention;

import java.util.Set;

/**
 * 根据消息通知里的业务 ID 和跳转链接推断评论区类型。
 */
public final class CommentTypeResolver {

    private static final Set<Integer> KNOWN_TYPES = Set.of(
            1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 33
    );

    private CommentTypeResolver() {
    }

    public static int resolve(long businessId, String uri) {
        int fromUri = fromUri(uri);
        // B 站网页端回复时直接使用 business_id 作为评论区 type。
        // 少数历史接口把动态评论标为 11，此时以跳转链接修正为动态类型 17。
        if (businessId == 11 && fromUri == 17) {
            return 17;
        }
        if (businessId > 0 && businessId <= Integer.MAX_VALUE && KNOWN_TYPES.contains((int) businessId)) {
            return (int) businessId;
        }
        return fromUri;
    }

    private static int fromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return 0;
        }
        String value = uri.toLowerCase();
        if (value.contains("/video/") || value.contains("b23.tv/")) {
            return 1;
        }
        if (value.contains("/opus/") || value.contains("t.bilibili.com/") || value.contains("/dynamic/")) {
            return 17;
        }
        if (value.contains("/read/")) {
            return 12;
        }
        if (value.contains("/audio/")) {
            return 14;
        }
        if (value.contains("/bangumi/")) {
            return 1;
        }
        return 0;
    }
}
