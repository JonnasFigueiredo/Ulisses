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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
	/** Frames validos minimos em cada pose de giro do cadastro. */
	private static final int MIN_ENROLL_TURN_FRAMES = 3;
	/** Desvio minimo do nariz vs pose frontal para aceitar que o rosto foi realmente virado. */
	private static final double MIN_ENROLL_TURN_DEVIATION = 0.10;
	/** Coerencia minima entre os frames do cadastro (mesma pessoa durante toda a captura). */
	private static final double MIN_ENROLL_CONSISTENCY = 0.45;
	/** Frames frontais validos minimos para uma validacao. */
	private static final int MIN_VERIFY_STRAIGHT_FRAMES = 3;
	/**
	 * Frontalidade da baseline (verify): apenas um guarda amplo contra comecar a sessao muito
	 * torto/inclinado — a seguranca real vem do MOVIMENTO relativo, nao destes valores absolutos.
	 * Faixas largas porque a anatomia do rosto varia (posicao da ponta do nariz).
	 */
	private static final double YAW_FRONTAL_MAX = 0.24;
	private static final double PITCH_FRONTAL_MIN = 0.15;
	private static final double PITCH_FRONTAL_MAX = 0.95;
	/** Interocular minima tambem no verify (nao so no cadastro): barra spoof de baixa resolucao/tela. */
	private static final int MIN_VERIFY_INTEROCULAR = 45;
	/** Desvio minimo do nariz para aceitar o GIRO horizontal (yaw). */
	private static final double MIN_TURN_DEVIATION = 0.12;
	/** Desvio minimo para a INCLINACAO vertical (pitch): sinal 2D mais fraco, limiar menor. */
	private static final double MIN_PITCH_DEVIATION = 0.07;
	/** Frames minimos (absolutos) que precisam atingir o desvio pedido na direcao sorteada. */
	private static final int MIN_MOVED_FRAMES = 3;
	/**
	 * Movimento OBSERVADO: cada segmento de giro precisa conter um frame proximo do neutro
	 * (|desvio| abaixo disto) antes de atingir o desvio pedido. Uma foto ja virada nunca tem esse
	 * frame neutro — o que derruba o ataque de "foto estatica rotulada como giro".
	 */
	private static final double NEUTRAL_BAND = 0.06;
	/** Identidade minima nos frames de giro (pose lateral degrada o embedding, mas 0.30 era baixo demais). */
	private static final double MIN_TURN_IDENTITY = 0.38;
	/** Salto maximo do nariz entre frames consecutivos do giro: movimento real e continuo. */
	private static final double MAX_RATIO_JUMP = 0.45;
	/** Um segundo rosto com area acima desta fracao do principal invalida o frame. */
	private static final double SECOND_FACE_AREA_RATIO = 0.20;
	/** Limite de tentativas de validacao por usuario por minuto (anti forca bruta). */
	private static final int MAX_VERIFY_PER_MINUTE = 6;
	/** Limite de sorteios de desafio por usuario por minuto. */
	private static final int MAX_CHALLENGE_PER_MINUTE = 20;
	/**
	 * Limite de checagens de pose por usuario por minuto. Alto de proposito: o cliente legitimo
	 * consulta /pose varias vezes por segundo para o feedback ao vivo; o teto so barra abuso grosseiro.
	 */
	private static final int MAX_POSE_PER_MINUTE = 600;
	/** Teto de frames processados por requisicao (anti-DoS de inferencia). */
	private static final int MAX_FRAMES_PER_REQUEST = 80;

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

	/** Frame processado: embedding + pose (yaw e pitch) + score de liveness passiva (1 = nao avaliado). */
	private record ProcessedFrame(
			String phase, float[] embedding, float noseRatio, float noseVertical, double passiveScore) {}

	/** Resultado do processamento com contadores de descarte para mensagens de erro precisas. */
	private record ProcessResult(List<ProcessedFrame> frames, int multiFace, int blurry) {}

	/**
	 * Rate limit em memoria por usuario e por "balde" (verify / light). AVISO: e por instancia;
	 * em deployment multi-instancia mova para um store compartilhado (Redis) com operacoes atomicas.
	 */
	private final Map<String, java.util.concurrent.ConcurrentLinkedDeque<Long>> rateBuckets =
			new java.util.concurrent.ConcurrentHashMap<>();

	public void checkChallengeRateLimit(Long userId) {
		enforceRate("challenge:" + userId, MAX_CHALLENGE_PER_MINUTE);
	}

	public void checkPoseRateLimit(Long userId) {
		enforceRate("pose:" + userId, MAX_POSE_PER_MINUTE);
	}

	@Transactional
	public EnrollResult enroll(User user, List<MultipartFile> frames, List<String> phases) {
		// No cadastro, os frames frontais precisam de resolucao minima (interocular) para
		// gerar um template de qualidade; nos giros a exigencia nao se aplica (a distancia
		// interocular projetada encolhe naturalmente com a rotacao da cabeca).
		ProcessResult processResult =
				processFrames(frames, phases, properties.minEnrollInterocular(), false);
		List<ProcessedFrame> processed = processResult.frames();

		Map<String, List<ProcessedFrame>> byPhase = new HashMap<>();
		for (ProcessedFrame frame : processed) {
			byPhase.computeIfAbsent(frame.phase(), k -> new ArrayList<>()).add(frame);
		}
		int straightCount = byPhase.getOrDefault("STRAIGHT", List.of()).size();
		if (processed.size() < MIN_ENROLL_FRAMES || straightCount < MIN_ENROLL_STRAIGHT_FRAMES) {
			throw ApiException.badRequest(qualityMessage(processResult));
		}

		validateEnrollmentPoses(byPhase);

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
		checkRateLimit(user.getId());
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

		// No verify tambem exigimos resolucao minima nos frames frontais (barra spoof de tela pequena).
		ProcessResult processResult = processFrames(frames, phases, MIN_VERIFY_INTEROCULAR, true);
		List<ProcessedFrame> processed = processResult.frames();
		List<ProcessedFrame> straight = processed.stream()
				.filter(f -> "STRAIGHT".equals(f.phase()))
				.toList();

		if (straight.size() < MIN_VERIFY_STRAIGHT_FRAMES) {
			throw ApiException.badRequest(qualityMessage(processResult));
		}

		// A baseline de pose e validada NO SERVIDOR como frontal (nao confiamos no rotulo do cliente):
		// um atacante nao pode enviesar a linha de base com frames "STRAIGHT" ja tortos.
		double hBaseCheck = medianNoseRatio(straight);
		double vBaseCheck = median(straight.stream().mapToDouble(ProcessedFrame::noseVertical).toArray());
		if (Math.abs(hBaseCheck) > YAW_FRONTAL_MAX
				|| vBaseCheck < PITCH_FRONTAL_MIN || vBaseCheck > PITCH_FRONTAL_MAX) {
			throw ApiException.badRequest(
					"Nao foi possivel estabelecer o rosto frontal. Olhe diretamente para a camera no inicio da validacao.");
		}

		// 1) Identidade: MEDIANA da melhor similaridade de cada frame frontal contra os templates.
		// A mediana e robusta a frames atipicos (borrao, oclusao momentanea) nos dois sentidos:
		// nem derruba um usuario legitimo por um frame ruim, nem aceita por um unico frame bom.
		double similarity = median(straight.stream()
				.mapToDouble(f -> bestSimilarity(f.embedding(), templateVectors))
				.toArray());
		boolean match = similarity >= properties.matchThreshold();

		// 2) Prova de vida ATIVA: os DOIS movimentos sorteados precisam ser executados na ordem,
		// com trajetoria continua e mantendo a mesma identidade
		LivenessCheck liveness =
				checkLiveness(challenge.sequence(), straight, processed, templateVectors);

		// 3) Prova de vida PASSIVA (anti-spoofing por textura): mediana dos frames frontais.
		// Segunda camada; quando desabilitada, nao bloqueia (score neutro 1.0).
		boolean passiveEvaluated = engine.isPassiveLivenessEnabled();
		double passiveThreshold = properties.passiveLiveness().threshold();
		double passiveScore = passiveEvaluated
				? median(straight.stream().mapToDouble(ProcessedFrame::passiveScore).toArray())
				: 1.0;
		boolean passivePassed = !passiveEvaluated || passiveScore >= passiveThreshold;

		boolean success = match && liveness.passed() && passivePassed;
		VerificationAttempt attempt = new VerificationAttempt();
		attempt.setUserId(user.getId());
		attempt.setSimilarity(round(similarity));
		attempt.setLivenessPassed(liveness.passed());
		attempt.setPassiveScore(round(passiveScore));
		attempt.setSuccess(success);
		attemptRepository.save(attempt);

		log.info(
				"Validacao do usuario {}: similaridade={}, liveness_ativa={}, liveness_passiva={} (score={}), sucesso={}",
				user.getUsername(),
				String.format(Locale.ROOT, "%.3f", similarity),
				liveness.passed(),
				passivePassed,
				String.format(Locale.ROOT, "%.3f", passiveScore),
				success);
		return new VerifyResult(
				success,
				match,
				round(similarity),
				properties.matchThreshold(),
				liveness.passed(),
				liveness.detail(),
				passiveEvaluated,
				passivePassed,
				round(passiveScore),
				passiveThreshold,
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

	/**
	 * Garante que o usuario realmente executou as poses do cadastro: rosto frontal na etapa
	 * STRAIGHT e giros reais (deslocamento do nariz vs a pose frontal) nas etapas LEFT e RIGHT.
	 */
	private void validateEnrollmentPoses(Map<String, List<ProcessedFrame>> byPhase) {
		List<ProcessedFrame> straight = byPhase.getOrDefault("STRAIGHT", List.of());
		List<ProcessedFrame> left = byPhase.getOrDefault("LEFT", List.of());
		List<ProcessedFrame> right = byPhase.getOrDefault("RIGHT", List.of());
		if (left.size() < MIN_ENROLL_TURN_FRAMES || right.size() < MIN_ENROLL_TURN_FRAMES) {
			throw ApiException.badRequest(
					"O cadastro exige as poses com o rosto virado para os dois lados. Siga as instrucoes na tela e "
							+ "mantenha o giro ate a barra de progresso completar.");
		}
		double baseline = medianNoseRatio(straight);
		if (Math.abs(baseline) > YAW_FRONTAL_MAX) {
			throw ApiException.badRequest(
					"Na primeira etapa, olhe diretamente para a camera com o rosto centralizado e refaca o cadastro.");
		}
		// Girar para a propria esquerda desloca o nariz para a direita da imagem (ratio aumenta)
		if (medianNoseRatio(left) - baseline < MIN_ENROLL_TURN_DEVIATION) {
			throw ApiException.badRequest(
					"Nao confirmamos o giro do rosto para a ESQUERDA. Refaca o cadastro virando o rosto quando "
							+ "solicitado e segurando a pose.");
		}
		if (baseline - medianNoseRatio(right) < MIN_ENROLL_TURN_DEVIATION) {
			throw ApiException.badRequest(
					"Nao confirmamos o giro do rosto para a DIREITA. Refaca o cadastro virando o rosto quando "
							+ "solicitado e segurando a pose.");
		}
	}

	private static double medianNoseRatio(List<ProcessedFrame> frames) {
		return median(frames.stream().mapToDouble(ProcessedFrame::noseRatio).toArray());
	}

	private static double median(double[] values) {
		double[] sorted = values.clone();
		java.util.Arrays.sort(sorted);
		int n = sorted.length;
		if (n == 0) {
			return 0;
		}
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}

	/** Checagem leve de pose para guiar a captura em tempo real (sem extrair embedding). */
	public FaceDtos.PoseResponse pose(MultipartFile frame) {
		BufferedImage image = decode(frame);
		if (image == null) {
			return new FaceDtos.PoseResponse(false, 0, 0, 0, false, 0, 0, 0, false);
		}
		return engine.detectMainFace(image)
				.map(d -> new FaceDtos.PoseResponse(
						true,
						round(d.noseRatio()),
						round(d.noseVerticalRatio()),
						round(d.interocular()),
						d.interocular() >= properties.minEnrollInterocular(),
						round((d.x1() + d.x2()) / 2.0 / image.getWidth()),
						round((d.y1() + d.y2()) / 2.0 / image.getHeight()),
						round(d.height() / (double) image.getHeight()),
						hasSecondFace(image)))
				.orElse(new FaceDtos.PoseResponse(false, 0, 0, 0, false, 0, 0, 0, false));
	}

	private record LivenessCheck(boolean passed, String detail) {}

	/** Desvio assinado da pose no eixo do desafio: positivo quando na direcao pedida. */
	private static double axisDeviation(ProcessedFrame frame, Direction direction, double hBase, double vBase) {
		return switch (direction) {
			// Sem espelhamento: girar para a PROPRIA esquerda desloca o nariz para a direita da
			// imagem (yaw ratio aumenta); para a direita, o inverso.
			case LEFT -> frame.noseRatio() - hBase;
			case RIGHT -> hBase - frame.noseRatio();
			// Inclinar para CIMA projeta o nariz em direcao a linha dos olhos (pitch ratio diminui).
			case UP -> vBase - frame.noseVertical();
			case DOWN -> frame.noseVertical() - vBase;
		};
	}

	/** Desvio minimo exigido conforme o eixo (pitch e mais permissivo que yaw). */
	private static double minDeviationFor(Direction direction) {
		return (direction == Direction.UP || direction == Direction.DOWN)
				? MIN_PITCH_DEVIATION
				: MIN_TURN_DEVIATION;
	}

	/** Valor da pose no eixo relevante do desafio (para a analise de continuidade da trajetoria). */
	private static float axisValue(ProcessedFrame frame, Direction direction) {
		return (direction == Direction.LEFT || direction == Direction.RIGHT)
				? frame.noseRatio()
				: frame.noseVertical();
	}

	private LivenessCheck checkLiveness(
			List<Direction> sequence,
			List<ProcessedFrame> straight,
			List<ProcessedFrame> allProcessed,
			List<float[]> templateVectors) {
		// Linhas de base da pose neutra do PROPRIO usuario (neutraliza o angulo da camera)
		double hBaseline = medianNoseRatio(straight);
		double vBaseline = median(straight.stream().mapToDouble(ProcessedFrame::noseVertical).toArray());
		List<ProcessedFrame> allTurnFrames = new ArrayList<>();

		for (int i = 0; i < sequence.size(); i++) {
			Direction direction = sequence.get(i);
			String phase = "TURN" + (i + 1);
			List<ProcessedFrame> segment = allProcessed.stream()
					.filter(f -> phase.equals(f.phase()))
					.toList();
			if (segment.size() < 3) {
				return new LivenessCheck(
						false, "Movimento " + (i + 1) + " (" + directionLabel(direction) + ") nao detectado");
			}

			// O neutro deve ser observado ANTES dos frames que atingem o desvio pedido.
			double reach = minDeviationFor(direction);
			boolean neutralObserved = false;
			long moved = 0;
			for (ProcessedFrame f : segment) {
				double dev = axisDeviation(f, direction, hBaseline, vBaseline);
				if (Math.abs(dev) <= NEUTRAL_BAND) {
					neutralObserved = true;
				}
				if (neutralObserved && dev >= reach) {
					moved++;
				}
			}
			if (moved < MIN_MOVED_FRAMES) {
				return new LivenessCheck(
						false,
						"O movimento " + (i + 1) + " (" + directionLabel(direction) + ") nao foi confirmado");
			}

			// Trajetoria continua no eixo do desafio: um movimento real nao "teleporta" — saltos
			// bruscos do nariz entre frames consecutivos indicam troca de foto/video no meio
			for (int j = 1; j < segment.size(); j++) {
				if (Math.abs(axisValue(segment.get(j), direction) - axisValue(segment.get(j - 1), direction))
						> MAX_RATIO_JUMP) {
					return new LivenessCheck(false, "Movimento descontinuo detectado durante o desafio");
				}
			}
			allTurnFrames.addAll(segment);
		}

		// A pessoa que girou a cabeca precisa ser a mesma dos templates (mediana, robusta a outliers)
		double turnIdentity = median(allTurnFrames.stream()
				.mapToDouble(f -> bestSimilarity(f.embedding(), templateVectors))
				.toArray());
		if (turnIdentity < MIN_TURN_IDENTITY) {
			return new LivenessCheck(false, "Identidade inconsistente durante os movimentos");
		}
		String executed = sequence.stream().map(FaceService::directionLabel).reduce((a, b) -> a + " e " + b).orElse("");
		return new LivenessCheck(true, "Movimentos " + executed + " confirmados");
	}

	private static String directionLabel(Direction direction) {
		return switch (direction) {
			case LEFT -> "para a esquerda";
			case RIGHT -> "para a direita";
			case UP -> "para cima";
			case DOWN -> "para baixo";
		};
	}

	/**
	 * Decodifica cada frame, detecta o rosto principal e extrai o embedding. Frames ruins sao
	 * descartados, com contadores por motivo. Regras:
	 * - exatamente UMA pessoa no quadro (um segundo rosto relevante invalida o frame);
	 * - nitidez minima do rosto alinhado (variancia do Laplaciano) contra borrao;
	 * - resolucao minima (interocular) apenas nos frames frontais, pois a rotacao da
	 *   cabeca encolhe a distancia interocular projetada.
	 */
	private ProcessResult processFrames(
			List<MultipartFile> frames,
			List<String> phases,
			int minStraightInterocular,
			boolean computePassive) {
		if (frames == null || frames.isEmpty()) {
			throw ApiException.badRequest("Nenhum frame recebido");
		}
		if (phases == null || phases.size() != frames.size()) {
			throw ApiException.badRequest("Cada frame precisa da fase de captura correspondente");
		}
		if (frames.size() > MAX_FRAMES_PER_REQUEST) {
			throw ApiException.badRequest("Excesso de frames na requisicao");
		}
		List<String> normalizedPhases = validatePhaseOrder(
				phases, computePassive ? List.of("STRAIGHT", "TURN1", "TURN2") : List.of("STRAIGHT", "LEFT", "RIGHT"));
		boolean passive = computePassive && engine.isPassiveLivenessEnabled();
		List<ProcessedFrame> processed = new ArrayList<>();
		int multiFace = 0;
		int blurry = 0;
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
			if (hasSecondFace(image)) {
				multiFace++;
				continue;
			}
			String phase = normalizedPhases.get(i);
			if ("STRAIGHT".equals(phase) && detection.interocular() < minStraightInterocular) {
				continue;
			}
			BufferedImage aligned = engine.alignFace(image, detection);
			if (sharpness(aligned) < properties.minSharpness()) {
				blurry++;
				continue;
			}
			// Liveness passiva so nos frames frontais (rosto de frente da a leitura de textura mais fiel)
			double passiveScore = (passive && "STRAIGHT".equals(phase))
					? engine.passiveLivenessScore(image, detection)
					: 1.0;
			float[] embedding = engine.embedAligned(aligned);
			processed.add(new ProcessedFrame(
					phase, embedding, detection.noseRatio(), detection.noseVerticalRatio(), passiveScore));
		}
		return new ProcessResult(processed, multiFace, blurry);
	}

	/** Valida todos os rotulos antes de decodificar imagens, inclusive os de frames descartados. */
	private static List<String> validatePhaseOrder(List<String> phases, List<String> allowedOrder) {
		List<String> normalized = new ArrayList<>(phases.size());
		int previousPhase = -1;
		for (String value : phases) {
			String phase = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
			int currentPhase = allowedOrder.indexOf(phase);
			if (currentPhase < 0) {
				throw ApiException.badRequest("Fase de captura invalida: use " + String.join(", ", allowedOrder));
			}
			if (currentPhase < previousPhase) {
				throw ApiException.badRequest("As fases de captura devem seguir a ordem " + String.join(", ", allowedOrder));
			}
			previousPhase = currentPhase;
			normalized.add(phase);
		}
		return normalized;
	}

	/**
	 * Ha uma segunda pessoa no quadro? Detecta uma unica vez, ordena por area e compara o SEGUNDO
	 * maior rosto ao principal. Nao usa igualdade de referencia (o bug anterior comparava objetos
	 * de chamadas diferentes de detect(), o que fazia qualquer rosto sozinho ser tratado como dois).
	 */
	private boolean hasSecondFace(BufferedImage image) {
		double[] areas = engine.detect(image).stream()
				.filter(d -> d.score() >= properties.minDetectionScore())
				.mapToDouble(Detection::area)
				.boxed()
				.sorted(java.util.Comparator.reverseOrder())
				.mapToDouble(Double::doubleValue)
				.toArray();
		return areas.length >= 2 && areas[1] >= areas[0] * SECOND_FACE_AREA_RATIO;
	}

	/** Mensagem de erro explicando o principal motivo de descarte dos frames. */
	private static String qualityMessage(ProcessResult result) {
		if (result.multiFace() >= 3) {
			return "Detectamos mais de uma pessoa no video. Apenas o titular deve aparecer no quadro.";
		}
		if (result.blurry() >= 3) {
			return "A imagem ficou tremida ou desfocada. Mantenha a camera estavel, limpe a lente e tente novamente.";
		}
		return "Nao foi possivel capturar o rosto com qualidade suficiente. Procure um lugar bem iluminado, "
				+ "centralize o rosto no oval e tente novamente.";
	}

	/**
	 * Nitidez pela variancia do Laplaciano (4-vizinhos) sobre o rosto alinhado em tons de cinza.
	 * Rostos nitidos ficam bem acima de 100; borrao de movimento/foco cai para menos de 40.
	 */
	private static double sharpness(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		double[] gray = new double[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			int p = pixels[i];
			gray[i] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
		}
		double sum = 0;
		double sumSquares = 0;
		int count = 0;
		for (int y = 1; y < height - 1; y++) {
			for (int x = 1; x < width - 1; x++) {
				int idx = y * width + x;
				double laplacian = 4 * gray[idx]
						- gray[idx - 1]
						- gray[idx + 1]
						- gray[idx - width]
						- gray[idx + width];
				sum += laplacian;
				sumSquares += laplacian * laplacian;
				count++;
			}
		}
		double mean = sum / count;
		return sumSquares / count - mean * mean;
	}

	private void checkRateLimit(Long userId) {
		enforceRate("verify:" + userId, MAX_VERIFY_PER_MINUTE);
	}

	/**
	 * Janela deslizante de 1 minuto por chave. Corrige o vazamento de memoria anterior: baldes
	 * vazios sao removidos do mapa apos a poda.
	 */
	private void enforceRate(String key, int maxPerMinute) {
		var bucket = rateBuckets.computeIfAbsent(
				key, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
		long now = System.currentTimeMillis();
		bucket.removeIf(t -> now - t > 60_000);
		if (bucket.size() >= maxPerMinute) {
			throw new ApiException(
					org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
					"Muitas requisicoes. Aguarde um minuto e tente novamente.");
		}
		bucket.add(now);
		// Evita crescimento ilimitado do mapa: remove a chave se o balde esvaziar entre chamadas.
		rateBuckets.values().removeIf(java.util.Collection::isEmpty);
	}

	private BufferedImage decode(MultipartFile file) {
		return FrameDecoder.decode(file);
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
