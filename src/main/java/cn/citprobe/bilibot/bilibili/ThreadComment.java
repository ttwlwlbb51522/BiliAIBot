package cn.citprobe.bilibot.bilibili;

/**
 * 评论串中的一条回复，用于给模型提供上下文。
 */
public record ThreadComment(long rpid, long mid, String uname, String content, long ctime) {
}
