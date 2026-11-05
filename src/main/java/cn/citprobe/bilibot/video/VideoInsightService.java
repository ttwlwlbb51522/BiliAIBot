package cn.citprobe.bilibot.video;

import cn.citprobe.bilibot.bilibili.BilibiliApiException;
import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读视频文字理解：标题、简介、字幕、热评、弹幕。
 */
public final class VideoInsightService {

    private static final Logger log = LoggerFactory.getLogger(VideoInsightService.class);
    private static final Pattern DANMAKU = Pattern.compile("<d p=\"[^\"]*\">([^<]*)</d>");

    private final BilibiliClient bilibili;
    private final FrameAnalyzer frames;

    public VideoInsightService(BilibiliClient bilibili, AppConfig.Video videoConfig) {
        this.bilibili = bilibili;
        this.frames = new FrameAnalyzer(videoConfig, bilibili);
    }

    public VideoInsight analyze(String bvid) {
        String encoded = URLEncoder.encode(bvid, StandardCharsets.UTF_8);
        JsonNode infoRoot = bilibili.getJson(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + encoded,
                "https://www.bilibili.com/video/" + bvid);
        if (infoRoot.path("code").asInt(-1) != 0) {
            throw new BilibiliApiException(infoRoot.path("code").asInt(-1), 200,
                    "读取视频信息失败: " + infoRoot.path("message").asText(""));
        }
        JsonNode data = infoRoot.path("data");
        long aid = data.path("aid").asLong();
        long cid = data.path("cid").asLong();
        String title = data.path("title").asText("");
        String author = data.path("owner").path("name").asText("");
        String description = truncate(data.path("desc").asText(""), 1200);

        String subtitles = "";
        String hotDanmaku = "";
        String hotComments = "";
        String frameDescription = "";
        try {
            subtitles = fetchSubtitles(bvid, cid);
        } catch (RuntimeException e) {
            log.debug("读取字幕失败 {}: {}", bvid, e.getMessage());
        }
        try {
            hotDanmaku = fetchHotDanmaku(cid);
        } catch (RuntimeException e) {
            log.debug("读取弹幕失败 {}: {}", bvid, e.getMessage());
        }
        try {
            hotComments = fetchHotComments(aid);
        } catch (RuntimeException e) {
            log.debug("读取热评失败 {}: {}", bvid, e.getMessage());
        }
        try {
            frameDescription = frames.analyze(bvid, cid);
        } catch (RuntimeException e) {
            log.debug("画面理解失败 {}: {}", bvid, e.getMessage());
        }
        return new VideoInsight(bvid, aid, cid, title, author, description,
                subtitles, hotComments, hotDanmaku, frameDescription);
    }

    private String fetchSubtitles(String bvid, long cid) {
        if (cid <= 0) {
            return "";
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", Long.toString(cid));
        JsonNode root = bilibili.signedGet("https://api.bilibili.com/x/player/wbi/v2",
                params, "https://www.bilibili.com/video/" + bvid);
        JsonNode subtitles = root.path("data").path("subtitle").path("subtitles");
        if (!subtitles.isArray() || subtitles.isEmpty()) {
            return "";
        }
        JsonNode chosen = null;
        for (JsonNode item : subtitles) {
            String lan = item.path("lan").asText("");
            if (lan.startsWith("zh") || lan.startsWith("ai-zh")) {
                chosen = item;
                break;
            }
        }
        if (chosen == null) {
            chosen = subtitles.get(0);
        }
        String url = chosen.path("subtitle_url").asText("");
        if (url.isBlank()) {
            return "";
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        String text = bilibili.getText(url, "https://www.bilibili.com/video/" + bvid);
        try {
            JsonNode body = Json.BILIBILI.readTree(text).path("body");
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : body) {
                String content = line.path("content").asText("");
                if (!content.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(content.trim());
                }
            }
            return truncate(sb.toString(), 4000);
        } catch (Exception e) {
            return "";
        }
    }

    private String fetchHotComments(long aid) {
        if (aid <= 0) {
            return "";
        }
        JsonNode root = bilibili.getJson(
                "https://api.bilibili.com/x/v2/reply?type=1&oid=" + aid + "&ps=20&sort=2",
                "https://www.bilibili.com/video/av" + aid);
        JsonNode replies = root.path("data").path("replies");
        List<String> comments = new ArrayList<>();
        if (replies.isArray()) {
            for (JsonNode reply : replies) {
                String text = reply.path("content").path("message").asText("");
                if (!text.isBlank()) {
                    comments.add(text.replaceAll("\\s+", " ").trim());
                }
                if (comments.size() >= 10) {
                    break;
                }
            }
        }
        return truncate(String.join(" / ", comments), 2500);
    }

    private String fetchHotDanmaku(long cid) {
        if (cid <= 0) {
            return "";
        }
        String xml = bilibili.getText("https://api.bilibili.com/x/v1/dm/list.so?oid=" + cid,
                "https://www.bilibili.com/video/av" + cid);
        Matcher matcher = DANMAKU.matcher(xml);
        Map<String, Integer> counts = new LinkedHashMap<>();
        while (matcher.find()) {
            String text = unescape(matcher.group(1)).replaceAll("\\s+", " ").trim();
            if (text.isEmpty() || text.length() > 30) {
                continue;
            }
            counts.merge(text, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(15)
                .map(e -> e.getKey())
                .reduce((a, b) -> a + " / " + b)
                .orElse("");
    }

    private static String unescape(String value) {
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
