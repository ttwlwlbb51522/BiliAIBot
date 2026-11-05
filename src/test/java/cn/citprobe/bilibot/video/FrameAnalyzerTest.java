package cn.citprobe.bilibot.video;

import cn.citprobe.bilibot.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameAnalyzerTest {

    @Test
    void disabledAnalyzerDoesNothing() {
        AppConfig.Video config = new AppConfig.Video(
                false, "ffmpeg", 6, 30, "", "", "");
        FrameAnalyzer analyzer = new FrameAnalyzer(config, null);
        assertEquals("", analyzer.analyze("BV1xx", 1L));
    }
}
