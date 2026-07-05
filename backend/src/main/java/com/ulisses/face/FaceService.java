package com.ulisses.face;

import com.ulisses.auth.User;
import com.ulisses.common.ApiException;
import com.ulisses.face.ChallengeService.Challenge;
import com.ulisses.face.ChallengeService.Direction;
import com.ulisses.face.FaceDtos.AttemptDto;
import com.ulisses.face.FaceDtos.EnrollResult;
import com.ulisses.face.FaceDtos.EnrollmentStatus;
import com.ulisses.face.FaceDtos.HistoryResponse;
import com.ulisses.face.FaceDtos.VerifyResult;
import com.ulisses.face.engine.Detection;
import com.ulisses.face.engine.FaceEmbedder;
import com.ulisses.face.engine.FaceEngine;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FaceService {

	private static final Logger log = LoggerFactory.getLogger(FaceService.class);

	/** Frames validos minimos para aceitar um cadastro. */
	private static final int MIN_ENROLL_FRAMES = 6;
	private static final int MIN_ENROLL_STRAIGHT_FRAMES = 3;
	/** Coerencia minima entre os frames do cadastro (mesma pessoa durante toda a captura). */
	private static final double MIN_ENROLL_CONSISTENCY = 0.45;
	/** Frames frontais validos minimos para uma validacao. */
	private static final int MIN_VERIFY_STRAIGHT_FRAMES = 3;
	/** Desvio minimo do nariz (em fracao da distancia interocular) para aceitar o giro de cabeca. */
	private static final double MIN_TURN_DEVIATION = 0.12;
	/** Fracao minima dos frames de giro que precisam confirmar o movimento na direcao sorteada. */
	private static final double MIN_TURN_FRAME_RATIO = 0.5;
	/** Identidade minima nos frames de giro (pose lateral degrada o embedding, limiar mais baixo). */
	private static final double MIN_TURN_IDENTITY = 0.28;

	private final FaceEngine engine;
	private final FaceProperties properties;
	private final FaceTemplateRepository templateRepository;
	private final VerificationAttemptRepository attemptRepository;
	private final ChallengeService challengeService;

	public FaceService(
			FaceEngine engine,
			FaceProperties properties,
			FaceTemplateRepository templateRepository,
			VerificationAttemptRepository attemptRepository,
			ChallengeService challengeService) {
		this.engine = engine;
		this.properties = properties;
		this.templateRepository = templateRepository;
		this.attemptRepository = attemptRepository;
		this.challengeService = challengeService;
	}

	public EnrollmentStatus status(User user) {
		List<FaceTemplate> templates = templateRepository.findByUserId(user.getId());
		Instant enrolledAt = templates.stream()
				.map(FaceTemplate::getCreatedAt)
				.max(Comparator.naturalOrder())
				.orElse(null);
		return new EnrollmentStatus(
				!templates.isEmpty(), enrolledAt, templates.size(), engine.getStatus().name());
	}

	/** Frame processado: embedding + pose estimada. */
	private record ProcessedFrame(String phase, float[] embedding, float noseRatio) {}

	@Transactional
	public EnrollResult enroll(User user, List<MultipartFile> frames, List<String> phases) {
		List<ProcessedFrame> processed = processFrames(frames, phases);

		Map<String, List<ProcessedFrame>> byPhase = new HashMap<>();
		for (ProcessedFrame frame : processed) {
			byPhase.computeIfAbsent(frame.phase(), k -> new ArrayList<>()).add(frame);
		}
		int straightCount = byPhase.getOrDefault("STRAIGHT", List.of()).size();
		if (processed.size() < MIN_ENROLL_FRAMES || straightCount < MIN_ENROLL_STRAIGHT_FRAMES) {
			throw ApiException.badRequest(
					"Nao foi possivel capturar o rosto com qualidade suficiente. Procure um lugar bem iluminado, "
							+ "centralize o rosto no oval e tente novamente.");
		}

		double consistency = meanPairwiseSimilarity(processed);
		if (consistency < MIN_ENROLL_CONSISTENCY) {
			throw ApiException.badRequest(
					"A captura ficou inconsistente (rostos diferentes ou movimento excessivo). Tente novamente.");
		}

		templateRepository.deleteByUserId(user.getId());
		Map<String, Integer> framesPerPhase = new HashMap<>();
		for (Map.Entry<String, List<ProcessedFrame>> entry : byPhase.entrySet()) {
			framesPerPhase.put(entry.getKey(), entry.getValue().size());
			FaceTemplate template = new FaceTemplate();
			template.setUserId(user.getId());
			template.setPhase(entry.getKey());
			template.setEmbeddingVector(meanEmbedding(entry.getValue()));
			templateRepository.save(template);
		}
		log.info(
				"Biometria cadastrada para o usuario {} ({} frames validos, consistencia {})",
				user.getUsername(),
				processed.size(),
				String.format(Locale.ROOT, "%.3f", consistency));
		return new EnrollResult(frames.size(), processed.size(), round(consistency), framesPerPhase);
	}

	@Transactional
	public VerifyResult verify(
			User user, String challengeId, List<MultipartFile> frames, List<String> phases) {
		Challenge challenge = challengeService.consume(challengeId, user.getId());
		if (challenge == null) {
			throw ApiException.badRequest("Desafio de prova de vida invalido ou expirado. Inicie a validacao novamente.");
		}
		List<FaceTemplate> templates = templateRepository.findByUserId(user.getId());
		if (templates.isEmpty()) {
			throw ApiException.badRequest("Nenhuma biometria cadastrada. Faca o cadastro facial primeiro.");
		}
		List<float[]> templateVectors = templates.stream()
				.map(FaceTemplate::getEmbeddingVector)
				.toList();

		List<ProcessedFrame> processed = processFrames(frames, phases);
		List<ProcessedFrame> straight = processed.stream()
				.filter(f -> "STRAIGHT".equals(f.phase()))
				.toList();
		List<ProcessedFrame> turn = processed.stream()
				.filter(f -> "TURN".equals(f.phase()))
				.toList();

		if (straight.size() < MIN_VERIFY_STRAIGHT_FRAMES) {
			throw ApiException.badRequest(
					"Rosto nao detectado com qualidade suficiente. Centralize o rosto e garanta boa iluminacao.");
		}

		// 1) Identidade: media da melhor similaridade de cada frame frontal contra os templates
		double similarity = straight.stream()
				.mapToDouble(f -> bestSimilarity(f.embedding(), templateVectors))
				.average()
				.orElse(0);
		boolean match = similarity >= properties.matchThreshold();

		// 2) Prova de vida: o nariz precisa se deslocar na direcao sorteada durante a fase de giro
		LivenessCheck liveness = checkLiveness(challenge.direction(), straight, turn, templateVectors);

		boolean success = match && liveness.passed();
		VerificationAttempt attempt = new VerificationAttempt();
		attempt.setUserId(user.getId());
		attempt.setSimilarity(round(similarity));
		attempt.setLivenessPassed(liveness.passed());
		attempt.setSuccess(success);
		attemptRepository.save(attempt);

		log.info(
				"Validacao do usuario {}: similaridade={}, liveness={}, sucesso={}",
				user.getUsername(),
				String.format(Locale.ROOT, "%.3f", similarity),
				liveness.passed(),
				success);
		return new VerifyResult(
				success,
				match,
				round(similarity),
				properties.matchThreshold(),
				liveness.passed(),
				liveness.detail(),
				processed.size());
	}

	@Transactional
	public void deleteEnrollment(User user) {
		templateRepository.deleteByUserId(user.getId());
	}

	public HistoryResponse history(User user) {
		return new HistoryResponse(
				attemptRepository.findTop10ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
						.map(AttemptDto::from)
						.toList());
	}

	private record LivenessCheck(boolean passed, String detail) {}

	private LivenessCheck checkLiveness(
			Direction direction,
			List<ProcessedFrame> straight,
			List<ProcessedFrame> turn,
			List<float[]> templateVectors) {
		if (turn.size() < 3) {
			return new LivenessCheck(false, "Movimento de cabeca nao detectado");
		}
		double baseline = straight.stream().mapToDouble(ProcessedFrame::noseRatio).average().orElse(0.5);

		// Nos frames sem espelhamento, girar a cabeca para a PROPRIA esquerda desloca o nariz para a
		// direita da imagem (ratio aumenta); para a direita, o inverso.
		long moved = turn.stream()
				.filter(f -> direction == Direction.LEFT
						? f.noseRatio() - baseline >= MIN_TURN_DEVIATION
						: baseline - f.noseRatio() >= MIN_TURN_DEVIATION)
				.count();
		boolean movementOk = moved >= Math.ceil(turn.size() * MIN_TURN_FRAME_RATIO);
		if (!movementOk) {
			return new LivenessCheck(
					false,
					"O movimento de cabeca esperado (" + directionLabel(direction) + ") nao foi confirmado");
		}

		// A pessoa que girou a cabeca precisa ser a mesma dos templates (evita troca de rosto no meio)
		double turnIdentity = turn.stream()
				.mapToDouble(f -> bestSimilarity(f.embedding(), templateVectors))
				.average()
				.orElse(0);
		if (turnIdentity < MIN_TURN_IDENTITY) {
			return new LivenessCheck(false, "Identidade inconsistente durante o movimento");
		}
		return new LivenessCheck(true, "Movimento " + directionLabel(direction) + " confirmado");
	}

	private static String directionLabel(Direction direction) {
		return direction == Direction.LEFT ? "para a esquerda" : "para a direita";
	}

	/** Decodifica cada frame, detecta o rosto principal e extrai o embedding. Frames ruins sao descartados. */
	private List<ProcessedFrame> processFrames(List<MultipartFile> frames, List<String> phases) {
		if (frames == null || frames.isEmpty()) {
			throw ApiException.badRequest("Nenhum frame recebido");
		}
		if (phases == null || phases.size() != frames.size()) {
			throw ApiException.badRequest("Cada frame precisa da fase de captura correspondente");
		}
		List<ProcessedFrame> processed = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			BufferedImage image = decode(frames.get(i));
			if (image == null) {
				continue;
			}
			Optional<Detection> face = engine.detectMainFace(image);
			if (face.isEmpty()) {
				continue;
			}
			Detection detection = face.get();
			float[] embedding = engine.embed(image, detection);
			processed.add(new ProcessedFrame(
					phases.get(i).trim().toUpperCase(Locale.ROOT), embedding, detection.noseRatio()));
		}
		return processed;
	}

	private BufferedImage decode(MultipartFile file) {
		try {
			BufferedImage image = ImageIO.read(file.getInputStream());
			if (image == null) {
				return null;
			}
			if (image.getType() == BufferedImage.TYPE_INT_RGB) {
				return image;
			}
			BufferedImage rgb =
					new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(image, 0, 0, null);
			return rgb;
		} catch (IOException e) {
			return null;
		}
	}

	private static double bestSimilarity(float[] embedding, List<float[]> templates) {
		double best = -1;
		for (float[] template : templates) {
			best = Math.max(best, FaceEmbedder.cosine(embedding, template));
		}
		return best;
	}

	private static float[] meanEmbedding(List<ProcessedFrame> frames) {
		float[] mean = new float[FaceEmbedder.EMBEDDING_SIZE];
		for (ProcessedFrame frame : frames) {
			for (int i = 0; i < mean.length; i++) {
				mean[i] += frame.embedding()[i];
			}
		}
		for (int i = 0; i < mean.length; i++) {
			mean[i] /= frames.size();
		}
		return FaceEmbedder.normalize(mean);
	}

	private static double meanPairwiseSimilarity(List<ProcessedFrame> frames) {
		if (frames.size() < 2) {
			return 1;
		}
		double sum = 0;
		int count = 0;
		for (int i = 0; i < frames.size(); i++) {
			for (int j = i + 1; j < frames.size(); j++) {
				sum += FaceEmbedder.cosine(frames.get(i).embedding(), frames.get(j).embedding());
				count++;
			}
		}
		return sum / count;
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}
}
