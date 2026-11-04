package cn.citprobe.bilibot.bilibili;

import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.config.ConfigException;
import cn.citprobe.bilibot.recommend.RecommendationModels.FavoriteFolder;
import cn.citprobe.bilibot.recommend.RecommendationModels.FavoriteVideo;
import cn.citprobe.bilibot.recommend.RecommendationModels.RelatedVideo;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B 站 Web API 客户端，只依赖 Cookie 登录态。
 */
public final class BilibiliClient {

    private static final Logger log = LoggerFactory.getLogger(BilibiliClient.class);

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String REPLY_ADD_URL = "https://api.bilibili.com/x/v2/reply/add";
    private static final String THREAD_REPLY_URL = "https://api.bilibili.com/x/v2/reply/reply";
    private static final String FAVORITE_FOLDERS_URL =
            "https://api.bilibili.com/x/v3/fav/folder/created/list";
    private static final String FAVORITE_RESOURCES_URL =
            "https://api.bilibili.com/x/v3/fav/resource/list";
    private static final String RELATED_VIDEOS_URL =
            "https://api.bilibili.com/x/web-interface/archive/related";

    private final AppConfig.Bilibili config;
    private final HttpClient http;
    private final ObjectMapper mapper = Json.BILIBILI;
    private final String csrf;
    private final String navUrl;
    private final String replyAddUrl;
    private final String threadReplyUrl;

    public BilibiliClient(AppConfig.Bilibili config) {
        this(config, NAV_URL, REPLY_ADD_URL, THREAD_REPLY_URL);
    }

    BilibiliClient(AppConfig.Bilibili config, String navUrl, String replyAddUrl, String threadReplyUrl) {
        this.config = config;
        this.navUrl = navUrl;
        this.replyAddUrl = replyAddUrl;
        this.threadReplyUrl = threadReplyUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(config.requestTimeoutSeconds(), 15)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.csrf = cookieValue(config.cookie(), "bili_jct")
                .orElseThrow(() -> new ConfigException("Cookie 中缺少 bili_jct，无法发表评论"));
    }

    public BotIdentity whoAmI() {
        HttpRequest request = baseRequest(navUrl, "https://www.bilibili.com/")
                .GET()
                .build();
        JsonNode root = execute(request);
        BilibiliDtos.NavResponse response = treeToValue(root, BilibiliDtos.NavResponse.class);
        if (response.code() != 0 || response.data() == null || !response.data().isLogin()
                || response.data().mid() <= 0) {
            throw new BilibiliApiException(
                    response.code() == 0 ? -101 : response.code(),
                    200,
                    "Cookie 登录态无效或已过期，请重新从浏览器复制完整 Cookie"
            );
        }
        return new BotIdentity(response.data().mid(), nullToEmpty(response.data().uname()));
    }

    public List<BilibiliDtos.MsgFeedItem> fetchAtMessages() {
        return fetchMessages(config.atFeedUrl(), "@");
    }

    public List<BilibiliDtos.MsgFeedItem> fetchReplyMessages() {
        return fetchMessages(config.replyFeedUrl(), "回复");
    }

    private List<BilibiliDtos.MsgFeedItem> fetchMessages(String feedUrl, String label) {
        String separator = feedUrl.contains("?") ? "&" : "?";
        String url = feedUrl + separator
                + "build=0&mobi_app=web&platform=web&web_location=333.1387";
        HttpRequest request = baseRequest(url, "https://message.bilibili.com/")
                .GET()
                .build();
        JsonNode root = execute(request);
        BilibiliDtos.MsgFeedResponse response = treeToValue(root, BilibiliDtos.MsgFeedResponse.class);
        if (response.code() != 0) {
            throw new BilibiliApiException(response.code(), 200,
                    "获取 " + label + " 消息失败: " + nullToEmpty(response.message()));
        }
        if (response.data() == null || response.data().items() == null) {
            return List.of();
        }
        return response.data().items();
    }

