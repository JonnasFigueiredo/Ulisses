package com.ulisses.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingCipherTest {

	@AfterEach
	void reset() {
		EmbeddingCipher.init(""); // volta ao modo pass-through
	}

	@Test
	@DisplayName("ciclo cifrar/decifrar recupera o conteudo original")
	void roundTrip() {
		EmbeddingCipher.init("chave-secreta-de-teste");
		byte[] plain = "template-biometrico-512-floats".getBytes(StandardCharsets.UTF_8);
		byte[] encrypted = EmbeddingCipher.encrypt(plain);
		assertNotEquals(plain.length, encrypted.length); // tem IV + tag
		assertFalse(java.util.Arrays.equals(plain, encrypted)); // nao esta em claro
		assertArrayEquals(plain, EmbeddingCipher.decrypt(encrypted));
	}

	@Test
	@DisplayName("cifra com chave produz saidas diferentes a cada vez (IV aleatorio)")
	void randomizedIv() {
		EmbeddingCipher.init("chave");
		byte[] plain = new byte[] {1, 2, 3, 4, 5};
		assertFalse(java.util.Arrays.equals(EmbeddingCipher.encrypt(plain), EmbeddingCipher.encrypt(plain)));
	}

	@Test
	@DisplayName("sem chave (dev) opera em pass-through")
	void passthroughWithoutKey() {
		EmbeddingCipher.init("");
		assertFalse(EmbeddingCipher.isEnabled());
		byte[] plain = new byte[] {9, 8, 7};
		assertArrayEquals(plain, EmbeddingCipher.encrypt(plain));
		assertArrayEquals(plain, EmbeddingCipher.decrypt(plain));
	}

	@Test
	@DisplayName("decifrar com chave errada falha (integridade GCM)")
	void wrongKeyFails() {
		EmbeddingCipher.init("chave-correta");
		byte[] encrypted = EmbeddingCipher.encrypt(new byte[] {1, 2, 3});
		EmbeddingCipher.init("chave-errada");
		assertThrows(IllegalStateException.class, () -> EmbeddingCipher.decrypt(encrypted));
	}

	@Test
	@DisplayName("dado legado em claro e lido mesmo com a cifra ativa")
	void legacyPlaintextTolerated() {
		EmbeddingCipher.init("chave");
		byte[] legacy = new byte[] {2, 2, 2, 2}; // nao comeca com o marcador de versao 0x01
		assertArrayEquals(legacy, EmbeddingCipher.decrypt(legacy));
		assertTrue(EmbeddingCipher.isEnabled());
		assertEquals(4, legacy.length);
	}
}
