package com.ulisses.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	private AuthDtos() {}

	public record RegisterRequest(
			@NotBlank(message = "informe seu nome") @Size(max = 120) String name,
			@NotBlank(message = "informe o usuario") @Size(min = 3, max = 60, message = "minimo de 3 caracteres")
					String username,
			@NotBlank(message = "informe a senha") @Size(min = 6, max = 100, message = "minimo de 6 caracteres")
					String password) {}

	public record LoginRequest(
			@NotBlank(message = "informe o usuario") String username,
			@NotBlank(message = "informe a senha") String password) {}

	public record UserDto(Long id, String name, String username) {
		public static UserDto from(User user) {
			return new UserDto(user.getId(), user.getName(), user.getUsername());
		}
	}

	public record AuthResponse(String token, UserDto user) {}
}
