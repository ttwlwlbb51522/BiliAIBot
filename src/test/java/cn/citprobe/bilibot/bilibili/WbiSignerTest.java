package cn.citprobe.bilibot.bilibili;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbiSignerTest {

    @Test
    void buildsMixinAndSignatureDeterministically() {
        String mixin = WbiSigner.mixinKey(
                "7cd084941338484aae1ad9425b84077c",
                "4932caff0ff746eab6f01bf08b70ac45");
        assertEquals(64, mixin.length());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("foo", "114");
        params.put("bar", "514");
        String first = WbiSigner.sign(params, mixin);
        String second = WbiSigner.sign(params, mixin);
        assertEquals(first, second);
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
    }
}
