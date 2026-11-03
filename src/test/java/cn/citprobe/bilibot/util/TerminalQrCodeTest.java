package cn.citprobe.bilibot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalQrCodeTest {

    @Test
    void rendersQrToTerminal() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            TerminalQrCode.print("https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=test");
        } finally {
            System.setOut(original);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\033[30;47m"));
        assertTrue(output.contains("█"));
        assertTrue(output.lines().count() > 10);
    }

    @Test
    void savesPng(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("login-qrcode.png");

        Path saved = TerminalQrCode.savePng("https://example.com/login", file);

        assertTrue(Files.isRegularFile(saved));
        BufferedImage image = ImageIO.read(saved.toFile());
        assertNotNull(image);
        assertTrue(image.getWidth() > 100);
    }
}
