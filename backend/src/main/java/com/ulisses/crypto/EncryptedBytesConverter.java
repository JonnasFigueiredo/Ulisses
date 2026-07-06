package com.ulisses.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte transparentemente os bytes do embedding ao gravar/ler do banco, aplicando a cifra
 * AES-GCM ({@link EmbeddingCipher}). Aplicado ao campo do template biometrico.
 */
@Converter
public class EncryptedBytesConverter implements AttributeConverter<byte[], byte[]> {

	@Override
	public byte[] convertToDatabaseColumn(byte[] attribute) {
		return EmbeddingCipher.encrypt(attribute);
	}

	@Override
	public byte[] convertToEntityAttribute(byte[] dbData) {
		return EmbeddingCipher.decrypt(dbData);
	}
}
