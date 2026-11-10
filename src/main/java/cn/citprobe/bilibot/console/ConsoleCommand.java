package cn.citprobe.bilibot.console;

import java.util.Locale;

/**
 * 后台控制台命令。
 */
public enum ConsoleCommand {
    STOP,
    HELP,
    STATUS,
    VERSION,
    DRAFTS,
    BROWSE,
    RELOAD,
    UNKNOWN;

    public static ConsoleCommand parse(String input) {
        if (input == null) {
            return UNKNOWN;
        }
        String command = input.trim().toLowerCase(Locale.ROOT);
        if (command.equals("drafts") || command.startsWith("drafts ")) {
            return DRAFTS;
        }
        if (command.equals("browse") || command.startsWith("browse ")) {
            return BROWSE;
        }
        return switch (command) {
            case "stop", "shutdown", "exit", "quit" -> STOP;
            case "help", "?" -> HELP;
            case "status" -> STATUS;
            case "version", "ver" -> VERSION;
            case "reload", "reload-config" -> RELOAD;
            default -> UNKNOWN;
        };
    }
}
