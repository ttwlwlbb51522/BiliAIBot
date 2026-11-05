package cn.citprobe.bilibot.browse;

/**
 * B 站搜索结果中的视频。
 */
public record SearchVideo(
        long aid,
        String bvid,
        String title,
        String description,
        String author,
        long play,
        long pubdate,
        String url
) {
}
