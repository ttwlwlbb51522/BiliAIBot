package cn.citprobe.bilibot.recommend;

/**
 * 收藏夹推荐使用的最小数据模型。
 */
public final class RecommendationModels {

    private RecommendationModels() {
    }

    public record FavoriteFolder(long id, String title, int mediaCount) {
    }

    public record FavoriteVideo(
            long aid,
            String bvid,
            String title,
            String intro,
            String author,
            long play,
            long favorite,
            long pubtime,
            long duration
    ) {
    }

    public record RelatedVideo(
            long aid,
            String bvid,
            String title,
            String description,
            String author,
            long play,
            long like,
            long pubdate,
            long duration,
            String sourceTitle
    ) {
    }

    public record Recommendation(
            long aid,
            String bvid,
            String title,
            String author,
            String url,
            int score,
            String reason,
            String sourceTitle
    ) {
    }
}
