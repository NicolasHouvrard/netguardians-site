package com.logicielpourtous.site.features.mail;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Converter
public class MailEncryptionConverter implements AttributeConverter<String, String> {

    private static final String ALGO = "AES/CBC/PKCS5Padding";
    private static byte[] KEY;

    @Value("${app.crypto.key}")
    public void setKey(String key) {
        KEY = Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 16);
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[16 + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, 16);
            System.arraycopy(encrypted, 0, combined, 16, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption error", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv         = Arrays.copyOfRange(combined, 0, 16);
            byte[] ciphertext = Arrays.copyOfRange(combined, 16, combined.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption error", e);
        }
    }
}
