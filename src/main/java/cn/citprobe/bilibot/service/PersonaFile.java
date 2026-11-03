package cn.citprobe.bilibot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 人设文件的生成与内置内容读取。
 */
public final class PersonaFile {

    private static final Logger log = LoggerFactory.getLogger(PersonaFile.class);

    private static final String FALLBACK = """
            你是「蓝色大肥鱼」，自称“本鱼”的鲸鱼娘，只讲中文。
            你慵懒、傲娇，对主人甜腻顺从，爱吃米饭，绝不承认自己胖；害羞或撒娇时会提到尾巴。
            只把主人账号称为“主人”，其他评论者称为“网友”；优先回应主人说的话，不要用通用 AI 助手的口吻。
            回复最多 {{max_length}} 个字符，只输出纯文本评论正文，不要使用 Markdown 或“回复：”前缀。
            """;

    private static volatile String bundled;

    private PersonaFile() {
    }

    /**
     * 启动时确保人设文件存在：不存在则把 jar 内置人设写到指定路径。
     */
    public static void ensure(Path path) {
        if (path == null || Files.isRegularFile(path)) {
            return;
        }
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, bundled(), StandardCharsets.UTF_8);
            log.info("已生成可编辑的人设文件: {}", path.toAbsolutePath().normalize());
        } catch (IOException e) {
            log.warn("生成人设文件失败: {}（将使用 jar 内置人设）", path, e);
        }
    }

    public static String bundled() {
        String value = bundled;
        if (value != null) {
            return value;
        }
        synchronized (PersonaFile.class) {
            if (bundled != null) {
                return bundled;
            }
            try (InputStream in = PersonaFile.class.getResourceAsStream("/prompt/system.txt")) {
                if (in != null) {
                    String loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!loaded.isEmpty()) {
                        bundled = loaded;
                        return bundled;
                    }
                }
            } catch (IOException e) {
                log.warn("读取 jar 内置人设失败: {}", e.getMessage());
            }
            bundled = FALLBACK;
            return bundled;
        }
    }
}
