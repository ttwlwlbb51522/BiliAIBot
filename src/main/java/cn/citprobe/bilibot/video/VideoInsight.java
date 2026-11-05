package cn.citprobe.bilibot.video;

/**
 * 视频文字理解结果。
 */
public record VideoInsight(
        String bvid,
        long aid,
        long cid,
        String title,
        String author,
        String description,
        String subtitles,
        String hotComments,
        String hotDanmaku,
        String frames
) {
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(blank(title)).append('\n');
        sb.append("UP：").append(blank(author)).append('\n');
        if (!description.isBlank()) {
            sb.append("简介：").append(description).append('\n');
        }
        if (!subtitles.isBlank()) {
            sb.append("字幕：").append(subtitles).append('\n');
        }
        if (!hotComments.isBlank()) {
            sb.append("热评：").append(hotComments).append('\n');
        }
        if (!hotDanmaku.isBlank()) {
            sb.append("弹幕：").append(hotDanmaku).append('\n');
        }
        if (!frames.isBlank()) {
            sb.append("画面：").append(frames).append('\n');
        }
        String text = sb.toString().trim();
        return text.length() <= 6000 ? text : text.substring(0, 6000) + "…";
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "（未知）" : value;
    }
}
