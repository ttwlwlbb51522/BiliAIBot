package cn.citprobe.bilibot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsPersonaFileWhenMissing() {
        Path file = tempDir.resolve("persona.txt");

        PersonaSource source = new PersonaSource(file);

        assertTrue(Files.isRegularFile(file));
        assertTrue(source.get().contains("蓝色大肥鱼"));
    }

    @Test
    void reloadsWhenFileChanges() throws Exception {
        Path file = tempDir.resolve("persona.txt");
        Files.writeString(file, "人设A");
        PersonaSource source = new PersonaSource(file);
        assertEquals("人设A", source.get());

        Files.writeString(file, "人设B");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertEquals("人设B", source.get());
    }
}
