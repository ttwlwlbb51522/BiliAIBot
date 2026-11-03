package cn.citprobe.bilibot.console;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleCommandTest {

    @Test
    void parsesCommandsAndAliases() {
        assertEquals(ConsoleCommand.STOP, ConsoleCommand.parse("stop"));
        assertEquals(ConsoleCommand.STOP, ConsoleCommand.parse(" STOP "));
        assertEquals(ConsoleCommand.STOP, ConsoleCommand.parse("quit"));
        assertEquals(ConsoleCommand.STOP, ConsoleCommand.parse("shutdown"));
        assertEquals(ConsoleCommand.HELP, ConsoleCommand.parse("help"));
        assertEquals(ConsoleCommand.HELP, ConsoleCommand.parse("?"));
        assertEquals(ConsoleCommand.STATUS, ConsoleCommand.parse("status"));
        assertEquals(ConsoleCommand.VERSION, ConsoleCommand.parse("version"));
        assertEquals(ConsoleCommand.UNKNOWN, ConsoleCommand.parse("reload"));
        assertEquals(ConsoleCommand.UNKNOWN, ConsoleCommand.parse(""));
        assertEquals(ConsoleCommand.UNKNOWN, ConsoleCommand.parse(null));
    }

    @Test
    void formatsUptime() {
        assertEquals("00:00:05", BotConsole.formatDuration(Duration.ofSeconds(5)));
        assertEquals("01:02:03", BotConsole.formatDuration(Duration.ofSeconds(3723)));
        assertEquals("1天 01:00:00", BotConsole.formatDuration(Duration.ofSeconds(90_000)));
    }
}
