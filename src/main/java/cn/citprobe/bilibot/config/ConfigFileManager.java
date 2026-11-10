package cn.citprobe.bilibot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理 jar 同级的 application.properties：
 * 1. 首次运行自动生成带注释的完整模板；
 * 2. 每次启动补齐缺失的键、修复空值（使用模板默认值）；
 * 3. 不覆盖用户已经填写的值。
 */
final class ConfigFileManager {

    static final String FILE_NAME = "application.properties";
    private static final String TEMPLATE_RESOURCE = "/config-template.properties";
    private static final Logger log = LoggerFactory.getLogger(ConfigFileManager.class);

    private ConfigFileManager() {
    }

    static Path resolvePath(Path requested) {
        if (requested != null) {
            return requested.toAbsolutePath().normalize();
        }
        Path codeSource = codeSourcePath();
        if (codeSource != null && Files.isRegularFile(codeSource)
                && codeSource.getFileName().toString().toLowerCase().endsWith(".jar")) {
            Path parent = codeSource.getParent();
            if (parent != null) {
                return parent.resolve(FILE_NAME).toAbsolutePath().normalize();
            }
        }
        return Paths.get(FILE_NAME).toAbsolutePath().normalize();
    }

    static PrepareResult prepare(Path file) {
        Path path = file.toAbsolutePath().normalize();
        try {
            if (Files.isDirectory(path)) {
                throw new ConfigException("配置路径是目录而不是文件: " + path);
            }
            if (!Files.exists(path)) {
                writeAtomically(path, templateText());
                log.info("首次运行，已自动生成配置文件: {}", path);
                log.info("请填写 bilibili.owner-uid、deepseek.api-key，"
                        + "并按需配置登录：账号密码、Cookie，或保持登录信息为空使用二维码登录。");
                return new PrepareResult(true, templateKeys().size(), 0);
            }

            String content = stripBom(Files.readString(path, StandardCharsets.UTF_8));
            if (content.isBlank()) {
                writeAtomically(path, templateText());
                log.info("配置文件为空，已重新生成完整配置: {}", path);
                return new PrepareResult(true, templateKeys().size(), 0);
            }

            BackfillResult result = backfill(content, templateDefaults());
            if (result.changed()) {
                writeAtomically(path, result.content());
                log.info("已自动补全配置文件 {}：新增 {} 项，修复空值 {} 项",
                        path, result.addedKeys().size(), result.filledKeys().size());
                if (!result.addedKeys().isEmpty()) {
                    log.info("新增配置项: {}", String.join(", ", result.addedKeys()));
                }
                if (!result.filledKeys().isEmpty()) {
                    log.info("修复空值配置项: {}", String.join(", ", result.filledKeys()));
                }
                if (result.addedKeys().isEmpty() && result.filledKeys().isEmpty()) {
                    log.info("配置项顺序和注释已按内置模板重新对齐");
                }
            }
            return new PrepareResult(false, result.addedKeys().size(), result.filledKeys().size());
        } catch (IOException e) {
            throw new ConfigException("初始化配置文件失败: " + path + "（" + e.getMessage() + "）", e);
        }
    }

    static Properties templateDefaults() {
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(templateText())) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigException("读取内置配置模板失败", e);
        }
        return properties;
    }

    static String templateText() {
        try (InputStream in = ConfigFileManager.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("缺少内置配置模板: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("读取内置配置模板失败: " + TEMPLATE_RESOURCE, e);
        }
    }

    private static BackfillResult backfill(String content, Properties defaults) {
        Properties existing = new Properties();
        try (StringReader reader = new StringReader(content)) {
            existing.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("解析配置文件失败，请检查是否有非法的转义字符", e);
        }

        List<String> added = new ArrayList<>();
        List<String> filled = new ArrayList<>();
        for (String key : templateKeys()) {
            String defaultValue = defaults.getProperty(key, "");
            String current = existing.getProperty(key);
            if (current == null) {
                added.add(key);
            } else if (current.isBlank() && !defaultValue.isBlank()) {
                filled.add(key);
            }
        }

        // 以模板为唯一结构来源：缺失项回到所属章节，并保留模板注释。
        List<String> merged = new ArrayList<>();
        Set<String> templateKeys = new LinkedHashSet<>(templateKeys());
        for (String templateLine : templateText().split("\\R", -1)) {
            String trimmed = templateLine.trim();
            int separator = trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")
                    ? -1 : indexOfSeparator(trimmed);
            if (separator <= 0) {
                merged.add(templateLine);
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String defaultValue = defaults.getProperty(key, "");
            String current = existing.getProperty(key);
            String value = current == null || (current.isBlank() && !defaultValue.isBlank())
                    ? defaultValue : current;
            merged.add(key + "=" + escapeValue(value));
        }

        List<String> custom = new ArrayList<>();
        for (String key : existing.stringPropertyNames()) {
            if (!templateKeys.contains(key)) {
                custom.add(key + "=" + escapeValue(existing.getProperty(key, "")));
            }
        }
        if (!custom.isEmpty()) {
            custom.sort(String::compareTo);
            if (!merged.isEmpty() && !merged.get(merged.size() - 1).isBlank()) {
                merged.add("");
            }
            merged.add("# ===== 用户自定义配置 =====");
            merged.addAll(custom);
        }

        String result = String.join("\n", merged);
        if (!result.endsWith("\n")) {
            result += "\n";
        }
        boolean changed = !result.equals(content);
        return new BackfillResult(changed, result, List.copyOf(added), List.copyOf(filled));
    }

    private static boolean replaceValue(List<String> lines, String key, String value) {
        Pattern pattern = Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*[=:]\\s*).*$");
        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.matches()) {
                lines.set(i, matcher.group(1) + escapeValue(value));
                return true;
            }
        }
        return false;
    }

    private static List<String> templateKeys() {
        List<String> keys = new ArrayList<>();
        for (String line : templateText().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = indexOfSeparator(trimmed);
            if (separator > 0) {
                keys.add(trimmed.substring(0, separator).trim());
            }
        }
        return keys;
    }

    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static String escapeValue(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            parent = Paths.get("").toAbsolutePath();
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "application", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException e) {
                // Windows 等非 POSIX 文件系统，或当前文件系统不支持修改权限。
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path codeSourcePath() {
        try {
            var location = ConfigFileManager.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            return Paths.get(location.toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    record PrepareResult(boolean created, int addedKeys, int filledKeys) {
    }

    private record BackfillResult(boolean changed, String content, List<String> addedKeys,
                                  List<String> filledKeys) {
    }
}
