package cn.citprobe.bilibot.draft;

import cn.citprobe.bilibot.bilibili.CommentTarget;

/**
 * 待主人审核的回复草稿。
 */
public record Draft(
        String id,
        long eventId,
        long authorUid,
        String authorName,
        long sourceRpid,
        long rootRpid,
        long subjectId,
        int commentType,
        String title,
        String uri,
        String content,
        String reply,
        String createdAt
) {
    public CommentTarget target() {
        long root = rootRpid > 0 ? rootRpid : sourceRpid;
        return new CommentTarget(subjectId, commentType, root, sourceRpid, uri);
    }
}
