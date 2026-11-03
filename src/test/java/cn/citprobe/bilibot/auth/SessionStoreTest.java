package cn.citprobe.bilibot.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsCookie() {
        Path file = tempDir.resolve("data/session.json");
        SessionStore store = new SessionStore(file);

        store.save("SESSDATA=abc; bili_jct=csrf");

        assertEquals("SESSDATA=abc; bili_jct=csrf", new SessionStore(file).load().orElseThrow());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void clearsSession() {
        Path file = tempDir.resolve("session.json");
        SessionStore store = new SessionStore(file);
        store.save("SESSDATA=abc");

        store.clear();

        assertFalse(Files.exists(file));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void ignoresCorruptCache() throws Exception {
        Path file = tempDir.resolve("session.json");
        Files.writeString(file, "{broken");

        assertTrue(new SessionStore(file).load().isEmpty());
    }
}
