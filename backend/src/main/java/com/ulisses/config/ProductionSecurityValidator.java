package com.ulisses.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Impede que o perfil de producao inicie com configuracao de seguranca de exemplo. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSecurityValidator {

	public ProductionSecurityValidator(
			@Value("${ulisses.jwt.secret:}") String jwtSecret,
			@Value("${ulisses.crypto.key:}") String cryptoKey,
			@Value("${ulisses.cors.allowed-origins:}") String allowedOrigins) {
		validateSecret("ULISSES_JWT_SECRET", jwtSecret);
		validateSecret("ULISSES_CRYPTO_KEY", cryptoKey);
		validateOrigins(allowedOrigins);
	}

	private static void validateSecret(String variable, String secret) {
		if (secret == null || secret.isBlank()
				|| secret.strip().getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException(variable + " deve conter um segredo aleatorio de pelo menos 32 bytes em producao");
		}
		String normalized = secret.strip().toLowerCase(Locale.ROOT);
		if (normalized.startsWith("troque-") || normalized.startsWith("ulisses-dev-secret")) {
			throw new IllegalStateException(variable + " nao pode usar o valor de exemplo em producao");
		}
	}

	private static void validateOrigins(String allowedOrigins) {
		if (allowedOrigins == null || allowedOrigins.isBlank()) {
			throw new IllegalStateException("ULISSES_CORS_ORIGINS deve listar as origens permitidas em producao");
		}
		for (String origin : allowedOrigins.split(",", -1)) {
			if (origin.isBlank() || origin.contains("*")) {
				throw new IllegalStateException("ULISSES_CORS_ORIGINS deve conter apenas origens explicitas, sem valores vazios ou curingas");
			}
		}
	}
}
