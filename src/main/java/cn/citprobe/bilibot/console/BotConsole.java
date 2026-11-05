package cn.citprobe.bilibot.console;

import cn.citprobe.bilibot.BotRunner;
import cn.citprobe.bilibot.draft.Draft;
import cn.citprobe.bilibot.draft.DraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Minecraft 服务端风格的后台控制台：从 stdin 读取命令，输入 stop 安全关闭。
 */
public final class BotConsole {

    private static final Logger log = LoggerFactory.getLogger(BotConsole.class);

    private final BotRunner runner;
    private final Instant startedAt;
    private final DraftService drafts;
    private final boolean interactive;
    private final Thread thread;
    private volatile boolean stopping;

    public BotConsole(BotRunner runner, Instant startedAt) {
        this(runner, startedAt, null);
    }

    public BotConsole(BotRunner runner, Instant startedAt, DraftService drafts) {
        this.runner = runner;
        this.startedAt = startedAt;
        this.drafts = drafts;
        this.interactive = System.console() != null;
        this.thread = new Thread(this::run, "bili-deepseek-console");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void printHelp() {
        System.out.println("""
                可用命令:
                  help     显示此帮助
                  status   查看运行状态和运行时长
                  version  查看程序版本
                  drafts   查看待审草稿
                  drafts approve <id>  批准并发送草稿
                  drafts drop <id>     丢弃草稿
                  stop     安全停止机器人（也可用 shutdown/exit/quit）
                """);
        System.out.flush();
    }

    private void run() {
        if (interactive) {
            printPrompt();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handle(line)) {
                    return;
                }
                if (interactive) {
                    printPrompt();
                }
            }
            log.debug("控制台输入已结束，命令线程退出");
        } catch (IOException e) {
            log.warn("读取控制台命令失败: {}", e.getMessage());
        }
    }

    boolean handle(String line) {
        ConsoleCommand command = ConsoleCommand.parse(line);
        switch (command) {
            case STOP -> {
                stop();
                return false;
            }
            case HELP -> printHelp();
            case STATUS -> printStatus();
            case VERSION -> printVersion();
            case DRAFTS -> handleDrafts(line);
            case UNKNOWN -> {
                if (line != null && !line.isBlank()) {
                    System.out.println("未知命令: " + line.trim() + "，输入 help 查看可用命令");
                    System.out.flush();
                }
            }
        }
        return true;
    }

    private void handleDrafts(String line) {
        if (drafts == null) {
            System.out.println("草稿功能未启用");
            System.out.flush();
            return;
        }
        String[] parts = line == null ? new String[0] : line.trim().split("\\s+");
        if (parts.length <= 1 || "list".equalsIgnoreCase(parts[1])) {
            java.util.List<Draft> list = drafts.list();
            if (list.isEmpty()) {
                System.out.println("没有待审草稿");
            } else {
                for (Draft draft : list) {
                    System.out.println(drafts.describe(draft));
                }
            }
            System.out.flush();
            return;
        }
        if (parts.length < 3) {
            System.out.println("用法: drafts list | drafts approve <id> | drafts drop <id>");
            System.out.flush();
            return;
        }
        String action = parts[1].toLowerCase(Locale.ROOT);
        String id = parts[2];
        boolean ok = switch (action) {
            case "approve" -> drafts.approve(id);
            case "drop", "delete" -> drafts.drop(id);
            default -> false;
        };
        if (!ok) {
            System.out.println("操作未完成，检查 id 或查看日志");
            System.out.flush();
        }
    }

    private void printStatus() {
        Duration uptime = Duration.between(startedAt, Instant.now());
        System.out.printf(Locale.ROOT, "运行状态: %s%n", runner.isRunning() ? "运行中" : "已停止");
        System.out.printf(Locale.ROOT, "运行时长: %s%n", formatDuration(uptime));
        System.out.flush();
    }

    private void printVersion() {
        String version = ConsoleCommand.class.getPackage().getImplementationVersion();
        System.out.println("版本: " + (version == null ? "dev" : version));
        System.out.flush();
    }

    private void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        log.info("收到 stop 命令，正在安全停止机器人...");
        runner.close();
        log.info("机器人已停止");
        System.exit(0);
    }

    private void printPrompt() {
        if (!stopping) {
            System.out.print("> ");
            System.out.flush();
        }
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%d天 %02d:%02d:%02d", days, hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }
}
