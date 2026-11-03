package cn.citprobe.bilibot.state;

import java.util.List;

public record BotState(
        int version,
        boolean initialized,
        List<Long> processedEventIds,
        List<Long> processedSourceIds,
        String updatedAt
) {
    public static BotState empty() {
        return new BotState(1, false, List.of(), List.of(), null);
    }
}
