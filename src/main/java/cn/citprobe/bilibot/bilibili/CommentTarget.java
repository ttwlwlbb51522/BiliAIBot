package cn.citprobe.bilibot.bilibili;

/**
 * 发布回复所需的目标参数。
 *
 * @param oid    评论区对象 ID（视频 aid、动态 ID、专栏 cvid 等）
 * @param type   评论区类型代码（视频=1，动态=17，专栏=12 ...）
 * @param root   根评论 rpid
 * @param parent 被回复评论 rpid
 * @param uri    原页面地址，用作 Referer
 */
public record CommentTarget(long oid, int type, long root, long parent, String uri) {
}
