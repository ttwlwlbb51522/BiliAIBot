package cn.citprobe.bilibot.pm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateMessageStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsSeenMessageKeys() throws Exception {
        Path file = tempDir.resolve("private-messages.json");
        PrivateMessageStore first = new PrivateMessageStore(file);
        first.markSeen("key-1");
        first.save();

        PrivateMessageStore second = new PrivateMessageStore(file);

        assertTrue(second.isSeen("key-1"));
        assertFalse(second.isSeen("key-2"));
        assertTrue(Files.readString(file).contains("key-1"));
    }
}
