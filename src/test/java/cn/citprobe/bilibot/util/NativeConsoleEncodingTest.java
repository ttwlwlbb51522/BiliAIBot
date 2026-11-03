package cn.citprobe.bilibot.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeConsoleEncodingTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("console.encoding");
    }

    @Test
    void normalizesCommonEncodingNames() {
        assertEquals(StandardCharsets.UTF_8, NativeConsoleEncoding.normalize("UTF-8"));
        assertEquals(StandardCharsets.UTF_8, NativeConsoleEncoding.normalize("utf8"));
        assertEquals(Charset.forName("GBK"), NativeConsoleEncoding.normalize("GBK"));
        assertEquals(Charset.forName("GBK"), NativeConsoleEncoding.normalize("ms936"));
        assertEquals(Charset.forName("GBK"), NativeConsoleEncoding.normalize("CP936"));
        assertNull(NativeConsoleEncoding.normalize("not-a-real-charset"));
    }

    @Test
    void honorsExplicitOverride() {
        System.setProperty("console.encoding", "GBK");

        assertEquals(Charset.forName("GBK"), NativeConsoleEncoding.detect());
    }
}
