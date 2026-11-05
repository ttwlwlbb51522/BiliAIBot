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
    UNKNOWN;

    public static ConsoleCommand parse(String input) {
        if (input == null) {
            return UNKNOWN;
        }
        String command = input.trim().toLowerCase(Locale.ROOT);
        if (command.equals("drafts") || command.startsWith("drafts ")) {
            return DRAFTS;
        }
        return switch (command) {
            case "stop", "shutdown", "exit", "quit" -> STOP;
            case "help", "?" -> HELP;
            case "status" -> STATUS;
            case "version", "ver" -> VERSION;
            default -> UNKNOWN;
        };
    }
}