    /**
     * 获取某个根评论下的回复，按时间升序，最多 limit 条。
     */
    public List<ThreadComment> fetchThreadContext(CommentTarget target, int limit) {
        if (limit <= 0 || target.root() <= 0) {
            return List.of();
        }
        int pageSize = Math.min(limit, 20);
        String url = threadReplyUrl
                + "?oid=" + target.oid()
                + "&type=" + target.type()
                + "&root=" + target.root()
                + "&ps=" + pageSize
                + "&pn=1";
        String referer = isHttpUrl(target.uri()) ? target.uri() : config.referer();
        HttpRequest request = baseRequest(url, referer).GET().build();
        JsonNode root = execute(request);
        BilibiliDtos.ReplyListResponse response = treeToValue(root, BilibiliDtos.ReplyListResponse.class);
        if (response.code() != 0) {
            throw new BilibiliApiException(response.code(), 200,
                    "获取评论串上下文失败: " + nullToEmpty(response.message()));
        }
        if (response.data() == null || response.data().replies() == null) {
            return List.of();
        }

        List<ThreadComment> comments = new ArrayList<>();
        for (BilibiliDtos.ThreadReply reply : response.data().replies()) {
            if (reply == null || reply.content() == null || reply.content().message() == null) {
                continue;
            }
            String content = reply.content().message().trim();
            if (content.isEmpty()) {
                continue;
            }
            String uname = reply.member() == null ? "" : nullToEmpty(reply.member().uname());
            comments.add(new ThreadComment(reply.rpid(), reply.mid(), uname, content, reply.ctime()));
        }
        comments.sort(Comparator.comparingLong(ThreadComment::ctime).thenComparingLong(ThreadComment::rpid));
        if (comments.size() > limit) {
            comments = new ArrayList<>(comments.subList(comments.size() - limit, comments.size()));
        }
        return comments;
    }

    /**
     * 在主人评论下发表回复，返回新评论 rpid。
     */
    public long addReply(CommentTarget target, String message) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("oid", Long.toString(target.oid()));
        form.put("type", Integer.toString(target.type()));
        form.put("root", Long.toString(target.root()));
        form.put("parent", Long.toString(target.parent()));
        form.put("message", message);
        form.put("plat", "1");
        form.put("csrf", csrf);

        String referer = isHttpUrl(target.uri()) ? target.uri() : config.referer();
        HttpRequest request = baseRequest(replyAddUrl, referer)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", "https://www.bilibili.com")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();

