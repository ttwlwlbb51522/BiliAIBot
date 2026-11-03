package cn.citprobe.bilibot.mention;

import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 B 站原始通知转换为 MentionEvent。对字段缺失保持保守，避免回复错评论。
 */
public final class MentionEventFactory {

    private static final Pattern REPLY_ID = Pattern.compile("(?i)(?:#|&|\\?)reply(\\d+)");

    private MentionEventFactory() {
    }

    public static Optional<MentionEvent> from(BilibiliDtos.MsgFeedItem raw) {
        if (raw == null || raw.user() == null || raw.item() == null) {
            return Optional.empty();
        }
        BilibiliDtos.MsgItem item = raw.item();
        long sourceRpid = item.sourceId();
        if (sourceRpid <= 0) {
            sourceRpid = parseReplyId(item.uri());
        }
        if (sourceRpid <= 0 || item.subjectId() <= 0) {
            return Optional.empty();
        }

        String content = item.sourceContent() == null ? "" : item.sourceContent().trim();
        if (content.isEmpty()) {
            return Optional.empty();
        }

        Set<Long> atUids = new LinkedHashSet<>();
        if (item.atDetails() != null) {
            for (BilibiliDtos.MsgUser user : item.atDetails()) {
                if (user != null && user.mid() > 0) {
                    atUids.add(user.mid());
                }
            }
        }

        long eventId = raw.id() > 0 ? raw.id() : sourceRpid;
        String authorName = raw.user().nickname() == null ? "" : raw.user().nickname();
        int type = CommentTypeResolver.resolve(item.businessId(), item.uri());

        return Optional.of(new MentionEvent(
                eventId,
                sourceRpid,
                Math.max(0, item.rootId()),
                item.subjectId(),
                type,
                raw.eventTime(),
                raw.user().mid(),
                authorName,
                content,
                item.title() == null ? "" : item.title(),
                item.uri() == null ? "" : item.uri(),
                Set.copyOf(atUids)
        ));
    }

    private static long parseReplyId(String uri) {
        if (uri == null || uri.isBlank()) {
            return 0;
        }
        Matcher matcher = REPLY_ID.matcher(uri);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
