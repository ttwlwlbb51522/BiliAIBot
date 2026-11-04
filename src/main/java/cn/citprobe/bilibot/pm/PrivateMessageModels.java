package cn.citprobe.bilibot.pm;

/**
 * 私信接口的最小数据模型。
 */
public final class PrivateMessageModels {

    private PrivateMessageModels() {
    }

    public record PrivateSession(long talkerId, int unreadCount, long atTime) {
    }

    public record PrivateMessage(
            String msgKey,
            long senderUid,
            int msgType,
            String content,
            long timestamp
    ) {
    }
}
