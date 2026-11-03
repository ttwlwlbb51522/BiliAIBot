package cn.citprobe.bilibot.mention;

import cn.citprobe.bilibot.bilibili.CommentTarget;

import java.util.Set;

/**
 * 从 B 站 @ 通知中提取出的、可直接处理的事件。
 */
public record MentionEvent(
        long eventId,
        long sourceRpid,
        long rootRpid,
        long subjectId,
        int commentType,
        long replyTime,
        long authorUid,
        String authorName,
        String content,
        String title,
        String uri,
        Set<Long> mentionedUids
) {

    public CommentTarget toTarget() {
        long root = rootRpid > 0 ? rootRpid : sourceRpid;
        return new CommentTarget(subjectId, commentType, root, sourceRpid, uri);
    }

    public boolean mentions(long uid) {
        return mentionedUids != null && mentionedUids.contains(uid);
    }
}
