package cn.citprobe.bilibot.auth;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * B 站密码登录的 RSA 加密：Base64(RSA_PKCS1(salt + password))。
 */
public final class PasswordEncryptor {

    private PasswordEncryptor() {
    }

    public static String encrypt(String salt, String publicKeyPem, String password) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("salt 不能为空");
        }
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("公钥不能为空");
        }
        if (password == null) {
            throw new IllegalArgumentException("password 不能为空");
        }
        try {
            String base64 = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));

            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal((salt + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("B站登录密码加密失败: " + e.getMessage(), e);
        }
    }
}