        JsonNode root = execute(request);
        BilibiliDtos.AddReplyResponse response = treeToValue(root, BilibiliDtos.AddReplyResponse.class);
        if (response.code() != 0) {
            throw new BilibiliApiException(response.code(), 200,
                    "发表评论失败: " + nullToEmpty(response.message()));
        }
        long rpid = response.data() == null ? 0 : response.data().rpid();
        log.debug("B站回复发表成功，rpid={}", rpid);
        return rpid;
    }

    /**
     * 读取主人公开收藏夹。私密收藏夹不会出现在其他账号的接口结果中。
     */
    public List<FavoriteFolder> fetchFavoriteFolders(long ownerUid) {
        String url = FAVORITE_FOLDERS_URL
                + "?up_mid=" + ownerUid + "&pn=1&ps=50&platform=web";
        JsonNode root = execute(baseRequest(url, "https://space.bilibili.com/" + ownerUid + "/favlist")
                .GET().build());
        checkCode(root, "读取收藏夹列表");
        JsonNode list = root.path("data").path("list");
        List<FavoriteFolder> result = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode node : list) {
                long id = node.path("id").asLong(node.path("media_id").asLong());
                if (id <= 0) {
                    continue;
                }
                result.add(new FavoriteFolder(
                        id,
                        node.path("title").asText(""),
                        node.path("media_count").asInt(0)
                ));
            }
        }
        return result;
    }

    public List<FavoriteVideo> fetchFavoriteVideos(long mediaId, int limit) {
        int pageSize = Math.max(1, Math.min(limit, 20));
        String url = FAVORITE_RESOURCES_URL
                + "?media_id=" + mediaId + "&pn=1&ps=" + pageSize + "&platform=web";
        JsonNode root = execute(baseRequest(url, "https://space.bilibili.com/")
                .GET().build());
        checkCode(root, "读取收藏夹内容");
        JsonNode medias = root.path("data").path("medias");
        List<FavoriteVideo> result = new ArrayList<>();
        if (medias.isArray()) {
            for (JsonNode node : medias) {
                if (node.path("type").asInt(2) != 2) {
                    continue;
                }
                long aid = node.path("id").asLong();
                String bvid = node.path("bvid").asText("");
                if (aid <= 0 || bvid.isBlank()) {
                    continue;
                }
                result.add(new FavoriteVideo(
                        aid,
                        bvid,
                        node.path("title").asText(""),
                        node.path("intro").asText(""),
                        node.path("upper").path("name").asText(""),
                        node.path("cnt_info").path("play").asLong(0),
                        node.path("cnt_info").path("collect").asLong(0),
                        node.path("pubtime").asLong(0),
                        node.path("duration").asLong(0)
                ));
            }
        }
        return result;
    }

    public List<RelatedVideo> fetchRelatedVideos(long aid, int limit, String sourceTitle) {
        int max = Math.max(1, limit);
        String url = RELATED_VIDEOS_URL + "?aid=" + aid;
        JsonNode root = execute(baseRequest(url, "https://www.bilibili.com/video/av" + aid)
                .GET().build());
        checkCode(root, "读取相关视频");
        List<RelatedVideo> result = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode node : data) {
                if (result.size() >= max) {
                    break;
                }
                long relatedAid = node.path("aid").asLong();
                String bvid = node.path("bvid").asText("");
                if (relatedAid <= 0 || bvid.isBlank()) {
                    continue;
                }
                result.add(new RelatedVideo(
                        relatedAid,
                        bvid,
                        node.path("title").asText(""),
                        node.path("desc").asText(""),
                        node.path("owner").path("name").asText(""),
                        node.path("stat").path("view").asLong(0),
                        node.path("stat").path("like").asLong(0),
                        node.path("pubdate").asLong(0),
                        node.path("duration").asLong(0),
                        sourceTitle
                ));
            }
        }
        return result;
    }

    private void checkCode(JsonNode root, String action) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new BilibiliApiException(code, 200,
                    action + "失败: " + root.path("message").asText(""));
        }
    }

    private HttpRequest.Builder baseRequest(String url, String referer) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Cookie", config.cookie())
                .header("User-Agent", config.userAgent())
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "identity");
    }

    private JsonNode execute(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BilibiliApiException(-1, 0, "请求被中断", e);
        } catch (IOException e) {
            throw new BilibiliApiException(-1, 0, "访问 B 站接口失败: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        String body = stripBom(response.body());
        if (body == null || body.isBlank()) {
            throw new BilibiliApiException(-1, status,
                    "B 站接口返回空响应（HTTP " + status + "）");
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            String preview = body.length() > 120 ? body.substring(0, 120) + "..." : body;
            throw new BilibiliApiException(-1, status,
                    "B 站接口返回非 JSON 内容（HTTP " + status + "）：" + preview, e);
        }

        int code = root.path("code").asInt(0);
        String message = root.path("message").asText("");
        if (status >= 400 || code != 0) {
            if (status == 412) {
                throw new BilibiliApiException(code == 0 ? -412 : code, status,
                        "触发 B 站风控（HTTP 412），请降低频率或检查 Cookie/IP");
            }
            throw new BilibiliApiException(code, status,
                    "B 站接口错误 code=" + code + ", HTTP=" + status + ", message=" + message);
        }
        return root;
    }

    private <T> T treeToValue(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException e) {
            throw new BilibiliApiException(-1, 200, "解析 B 站响应失败: " + e.getMessage(), e);
        }
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Optional<String> cookieValue(String cookie, String name) {
        for (String pair : cookie.split(";")) {
            String item = pair.trim();
            int index = item.indexOf('=');
            if (index > 0 && item.substring(0, index).trim().equals(name)) {
                String value = item.substring(index + 1).trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private static String stripBom(String value) {
        if (value == null) {
            return null;
        }
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
