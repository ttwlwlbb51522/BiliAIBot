package cn.citprobe.bilibot.draft;

import cn.citprobe.bilibot.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿文件存储。草稿只在本地，审批后才发送。
 */
final class DraftStore {

    private static final Logger log = LoggerFactory.getLogger(DraftStore.class);
    private static final ObjectMapper MAPPER = Json.STATE;

    private final Path file;
    private final List<Draft> drafts = new ArrayList<>();

    DraftStore(Path file) {
        this.file = file;
        load();
    }

    synchronized List<Draft> list() {
        return List.copyOf(drafts);
    }

    synchronized void add(Draft draft) {
        drafts.add(draft);
        save();
    }

    synchronized Draft get(String id) {
        for (Draft draft : drafts) {
            if (draft.id().equals(id)) {
                return draft;
            }
        }
        return null;
    }

    synchronized void remove(String id) {
        drafts.removeIf(draft -> draft.id().equals(id));
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            DraftsState state = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    DraftsState.class);
            if (state != null && state.drafts() != null) {
                drafts.addAll(state.drafts());
            }
            log.info("已加载待审草稿: {} 条", drafts.size());
        } catch (IOException e) {
            log.warn("读取草稿文件失败，暂不加载草稿: {}", file, e);
        }
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new DraftsState(new ArrayList<>(drafts))),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("写入草稿文件失败: {}", file, e);
        }
    }

    record DraftsState(List<Draft> drafts) {
    }
}
