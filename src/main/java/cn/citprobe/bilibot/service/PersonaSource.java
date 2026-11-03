package cn.citprobe.bilibot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 人设文件的热加载。每个 @ 事件构造 Prompt 前检查文件是否变化，修改后无需重启。
 */
public final class PersonaSource {

    private static final Logger log = LoggerFactory.getLogger(PersonaSource.class);

    private final Path path;
    private volatile String content;
    private volatile long lastModified;
    private volatile long lastSize;

    public PersonaSource(Path path) {
        this.path = path;
        PersonaFile.ensure(path);
        this.content = readOrDefault();
        updateStamp();
        if (path != null && Files.isRegularFile(path)) {
            log.info("已加载人设文件: {}", path);
        } else {
            log.info("使用 jar 内置人设");
        }
    }

    public String get() {
        if (path == null) {
            return content;
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            if (modified != lastModified || size != lastSize) {
                synchronized (this) {
                    if (modified != lastModified || size != lastSize) {
                        String updated = readOrDefault();
                        content = updated;
                        lastModified = modified;
                        lastSize = size;
                        log.info("检测到人设文件变化，已重新加载: {}", path);
                    }
                }
            }
        } catch (IOException e) {
            // 文件被删除/临时不可读时继续使用上一次成功内容。
        }
        return content;
    }

    private String readOrDefault() {
        if (path != null) {
            try {
                if (Files.isRegularFile(path)) {
                    String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                    log.warn("人设文件为空，使用 jar 内置人设: {}", path);
                }
            } catch (IOException e) {
                log.warn("读取人设文件失败，使用 jar 内置人设: {}", path, e);
            }
        }
        return PersonaFile.bundled();
    }

    private void updateStamp() {
        if (path == null) {
            return;
        }
        try {
            lastModified = Files.getLastModifiedTime(path).toMillis();
            lastSize = Files.size(path);
        } catch (IOException ignored) {
            lastModified = 0;
            lastSize = -1;
        }
    }
}
