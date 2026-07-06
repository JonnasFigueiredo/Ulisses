package com.ulisses.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Inicializa a chave de criptografia dos templates biometricos a partir da configuracao,
 * antes de qualquer acesso ao banco.
 */
@Configuration
public class CryptoConfig {

	private static final Logger log = LoggerFactory.getLogger(CryptoConfig.class);

	public CryptoConfig(@Value("${ulisses.crypto.key:}") String key) {
		EmbeddingCipher.init(key);
		if (EmbeddingCipher.isEnabled()) {
			log.info("Criptografia em repouso dos templates biometricos ATIVA (AES-256-GCM)");
		} else {
			log.warn(
					"Criptografia em repouso DESABILITADA: defina ULISSES_CRYPTO_KEY para cifrar os "
							+ "templates biometricos. Nao use assim em producao.");
		}
	}
}
