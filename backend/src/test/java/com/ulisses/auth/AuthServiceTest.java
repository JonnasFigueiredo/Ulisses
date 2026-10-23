package com.ulisses.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ulisses.auth.AuthDtos.LoginRequest;
import com.ulisses.auth.AuthDtos.RegisterRequest;
import com.ulisses.common.ApiException;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

	private UserRepository repository;
	private PasswordEncoder encoder;
	private JwtService jwtService;
	private AuthService service;

	@BeforeEach
	void setUp() {
		repository = mock(UserRepository.class);
		encoder = mock(PasswordEncoder.class);
		jwtService = mock(JwtService.class);
		service = new AuthService(repository, encoder, jwtService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "\u00e9"})
	void rejectsPasswordsExceedingBcryptByteLimitBeforeAccessingStorage(String character) {
		String password = character.repeat(character.equals("a") ? 73 : 37);
		ApiException registerError = assertThrows(ApiException.class,
				() -> service.register(new RegisterRequest("User", "user", password)));
		ApiException loginError = assertThrows(ApiException.class,
				() -> service.login(new LoginRequest("user", password)));
		assertEquals(HttpStatus.BAD_REQUEST, registerError.getStatus());
		assertEquals(HttpStatus.BAD_REQUEST, loginError.getStatus());
		verifyNoInteractions(repository, encoder, jwtService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "\u00e9"})
	void acceptsPasswordsAtBcryptByteLimitAndPreservesTheirContents(String character) {
		String password = character.repeat(character.equals("a") ? 72 : 36);
		PasswordEncoder realEncoder = new BCryptPasswordEncoder(4);
		service = new AuthService(repository, realEncoder, jwtService);
		when(repository.save(any())).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			when(repository.findByUsername("user")).thenReturn(Optional.of(user));
			return user;
		});
		service.register(new RegisterRequest("User", "user", password));
		assertEquals("user", service.login(new LoginRequest("user", password)).user().username());
	}

	@Test
	void validatesUsernameLengthAfterTrimming() {
		ApiException error = assertThrows(ApiException.class,
				() -> service.register(new RegisterRequest("User", " a ", "password")));
		assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
		verifyNoInteractions(repository, encoder, jwtService);
	}

	@Test
	void normalizesUsernameIndependentlyOfServerLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			User user = new User();
			user.setUsername("indigo");
			user.setPasswordHash("hash");
			when(repository.findByUsername("indigo")).thenReturn(Optional.of(user));
			when(encoder.matches("password", "hash")).thenReturn(true);
			assertEquals("indigo", service.register(new RegisterRequest("User", " INDIGO ", "password"))
					.user().username());
			assertEquals("indigo", service.login(new LoginRequest(" INDIGO ", "password")).user().username());
			verify(repository).existsByUsername("indigo");
		} finally {
			Locale.setDefault(previous);
		}
	}
}
