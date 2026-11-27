package com.attendance.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Transparently encrypts an entity attribute on write and decrypts it on read. */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return FieldEncryptor.get().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return FieldEncryptor.get().decrypt(dbData);
    }
}
