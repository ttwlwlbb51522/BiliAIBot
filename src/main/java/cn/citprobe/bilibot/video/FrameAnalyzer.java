package cn.citprobe.bilibot.video;

import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 抽帧 + OpenAI 兼容视觉模型，真正看视频画面。
 */
public final class FrameAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FrameAnalyzer.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final AppConfig.Video config;
    private final BilibiliClient bilibili;
    private final HttpClient http;

    public FrameAnalyzer(AppConfig.Video config, BilibiliClient bilibili) {
        this.config = config;
        this.bilibili = bilibili;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String analyze(String bvid, long cid) {
        if (!config.framesEnabled() || config.visionBaseUrl().isBlank()
                || config.visionApiKey().isBlank() || config.visionModel().isBlank()) {
            return "";
        }
        Path temp = null;
        try {
            String playUrl = bilibili.fetchPlayUrl(bvid, cid);
            temp = Files.createTempDirectory("bili-bot-frame-");
            extractFrames(playUrl, bvid, temp);
            List<Path> frames;
            try (var stream = Files.list(temp)) {
                frames = stream.filter(p -> p.getFileName().toString().endsWith(".jpg"))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .toList();
            }
            if (frames.isEmpty()) {
                log.warn("[画面] 没有抽到帧: {}", bvid);
                return "";
            }
            return describe(bvid, frames);
        } catch (Exception e) {
            log.warn("[画面] 抽帧/视觉分析失败 {}: {}", bvid, e.getMessage());
            return "";
        } finally {
            deleteRecursively(temp);
        }
    }

    private void extractFrames(String playUrl, String bvid, Path dir) throws Exception {
        double fps = Math.max(0.05, (double) config.frameCount() / config.maxSeconds());
        String headers = "Referer: https://www.bilibili.com/video/" + bvid + "\r\n"
                + "Cookie: " + bilibili.cookieHeader() + "\r\n"
                + "User-Agent: " + bilibili.userAgent() + "\r\n";
        List<String> command = new ArrayList<>();
        command.add(config.ffmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.add("-headers");
        command.add(headers);
        command.add("-ss");
        command.add("0");
        command.add("-t");
        command.add(Integer.toString(config.maxSeconds()));
        command.add("-i");
        command.add(playUrl);
        command.add("-vf");
        command.add(String.format(java.util.Locale.ROOT, "fps=%.4f,scale=640:-1", fps));
        command.add("-frames:v");
        command.add(Integer.toString(config.frameCount()));
        command.add("-q:v");
        command.add("3");
        command.add(dir.resolve("frame-%02d.jpg").toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg 超时");
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("ffmpeg 退出码 " + process.exitValue() + ": "
                    + output.substring(0, Math.min(200, output.length())));
        }
    }

    private String describe(String bvid, List<Path> frames) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text",
                "这是 B 站视频 " + bvid + " 的抽帧画面。请用中文简要描述视频内容、人物/场景和发生的事，"
                        + "不要编造画面里没有的信息，最多 300 字。"));
        for (Path frame : frames) {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(frame));
            content.add(Map.of("type", "image_url",
                    "image_url", Map.of("url", "data:image/jpeg;base64," + base64)));
        }
        Map<String, Object> payload = Map.of(
                "model", config.visionModel(),
                "messages", List.of(Map.of("role", "user", "content", content)),
                "max_tokens", 500,
                "temperature", 0.2
        );
        String body = MAPPER.writeValueAsString(payload);
        String url = config.visionBaseUrl().replaceAll("/+$", "");
        if (!url.endsWith("/chat/completions")) {
            url += "/chat/completions";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + config.visionApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("视觉接口 HTTP " + response.statusCode());
        }
        JsonNode root = MAPPER.readTree(response.body());
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        return text.isBlank() ? "" : text.trim();
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
