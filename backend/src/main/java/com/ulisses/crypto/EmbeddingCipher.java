package com.ulisses.crypto;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia em repouso dos templates biometricos (embeddings) com AES-256-GCM.
 *
 * <p>A chave e derivada (SHA-256) de um segredo de configuracao. Quando nenhum segredo e
 * fornecido (ambiente de desenvolvimento), a cifra opera em modo pass-through e apenas registra
 * um aviso — os bytes sao gravados como estao. Em producao o segredo e obrigatorio (ver
 * application-prod.yml), garantindo que dado biometrico sensivel nunca fique em claro no banco.
 *
 * <p>Formato do valor cifrado: [0x01 versao][12 bytes IV][ciphertext + tag GCM]. O byte de versao
 * permite conviver com dados legados em claro (que nao comecam com 0x01) durante uma migracao.
 */
public final class EmbeddingCipher {

	private static final byte VERSION = 0x01;
	private static final int IV_LENGTH = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile SecretKeySpec key;

	private EmbeddingCipher() {}

	/** Inicializa a chave a partir do segredo de configuracao; segredo vazio = pass-through. */
	public static void init(String secret) {
		if (secret == null || secret.isBlank()) {
			key = null;
			return;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes("UTF-8"));
			key = new SecretKeySpec(digest, "AES");
		} catch (Exception e) {
			throw new IllegalStateException("Falha ao derivar a chave de criptografia", e);
		}
	}

	public static boolean isEnabled() {
		return key != null;
	}

	public static byte[] encrypt(byte[] plain) {
		if (key == null || plain == null) {
			return plain;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plain);
			byte[] out = new byte[1 + IV_LENGTH + ciphertext.length];
			out[0] = VERSION;
			System.arraycopy(iv, 0, out, 1, IV_LENGTH);
			System.arraycopy(ciphertext, 0, out, 1 + IV_LENGTH, ciphertext.length);
			return out;
		} catch (Exception e) {
			throw new IllegalStateException("Falha ao criptografar o template biometrico", e);
		}
	}

	public static byte[] decrypt(byte[] stored) {
		if (stored == null || stored.length == 0) {
			return stored;
		}
		// Dado legado em claro (ou cifra desabilitada): nao comeca com o marcador de versao.
		if (key == null || stored[0] != VERSION) {
			return stored;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			System.arraycopy(stored, 1, iv, 0, IV_LENGTH);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return cipher.doFinal(stored, 1 + IV_LENGTH, stored.length - 1 - IV_LENGTH);
		} catch (Exception e) {
			throw new IllegalStateException("Falha ao descriptografar o template biometrico", e);
		}
	}
}
