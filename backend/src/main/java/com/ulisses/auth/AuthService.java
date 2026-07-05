package com.ulisses.auth;

import com.ulisses.auth.AuthDtos.AuthResponse;
import com.ulisses.auth.AuthDtos.LoginRequest;
import com.ulisses.auth.AuthDtos.RegisterRequest;
import com.ulisses.auth.AuthDtos.UserDto;
import com.ulisses.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(
			UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	public AuthResponse register(RegisterRequest request) {
		String username = request.username().trim().toLowerCase();
		if (userRepository.existsByUsername(username)) {
			throw ApiException.conflict("Este usuario ja esta em uso");
		}
		User user = new User();
		user.setName(request.name().trim());
		user.setUsername(username);
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		userRepository.save(user);
		return new AuthResponse(jwtService.generateToken(username), UserDto.from(user));
	}

	public AuthResponse login(LoginRequest request) {
		String username = request.username().trim().toLowerCase();
		User user = userRepository
				.findByUsername(username)
				.orElseThrow(() -> ApiException.unauthorized("Usuario ou senha invalidos"));
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw ApiException.unauthorized("Usuario ou senha invalidos");
		}
		return new AuthResponse(jwtService.generateToken(username), UserDto.from(user));
	}
}
