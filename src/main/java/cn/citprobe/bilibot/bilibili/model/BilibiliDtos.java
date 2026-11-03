package cn.citprobe.bilibot.bilibili.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * B 站接口返回结构。使用 snake_case 反序列化，未知字段全部忽略。
 */
public final class BilibiliDtos {

    private BilibiliDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgFeedResponse(int code, String message, MsgFeedData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgFeedData(MsgCursor cursor, List<MsgFeedItem> items, long lastViewAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgCursor(boolean isEnd, long id, long time) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgFeedItem(long id, MsgUser user, MsgItem item, int counts, int isMulti,
                              long replyTime, long atTime) {

        public long eventTime() {
            return atTime > 0 ? atTime : replyTime;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgUser(long mid, String nickname, String avatar) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgItem(
            long subjectId,
            long rootId,
            long sourceId,
            long targetId,
            String type,
            long businessId,
            String business,
            String title,
            String desc,
            String image,
            String uri,
            String nativeUri,
            String rootReplyContent,
            String sourceContent,
            String targetReplyContent,
            List<MsgUser> atDetails,
            boolean hideReplyButton
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NavResponse(int code, String message, NavData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NavData(
            // nav 接口使用 camelCase 的 isLogin，而其他接口大多使用 snake_case。
            @JsonProperty("isLogin") @JsonAlias("is_login") boolean isLogin,
            long mid,
            String uname,
            String face
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplyListResponse(int code, String message, ReplyListData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplyListData(List<ThreadReply> replies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadReply(long rpid, long mid, long root, long parent, long ctime,
                              ThreadMember member, ThreadContent content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadMember(long mid, String uname) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadContent(String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddReplyResponse(int code, String message, AddReplyData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddReplyData(long rpid, String rpidStr) {
    }
}
