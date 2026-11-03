package cn.citprobe.bilibot.util;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 自动识别运行环境的控制台编码，避免中文在 Windows GBK 控制台或 Linux UTF-8 终端中乱码。
 *
 * 识别顺序：stdout.encoding -> sun.stdout.encoding -> native.encoding -> sun.jnu.encoding。
 * Linux 无控制台（systemd/journal）时固定使用 UTF-8。
 */
public final class NativeConsoleEncoding {

    private NativeConsoleEncoding() {
    }

    public static Charset detect() {
        String override = System.getProperty("console.encoding");
        if (override == null || override.isBlank()) {
            override = System.getenv("CONSOLE_ENCODING");
        }
        Charset forced = normalize(override);
        if (forced != null) {
            return forced;
        }

        // 重定向到文件、管道、IDE、systemd/journal 时统一使用 UTF-8；
        // 只有真正连接了控制台时才按系统编码输出。
        if (System.console() == null) {
            return StandardCharsets.UTF_8;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        String[] candidates = {
                System.getProperty("stdout.encoding"),
                System.getProperty("sun.stdout.encoding"),
                System.getProperty("native.encoding"),
                System.getProperty("sun.jnu.encoding"),
                System.getProperty("file.encoding")
        };
        for (String candidate : candidates) {
            Charset charset = normalize(candidate);
            if (charset != null && !isAscii(charset)) {
                return charset;
            }
        }
        // 中文 Windows 的默认控制台代码页通常是 936。
        return windows ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    /**
     * 安装控制台输出流，并让 logback 的控制台 appender 使用同一编码。
     */
    public static void install() {
        Charset charset = detect();
        System.out.flush();
        System.err.flush();
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset));
    }

    static Charset normalize(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = name.trim().toUpperCase(Locale.ROOT);
        if (value.contains("UTF-8") || value.contains("UTF8") || value.contains("65001")) {
            return StandardCharsets.UTF_8;
        }
        if (value.contains("936") || value.contains("GBK") || value.contains("GB2312")) {
            return Charset.forName("GBK");
        }
        if (value.contains("US-ASCII") || value.contains("ANSI_X3.4") || value.equals("ASCII")) {
            return StandardCharsets.US_ASCII;
        }
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isAscii(Charset charset) {
        return StandardCharsets.US_ASCII.equals(charset);
    }
}
