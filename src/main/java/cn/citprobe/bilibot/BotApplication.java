package cn.citprobe.bilibot;

import cn.citprobe.bilibot.admin.AdminServer;
import cn.citprobe.bilibot.auth.BilibiliAuthException;
import cn.citprobe.bilibot.auth.BilibiliAuthService;
import cn.citprobe.bilibot.bilibili.BilibiliApiException;
import cn.citprobe.bilibot.bilibili.BilibiliClient;
import cn.citprobe.bilibot.bilibili.BotIdentity;
import cn.citprobe.bilibot.bilibili.TrafficGuard;
import cn.citprobe.bilibot.browse.SelfBrowseService;
import cn.citprobe.bilibot.config.AppConfig;
import cn.citprobe.bilibot.config.ConfigException;
import cn.citprobe.bilibot.config.ConfigLoader;
import cn.citprobe.bilibot.console.BotConsole;
import cn.citprobe.bilibot.deepseek.DeepSeekClient;
import cn.citprobe.bilibot.draft.DraftService;
import cn.citprobe.bilibot.live.LiveDanmakuService;
import cn.citprobe.bilibot.notify.DailyStats;
import cn.citprobe.bilibot.notify.NotifyService;
import cn.citprobe.bilibot.pm.PrivateMessageService;
import cn.citprobe.bilibot.recommend.RecommendationService;
import cn.citprobe.bilibot.service.BotService;
import cn.citprobe.bilibot.service.PersonaFile;
import cn.citprobe.bilibot.service.PromptBuilder;
import cn.citprobe.bilibot.state.StateStore;
import cn.citprobe.bilibot.util.NativeConsoleEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

public final class BotApplication {

    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    private BotApplication() {
    }

    public static void main(String[] args) {
        NativeConsoleEncoding.install();
        String version = BotApplication.class.getPackage().getImplementationVersion();
        log.info("Bilibili DeepSeek Bot {}（控制台编码: {}）",
                version == null ? "dev" : version,
                NativeConsoleEncoding.detect().name());
        Path configPath = ConfigLoader.resolvePath(requestedConfigPath(args));
        try {
            AppConfig config = ConfigLoader.loadResolved(configPath);
            PersonaFile.ensure(config.reply().systemPromptFile());
            start(config, configPath);
        } catch (ConfigException e) {
            log.error("启动失败：{}", e.getMessage());
            log.error("请检查 {} 或环境变量配置。", configPath.toAbsolutePath());
            System.exit(2);
        } catch (BilibiliAuthException e) {
            log.error("启动失败：B 站登录失败。{}", e.getMessage());
            log.error("可改用 bilibili.cookie 登录，或根据提示完成一次人机验证。");
            System.exit(2);
        } catch (BilibiliApiException e) {
            log.error("启动失败：无法使用登录后的 Cookie 访问 B 站。{}", e.getMessage());
            log.error("请检查账号登录状态，或改用包含 SESSDATA 和 bili_jct 的完整 Cookie。");
            System.exit(2);
        } catch (Exception e) {
            log.error("启动失败：{}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void start(AppConfig config, Path configPath) {
        BilibiliAuthService authService = new BilibiliAuthService(
                config.bilibili(), config.app().sessionFile());
        String runtimeCookie = authService.authenticate();
        TrafficGuard trafficGuard = new TrafficGuard(config.risk());
        BilibiliClient bilibili = new BilibiliClient(
                config.bilibili().withCookie(runtimeCookie), trafficGuard);
        BotIdentity bot = bilibili.whoAmI();
        if (bot.mid() == config.bilibili().ownerUid()) {
            throw new ConfigException("bilibili.owner-uid 不能等于机器人账号自己的 UID");
        }

        DeepSeekClient deepSeek = new DeepSeekClient(config.deepseek());
        StateStore state = new StateStore(config.app().stateFile());
        DailyStats dailyStats = new DailyStats(
                config.app().stateFile().resolveSibling("daily-stats.json"));
        NotifyService notifyService = new NotifyService(config.notification(), dailyStats);
        PromptBuilder promptBuilder = new PromptBuilder(config.reply(), config.bilibili(), bot);
        DraftService draftService = new DraftService(config, bilibili, dailyStats, notifyService);
        BotService service = new BotService(config, bilibili, deepSeek, state, bot,
                promptBuilder, draftService, dailyStats);
        RecommendationService recommendationService = new RecommendationService(
                config, bilibili, deepSeek, dailyStats);
        PrivateMessageService privateMessageService = new PrivateMessageService(
                config, bilibili, deepSeek, bot, dailyStats);
        LiveDanmakuService liveService = new LiveDanmakuService(
                config, bilibili, deepSeek, bot, dailyStats);
        SelfBrowseService browseService = new SelfBrowseService(
                config, bilibili, deepSeek, bot, dailyStats, notifyService, draftService);
        BotRunner runner = new BotRunner(config, service, recommendationService,
                privateMessageService, notifyService, liveService, browseService);
        AdminServer adminServer = null;
        if (config.admin().enabled()) {
            try {
                adminServer = new AdminServer(config, bot, draftService, browseService, dailyStats, configPath);
                adminServer.start();
            } catch (Exception e) {
                log.error("[管理] Android 控制接口启动失败: {}", e.getMessage());
            }
        }
        AdminServer admin = adminServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runner.close();
            if (admin != null) {
                admin.close();
            }
        }, "bili-deepseek-shutdown"));

        log.info("机器人账号：{}（UID {}）", bot.uname().isBlank() ? "(未知昵称)" : bot.uname(), bot.mid());
        log.info("主人账号 UID：{}", config.bilibili().ownerUid());
        log.info("模型：{}，轮询间隔：{}s，状态文件：{}",
                config.deepseek().model(),
                config.bilibili().pollIntervalSeconds(),
                config.app().stateFile());
        if (config.bilibili().dryRun()) {
            log.warn("当前为 DRY-RUN 模式：只生成回复并写入日志，不会真的发表评论。"
                    + "确认效果后设置 bilibili.dry-run=false。");
        } else {
            log.warn("已开启真实回复模式，请遵守 B 站社区规范并控制使用频率。");
        }
        log.info("开始监听 @ 消息。输入 help 查看控制台命令，输入 stop 安全退出。");
        Instant startedAt = Instant.now();
        runner.start();
        new BotConsole(runner, startedAt, draftService, browseService).start();
    }

    private static Path requestedConfigPath(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0]);
        }
        String systemProperty = System.getProperty("bot.config");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return Paths.get(systemProperty);
        }
        return null;
    }
}
