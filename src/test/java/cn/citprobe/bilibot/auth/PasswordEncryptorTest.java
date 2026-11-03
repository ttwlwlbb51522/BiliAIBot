package cn.citprobe.bilibot.auth;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordEncryptorTest {

    @Test
    void encryptsSaltPlusPasswordWithRsaPkcs1() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        String encrypted = PasswordEncryptor.encrypt("0123456789abcdef", pem, "p@ss word");

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        String plain = new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)),
                StandardCharsets.UTF_8);
        assertEquals("0123456789abcdefp@ss word", plain);
    }

    @Test
    void rejectsInvalidPublicKey() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordEncryptor.encrypt("salt", "not-a-key", "password"));
    }
}
