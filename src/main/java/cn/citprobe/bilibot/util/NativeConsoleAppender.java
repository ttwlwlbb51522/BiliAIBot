package cn.citprobe.bilibot.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

/**
 * 控制台日志按终端实际编码输出，文件日志仍保持 UTF-8。
 */
public final class NativeConsoleAppender extends ConsoleAppender<ILoggingEvent> {

    @Override
    public void start() {
        if (getEncoder() instanceof LayoutWrappingEncoder<?> encoder) {
            encoder.setCharset(NativeConsoleEncoding.detect());
        }
        super.start();
    }
}
