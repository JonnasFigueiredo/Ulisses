package com.ulisses.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionSecurityValidatorTest {

	private static final String JWT_SECRET = "6a2e9785f03d4b1c8f764d0ab5e19c32";
	private static final String CRYPTO_KEY = "b73c8e1590fd24a6e5087bc192d4a60f";
	private static final String ORIGINS = "https://ulisses.example,https://localhost,capacitor://localhost";

	@Test
	void acceptsExplicitProductionConfiguration() {
		assertDoesNotThrow(() -> new ProductionSecurityValidator(JWT_SECRET, CRYPTO_KEY, ORIGINS));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			"   ",
			"short-key",
			"1234567890123456789012345678901",
			" 1234567890123456789012345678901 ",
			"troque-este-valor-por-um-segredo-forte",
			"troque-esta-chave-de-criptografia",
			"troque-em-producao-0f1e2d3c4b5a69788796a5b4c3d2e1f0",
			"troque-em-producao-a1b2c3d4e5f6",
			"ulisses-dev-secret-key-nao-use-em-producao-9f4b2c7e1a8d6f3b5e0c9a7d4f2b8e6c"
	})
	void rejectsUnsafeSecretsWithoutDisclosingTheirValues(String unsafeSecret) {
		IllegalStateException jwtError = assertThrows(IllegalStateException.class,
				() -> new ProductionSecurityValidator(unsafeSecret, CRYPTO_KEY, ORIGINS));
		IllegalStateException cryptoError = assertThrows(IllegalStateException.class,
				() -> new ProductionSecurityValidator(JWT_SECRET, unsafeSecret, ORIGINS));
		assertThat(jwtError.getMessage()).startsWith("ULISSES_JWT_SECRET");
		assertThat(cryptoError.getMessage()).startsWith("ULISSES_CRYPTO_KEY");
		if (unsafeSecret != null && !unsafeSecret.isBlank()) {
			assertThat(jwtError.getMessage()).doesNotContain(unsafeSecret);
			assertThat(cryptoError.getMessage()).doesNotContain(unsafeSecret);
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "*", "https://*.example.com", "https://ulisses.example,*",
			"https://ulisses.example,", ",https://ulisses.example", "https://ulisses.example, "})
	void rejectsMissingOrWildcardOrigins(String unsafeOrigins) {
		assertThrows(IllegalStateException.class,
				() -> new ProductionSecurityValidator(JWT_SECRET, CRYPTO_KEY, unsafeOrigins));
	}

	@Test
	void productionContextFailsToStartWithoutSecrets() {
		new ApplicationContextRunner()
				.withPropertyValues("spring.profiles.active=prod")
				.withUserConfiguration(ProductionSecurityValidator.class)
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void productionContextStartsWithSafeConfiguration() {
		new ApplicationContextRunner()
				.withPropertyValues("spring.profiles.active=prod", "ulisses.jwt.secret=" + JWT_SECRET,
						"ulisses.crypto.key=" + CRYPTO_KEY, "ulisses.cors.allowed-origins=" + ORIGINS)
				.withUserConfiguration(ProductionSecurityValidator.class)
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductionSecurityValidator.class));
	}

	@Test
	void developmentContextKeepsItsExistingConfiguration() {
		new ApplicationContextRunner()
				.withUserConfiguration(ProductionSecurityValidator.class)
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionSecurityValidator.class));
	}
}
