package com.logicielpourtous.site.features.commun;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.TextEncryptor;

@Converter
public class EncryptionConverter implements AttributeConverter<String, String> {

    // Initialized by CryptoConfig at application startup
    static volatile TextEncryptor ENCRYPTOR;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (ENCRYPTOR == null || attribute == null || attribute.isBlank()) return attribute;
        return ENCRYPTOR.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (ENCRYPTOR == null || dbData == null || dbData.isBlank()) return dbData;
        try {
            return ENCRYPTOR.decrypt(dbData);
        } catch (Exception e) {
            // Données non chiffrées (migration) — retourner tel quel
            return dbData;
        }
    }
}
