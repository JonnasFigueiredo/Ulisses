package com.ulisses.face;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Desafios de prova de vida ativa: a cada validacao o servidor sorteia uma direcao de movimento de
 * cabeca que o usuario precisa executar em video. O desafio e de uso unico e expira rapido, o que
 * impede replay de gravacoes antigas.
 */
@Service
public class ChallengeService {

	/** LEFT/RIGHT: giro de cabeca (eixo yaw). UP/DOWN: inclinacao (eixo pitch). */
	public enum Direction {
		LEFT,
		RIGHT,
		UP,
		DOWN
	}

	/**
	 * Sequencia de DOIS movimentos sorteados de forma independente entre QUATRO direcoes
	 * (esquerda, direita, cima, baixo): um video gravado teria de acertar as duas direcoes na
	 * ordem certa — 1 chance em 16 (~6%) — alem de vencer a analise de trajetoria e a expiracao.
	 */
	public record Challenge(String id, Long userId, List<Direction> sequence, Instant expiresAt) {}

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Challenge> active = new ConcurrentHashMap<>();
	private final FaceProperties properties;

	public ChallengeService(FaceProperties properties) {
		this.properties = properties;
	}

	public Challenge create(Long userId) {
		cleanupExpired();
		// Um desafio ativo por usuario
		active.values().removeIf(c -> c.userId().equals(userId));
		Direction[] directions = Direction.values();
		Challenge challenge = new Challenge(
				UUID.randomUUID().toString(),
				userId,
				List.of(
						directions[random.nextInt(directions.length)],
						directions[random.nextInt(directions.length)]),
				Instant.now().plusSeconds(properties.challengeTtlSeconds()));
		active.put(challenge.id(), challenge);
		return challenge;
	}

	/** Consome o desafio (uso unico). Retorna null se inexistente, expirado ou de outro usuario. */
	public Challenge consume(String challengeId, Long userId) {
		if (challengeId == null) {
			return null;
		}
		Challenge challenge = active.remove(challengeId);
		if (challenge == null
				|| !challenge.userId().equals(userId)
				|| challenge.expiresAt().isBefore(Instant.now())) {
			return null;
		}
		return challenge;
	}

	private void cleanupExpired() {
		Instant now = Instant.now();
		active.values().removeIf(c -> c.expiresAt().isBefore(now));
	}
}
