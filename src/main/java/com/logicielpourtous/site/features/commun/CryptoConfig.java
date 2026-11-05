package com.logicielpourtous.site.features.commun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.stereotype.Component;

@Component
public class CryptoConfig {

    public CryptoConfig(@Value("${app.crypto.key}") String key,
                        @Value("${app.crypto.salt}") String salt) {
        EncryptionConverter.ENCRYPTOR = Encryptors.text(key, salt);
    }
}
