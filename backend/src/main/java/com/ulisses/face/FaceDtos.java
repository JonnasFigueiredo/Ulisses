package com.ulisses.face;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class FaceDtos {

	private FaceDtos() {}

	public record EnrollmentStatus(
			boolean enrolled, Instant enrolledAt, int templates, String engineStatus) {}

	public record EnrollResult(
			int framesReceived, int framesUsed, double consistency, Map<String, Integer> framesPerPhase) {}

	/** directions: sequencia de movimentos a executar, na ordem (ex.: ["LEFT","RIGHT"]). */
	public record ChallengeResponse(
			String challengeId, List<String> directions, long expiresInSeconds) {}

	/**
	 * Retorno da checagem de pose em tempo real usada para guiar a captura.
	 * enrollQuality indica se o rosto tem resolucao suficiente para o CADASTRO
	 * (distancia interocular minima, criterio ISO/IEC 19794-5).
	 * centerX/centerY/faceHeight sao normalizados (0..1) em relacao ao quadro e
	 * permitem ao frontend exigir o rosto dentro do oval-guia.
	 */
	public record PoseResponse(
			boolean faceDetected,
			double noseRatio,
			double noseVertical,
			double interocular,
			boolean enrollQuality,
			double centerX,
			double centerY,
			double faceHeight,
			boolean multipleFaces) {}

	public record VerifyResult(
			boolean success,
			boolean match,
			double similarity,
			double threshold,
			boolean liveness,
			String livenessDetail,
			boolean passiveEvaluated,
			boolean passivePassed,
			double passiveScore,
			double passiveThreshold,
			int framesUsed) {}

	public record AttemptDto(Instant createdAt, double similarity, boolean liveness, boolean success) {

		public static AttemptDto from(VerificationAttempt attempt) {
			return new AttemptDto(
					attempt.getCreatedAt(),
					attempt.getSimilarity(),
					attempt.isLivenessPassed(),
					attempt.isSuccess());
		}
	}

	public record HistoryResponse(List<AttemptDto> attempts) {}
}
