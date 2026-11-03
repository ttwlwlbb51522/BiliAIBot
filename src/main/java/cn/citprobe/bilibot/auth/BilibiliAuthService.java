package cn.citprobe.bilibot.auth;

import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.util.Json;
import cn.citprobe.bilibot.util.TerminalQrCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * B 站登录管理器。
 *
 * 优先使用会话缓存；其次使用配置的 Cookie；最后使用账号密码登录。
 * B 站密码登录强制要求极验人机验证，因此首次密码登录需要在交互式终端完成一次验证。
 */
public final class BilibiliAuthService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliAuthService.class);

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String CAPTCHA_URL =
            "https://passport.bilibili.com/x/passport-login/captcha?source=main_web";
    private static final String KEY_URL = "https://passport.bilibili.com/x/passport-login/web/key";
    private static final String LOGIN_URL = "https://passport.bilibili.com/x/passport-login/web/login";
    private static final String QR_GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String QR_POLL_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String CAPTCHA_HELPER_URL = "https://kuresaru.github.io/geetest-validator/";

    private final AppConfig.Bilibili config;
    private final SessionStore sessionStore;
    private final HttpClient http;
    private final ObjectMapper mapper = Json.BILIBILI;
    private final BufferedReader stdin;

    public BilibiliAuthService(AppConfig.Bilibili config, java.nio.file.Path sessionFile) {
        this.config = config;
        this.sessionStore = new SessionStore(sessionFile);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(config.requestTimeoutSeconds(), 20)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /**
     * 返回可直接用于后续 B 站接口的 Cookie 字符串。
     */
    public String authenticate() {
        Optional<String> cached = sessionStore.load();
        if (cached.isPresent()) {
            if (isValid(cached.get())) {
                log.info("使用已缓存的 B 站登录会话");
                return cached.get();
            }
            log.warn("缓存的登录会话已失效，将重新登录");
            sessionStore.clear();
        }

        if (!config.cookie().isBlank() && isValid(config.cookie())) {
            log.info("使用配置的 bilibili.cookie 登录");
            sessionStore.save(config.cookie());
            return config.cookie();
        }

        String loginType = config.loginType();
        if ("cookie".equals(loginType)) {
            throw new BilibiliAuthException("B 站登录凭据不可用：缓存的会话和配置的 Cookie 均已失效。"
                    + "请更新 bilibili.cookie，或改用 bilibili.login-type=auto/qrcode");
        }
        if ("password".equals(loginType)
                || ("auto".equals(loginType) && hasPasswordCredentials())) {
            return finishLogin(loginWithPassword(), "账号密码登录");
        }
        if ("qrcode".equals(loginType) || "auto".equals(loginType)) {
            return finishLogin(loginWithQrCode(), "二维码登录");
        }
        throw new BilibiliAuthException("不支持的 bilibili.login-type: " + loginType);
    }

    private String finishLogin(String cookie, String method) {
        if (!isValid(cookie)) {
            throw new BilibiliAuthException(method + "后 Cookie 校验失败，请稍后重试或改用 Cookie 登录");
        }
        sessionStore.save(cookie);
        log.info("{}成功，已缓存登录会话", method);
        return cookie;
    }

    private boolean hasPasswordCredentials() {
        return !config.username().isBlank() && !config.password().isBlank();
    }

    private boolean isValid(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return false;
        }
        JsonNode root = getJsonWithCookie(NAV_URL, cookie, "https://www.bilibili.com/");
        int code = root.path("code").asInt(0);
        if (code == -101) {
            return false;
        }
        if (code != 0) {
            throw new BilibiliAuthException("校验 B 站登录状态失败: code=" + code
                    + ", message=" + root.path("message").asText(""), code);
        }
        return root.path("data").path("isLogin").asBoolean(false)
                && root.path("data").path("mid").asLong(0) > 0;
    }

    private String loginWithQrCode() {
        boolean runningUnderSystemd = System.getenv("INVOCATION_ID") != null
                || System.getenv("JOURNAL_STREAM") != null;
        if (System.console() == null && runningUnderSystemd) {
            throw new BilibiliAuthException("systemd 后台服务无法完成二维码登录。"
                    + "请先在 SSH 终端手动运行一次完成扫码，或配置 bilibili.cookie 用于无人值守服务器");
        }
        log.info("开始使用 B 站二维码登录");
        CookieJar jar = new CookieJar();
        prepareBuvid(jar);

        JsonNode generated = getJsonWithJar(QR_GENERATE_URL, jar, "https://www.bilibili.com/");
        if (generated.path("code").asInt(0) != 0) {
            throw new BilibiliAuthException("获取登录二维码失败: "
                    + generated.path("message").asText(""), generated.path("code").asInt(0));
        }
        String qrUrl = generated.path("data").path("url").asText("");
        String qrKey = generated.path("data").path("qrcode_key").asText("");
        if (qrUrl.isBlank() || qrKey.isBlank()) {
            throw new BilibiliAuthException("获取登录二维码失败：响应缺少 url 或 qrcode_key");
        }

        System.out.println();
        System.out.println("请使用 B 站手机客户端扫描下方二维码：");
        TerminalQrCode.print(qrUrl);
        try {
            java.nio.file.Path image = TerminalQrCode.savePng(
                    qrUrl, java.nio.file.Path.of("data", "login-qrcode.png"));
            System.out.println("二维码图片已保存: " + image);
        } catch (IOException | RuntimeException e) {
            log.warn("保存二维码图片失败，请直接扫描终端二维码: {}", e.getMessage());
        }
        System.out.println("二维码有效期约 180 秒，扫码后请在手机上确认登录。");
        System.out.flush();

        long deadline = System.nanoTime() + 180_000_000_000L;
        boolean scanned = false;
        while (System.nanoTime() < deadline) {
            sleep(2_000);
            String pollUrl = QR_POLL_URL + "?qrcode_key="
                    + URLEncoder.encode(qrKey, StandardCharsets.UTF_8);
            JsonNode poll = getJsonWithJar(pollUrl, jar, "https://www.bilibili.com/");
            if (poll.path("code").asInt(0) != 0) {
                throw new BilibiliAuthException("查询扫码状态失败: "
                        + poll.path("message").asText(""), poll.path("code").asInt(0));
            }
            JsonNode data = poll.path("data");
            int status = data.path("code").asInt(-1);
            if (status == 0) {
                jar.captureUrl(data.path("url").asText(""));
                if (!jar.contains("SESSDATA") || !jar.contains("bili_jct")) {
                    throw new BilibiliAuthException("扫码成功但未获取到有效 Cookie，请重试或改用 Cookie 登录");
                }
                System.out.println("扫码登录成功。");
                System.out.flush();
                log.info("B 站扫码登录成功");
                return jar.header();
            }
            if (status == 86038) {
                throw new BilibiliAuthException("二维码已过期，请重新启动程序生成新的二维码");
            }
            if (status == 86090 && !scanned) {
                scanned = true;
                System.out.println("已扫码，请在手机上确认登录。");
                System.out.flush();
            }
        }
        throw new BilibiliAuthException("二维码登录超时，请重新启动程序生成新的二维码");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BilibiliAuthException("等待扫码时被中断", e);
        }
    }

    private String loginWithPassword() {
        log.info("开始使用 B 站账号密码登录: {}", maskUsername(config.username()));
        CookieJar jar = new CookieJar();
        prepareBuvid(jar);

        CaptchaData captcha = requestCaptcha(jar);
        CaptchaAnswer answer = promptForCaptcha(captcha);

        KeyData keyData = requestKey(jar);
        String encryptedPassword;
        try {
            encryptedPassword = PasswordEncryptor.encrypt(keyData.hash(), keyData.publicKey(), config.password());
        } catch (IllegalArgumentException e) {
            throw new BilibiliAuthException("加密登录密码失败: " + e.getMessage(), e);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", config.username());
        form.put("password", encryptedPassword);
        form.put("keep", "0");
        form.put("token", captcha.token());
        form.put("challenge", captcha.challenge());
        form.put("validate", answer.validate());
        form.put("seccode", answer.seccode());
        form.put("source", "main_web");
        form.put("go_url", "https://www.bilibili.com/");

        JsonNode root = postForm(LOGIN_URL, form, jar);
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw loginError(code, root.path("message").asText(""));
        }

        JsonNode data = root.path("data");
        int status = data.path("status").asInt(0);
        String loginUrl = data.path("url").asText("");
        String message = data.path("message").asText("");
        jar.captureUrl(loginUrl);

        if (!jar.contains("SESSDATA") || !jar.contains("bili_jct")) {
            if (!loginUrl.isBlank()) {
                printManualVerification(loginUrl, message, status);
                collectManualCookie(jar);
            }
            if (jar.contains("SESSDATA") && jar.contains("bili_jct")) {
                log.info("手动验证完成，已获取登录 Cookie");
                return jar.header();
            }
            String detail = message.isBlank() ? "登录未返回有效 Cookie" : message;
            if (status == 2) {
                detail += "；本次登录环境存在风险，需要在手机端/浏览器完成验证";
            }
            if (!loginUrl.isBlank()) {
                detail += "；验证地址: " + loginUrl;
            }
            throw new BilibiliAuthException(detail + "。完成验证后也可改用 bilibili.cookie 登录", code);
        }

        log.info("B 站密码登录响应成功（status={}）", status);
        return jar.header();
    }

    /**
     * 密码登录后 B 站可能要求二次验证（手机号/邮箱/风险验证）。
     * 将 B 站返回的验证地址打印到控制台，等待用户手动完成后继续。
     */
    private void printManualVerification(String url, String message, int status) {
        String separator = "============================================================";
        System.out.println();
        System.out.println(separator);
        System.out.println("B 站要求完成登录后的安全验证");
        if (message != null && !message.isBlank()) {
            System.out.println("说明: " + message);
        }
        if (status == 2) {
            System.out.println("本次登录环境存在风险，请完成手机号/邮箱验证。");
        }
        System.out.println("请在浏览器中打开以下链接并手动完成验证：");
        System.out.println(url);
        System.out.println();
        System.out.println("验证完成后，程序需要拿到登录 Cookie 才能继续。");
        System.out.println("请选择以下任一方式：");
        System.out.println("  A. 粘贴验证完成后浏览器最终跳转的完整 URL（若其中带 SESSDATA）；");
        System.out.println("  B. 按 F12 打开开发者工具，复制 bilibili.com 的完整 Cookie 后粘贴；");
        System.out.println("  C. 直接回车退出，稍后改用 bilibili.cookie 登录。");
        System.out.println(separator);
        System.out.flush();
        log.warn("B 站登录需要手动安全验证，请在控制台查看链接并完成");
        openBrowser(url);
    }

    private void collectManualCookie(CookieJar jar) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String input = readLine("粘贴最终 URL 或 Cookie（直接回车退出）: ");
            if (input == null || input.isBlank()) {
                return;
            }
            String value = input.trim();
            if (value.regionMatches(true, 0, "Cookie:", 0, 7)) {
                value = value.substring(7).trim();
            }
            if (value.startsWith("http://") || value.startsWith("https://")) {
                jar.captureUrl(value);
            } else {
                jar.mergeHeader(value);
            }
            if (jar.contains("SESSDATA") && jar.contains("bili_jct")) {
                return;
            }
            System.out.println("未从输入中识别到 SESSDATA 和 bili_jct，请重试。");
        }
    }

    private void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                System.out.println("已尝试在默认浏览器中打开验证页面。");
            }
        } catch (Exception e) {
            log.debug("自动打开验证页面失败，请手动复制链接: {}", e.getMessage());
        }
    }

    private void prepareBuvid(CookieJar jar) {
        jar.put("buvid3", UUID.randomUUID().toString().toUpperCase(Locale.ROOT) + "infoc");
        jar.put("b_nut", Long.toString(System.currentTimeMillis() / 1000));
        try {
            JsonNode root = getJsonWithJar(SPI_URL, jar, "https://www.bilibili.com/");
            JsonNode data = root.path("data");
            String b3 = data.path("b_3").asText("");
            String b4 = data.path("b_4").asText("");
            if (!b3.isBlank()) {
                jar.put("buvid3", b3);
            }
            if (!b4.isBlank()) {
                jar.put("buvid4", b4);
            }
            log.debug("已获取 B 站设备指纹 buvid");
        } catch (RuntimeException e) {
            log.debug("获取 buvid 失败，使用本地生成值: {}", e.getMessage());
        }
    }

    private CaptchaData requestCaptcha(CookieJar jar) {
        JsonNode root = getJsonWithJar(CAPTCHA_URL, jar, "https://passport.bilibili.com/login");
        if (root.path("code").asInt(0) != 0) {
            throw new BilibiliAuthException("获取登录验证码失败: " + root.path("message").asText(""),
                    root.path("code").asInt(0));
        }
        JsonNode data = root.path("data");
        String token = data.path("token").asText("");
        String gt = data.path("geetest").path("gt").asText("");
        String challenge = data.path("geetest").path("challenge").asText("");
        if (token.isBlank() || gt.isBlank() || challenge.isBlank()) {
            throw new BilibiliAuthException("登录验证码响应缺少 token/gt/challenge，请稍后重试");
        }
        return new CaptchaData(token, gt, challenge);
    }

    private KeyData requestKey(CookieJar jar) {
        JsonNode root = getJsonWithJar(KEY_URL, jar, "https://passport.bilibili.com/login");
        if (root.path("code").asInt(0) != 0) {
            throw new BilibiliAuthException("获取登录公钥失败: " + root.path("message").asText(""),
                    root.path("code").asInt(0));
        }
        String hash = root.path("data").path("hash").asText("");
        String key = root.path("data").path("key").asText("");
        if (hash.isBlank() || key.isBlank()) {
            throw new BilibiliAuthException("登录公钥响应缺少 hash/key，请稍后重试");
        }
        return new KeyData(hash, key);
    }

    private CaptchaAnswer promptForCaptcha(CaptchaData captcha) {
        String separator = "============================================================";
        System.out.println();
        System.out.println(separator);
        System.out.println("B 站账号密码登录需要进行一次人机验证（极验）");
        System.out.println("1. 用浏览器打开: " + CAPTCHA_HELPER_URL);
        System.out.println("2. 在网页中填入以下参数：");
        System.out.println("   gt        = " + captcha.gt());
        System.out.println("   challenge = " + captcha.challenge());
        System.out.println("3. 完成滑动/点击验证，复制网页生成的 validate 和 seccode");
        System.out.println("4. 回到本终端，依次粘贴 validate 和 seccode 并回车");
        System.out.println("提示：验证码页面只用于人机验证，不要在该页面输入 B 站账号密码。");
        System.out.println("     验证码有效期有限，超时请重新启动程序。");
        System.out.println(separator);
        System.out.flush();

        String validate = readLine("validate: ");
        String seccode = readLine("seccode: ");
        if (validate == null || validate.isBlank() || seccode == null || seccode.isBlank()) {
            throw new BilibiliAuthException("未读取到人机验证结果。"
                    + "请在交互式终端运行一次，或改用 bilibili.cookie 登录");
        }
        return new CaptchaAnswer(
                validate.trim().replace("%7C", "|").replace("%7c", "|"),
                seccode.trim().replace("%7C", "|").replace("%7c", "|")
        );
    }

    private String readLine(String prompt) {
        try {
            System.out.print(prompt);
            System.out.flush();
            return stdin.readLine();
        } catch (IOException e) {
            throw new BilibiliAuthException("读取人机验证结果失败: " + e.getMessage(), e);
        }
    }

    private BilibiliAuthException loginError(int code, String message) {
        String hint;
        switch (code) {
            case -105 -> hint = "人机验证失败或已过期，请重新启动并再次验证";
            case -629 -> hint = "账号或密码错误，或该账号触发了登录风控";
            case -653 -> hint = "账号或密码不能为空";
            case -662 -> hint = "提交超时，请重试";
            case -2100 -> hint = "需要先完成手机号或邮箱验证";
            case 2400 -> hint = "登录密钥错误，请重试";
            case 2406 -> hint = "极验服务出错，请稍后重试";
            case 86000 -> hint = "RSA 解密失败，请检查密码是否包含特殊字符";
            default -> hint = "B 站登录失败";
        }
        String detail = message == null || message.isBlank() ? "" : "（" + message + "）";
        return new BilibiliAuthException(hint + detail + "，错误码 " + code, code);
    }

    private JsonNode getJsonWithJar(String url, CookieJar jar, String referer) {
        HttpRequest.Builder builder = requestBuilder(url, referer);
        String cookie = jar.header();
        if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        return execute(builder.GET().build(), jar);
    }

    private JsonNode getJsonWithCookie(String url, String cookie, String referer) {
        HttpRequest.Builder builder = requestBuilder(url, referer);
        builder.header("Cookie", cookie);
        return execute(builder.GET().build(), null);
    }

    private JsonNode postForm(String url, Map<String, String> form, CookieJar jar) {
        HttpRequest.Builder builder = requestBuilder(url, "https://passport.bilibili.com/login")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Origin", "https://passport.bilibili.com");
        String cookie = jar.header();
        if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        return execute(builder.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form),
                StandardCharsets.UTF_8)).build(), jar);
    }

    private HttpRequest.Builder requestBuilder(String url, String referer) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("User-Agent", config.userAgent())
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "identity");
    }

    private JsonNode execute(HttpRequest request, CookieJar jar) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BilibiliAuthException("B 站登录请求被中断", e);
        } catch (IOException e) {
            throw new BilibiliAuthException("访问 B 站登录接口失败: " + e.getMessage(), e);
        }
        if (jar != null) {
            jar.capture(response);
        }
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new BilibiliAuthException("B 站登录接口返回空响应（HTTP " + status + "）");
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (status >= 400) {
                throw new BilibiliAuthException("B 站登录接口 HTTP " + status + ": "
                        + root.path("message").asText(""), root.path("code").asInt(0));
            }
            return root;
        } catch (IOException e) {
            throw new BilibiliAuthException("B 站登录接口返回非 JSON 内容（HTTP " + status + "）", e);
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

    private static String maskUsername(String username) {
        if (username == null || username.length() <= 3) {
            return "***";
        }
        if (username.contains("@")) {
            int at = username.indexOf('@');
            return username.charAt(0) + "***" + username.substring(at);
        }
        return username.substring(0, 3) + "****";
    }

    private record CaptchaData(String token, String gt, String challenge) {
    }

    private record KeyData(String hash, String publicKey) {
    }

    private record CaptchaAnswer(String validate, String seccode) {
    }
}
