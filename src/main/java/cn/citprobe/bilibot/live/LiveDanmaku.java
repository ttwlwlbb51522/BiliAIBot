package cn.citprobe.bilibot.live;

/**
 * 直播弹幕历史中的一条弹幕。
 */
public record LiveDanmaku(
        long uid,
        String nickname,
        String text,
        String timeline
) {
    public String key() {
        return uid + ":" + timeline + ":" + text;
    }
}
