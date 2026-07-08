package com.ulisses.face;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ulisses.auth.User;
import com.ulisses.common.ApiException;
import com.ulisses.face.ChallengeService.Challenge;
import com.ulisses.face.ChallengeService.Direction;
import com.ulisses.face.FaceDtos.EnrollResult;
import com.ulisses.face.FaceDtos.VerifyResult;
import com.ulisses.face.engine.Detection;
import com.ulisses.face.engine.FaceEmbedder;
import com.ulisses.face.engine.FaceEngine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Testes dos DOIS fluxos centrais — cadastro (enroll) e validacao (verify) — exercitando a logica
 * de decisao real do servico (limiares, mediana, prova de vida, poses, rate limit). O motor de
 * inferencia ONNX e mockado: os embeddings e as poses sao controlados, tornando os testes rapidos
 * e deterministicos, sem depender dos modelos nem de fotos reais.
 */
class FaceServiceTest {

	// Dois "rostos" sinteticos: vecA e vecB sao ortogonais (cosseno 0), simulando pessoas distintas.
	private static final float[] FACE_A =
			FaceEmbedder.normalize(constant(FaceEmbedder.EMBEDDING_SIZE, 1f));
	private static final float[] FACE_B = FaceEmbedder.normalize(alternating(FaceEmbedder.EMBEDDING_SIZE));

	private FaceEngine engine;
	private FaceService faceService;
	private ChallengeService challengeService;
	private User user;

	private final List<FaceTemplate> templateStore = new ArrayList<>();
	private final Deque<Detection> detQueue = new ArrayDeque<>();
	private final Deque<float[]> embQueue = new ArrayDeque<>();

	private byte[] jpegBytes;
	private BufferedImage sharpAligned;

	@BeforeEach
	void setUp() throws Exception {
		engine = mock(FaceEngine.class);
		FaceTemplateRepository templateRepo = mock(FaceTemplateRepository.class);
		VerificationAttemptRepository attemptRepo = mock(VerificationAttemptRepository.class);

		FaceProperties props = new FaceProperties(
				null, null, null,
				0.45, // matchThreshold
				0.60, // minDetectionScore
				80, // minFaceSize
				60, // minEnrollInterocular
				40, // minSharpness
				120, // challengeTtlSeconds
				// liveness passiva desabilitada nos testes unitarios (engine mockado)
				new FaceProperties.PassiveLiveness(false, null, null, 2, 0.55));
		challengeService = new ChallengeService(props);
		faceService = new FaceService(engine, props, templateRepo, attemptRepo, challengeService);

		user = mock(User.class);
		lenient().when(user.getId()).thenReturn(1L);
		lenient().when(user.getUsername()).thenReturn("teste");

		// Repositorio de templates em memoria (roundtrip real do embedding via FaceTemplate).
		when(templateRepo.save(any())).thenAnswer(inv -> {
			templateStore.add(inv.getArgument(0));
			return inv.getArgument(0);
		});
		when(templateRepo.findByUserId(anyLong())).thenAnswer(inv -> new ArrayList<>(templateStore));
		doAnswer(inv -> {
			templateStore.clear();
			return null;
		}).when(templateRepo).deleteByUserId(anyLong());
		when(attemptRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		// Motor mockado: deteccao e embedding saem das filas controladas por cada teste.
		when(engine.detectMainFace(any())).thenAnswer(inv -> java.util.Optional.of(detQueue.poll()));
		when(engine.embedAligned(any())).thenAnswer(inv -> embQueue.poll());
		lenient().when(engine.alignFace(any(), any())).thenAnswer(inv -> sharpAligned);
		// Por padrao, um unico rosto no quadro (sem segunda pessoa).
		lenient().when(engine.detect(any())).thenReturn(List.of());

		jpegBytes = jpeg();
		sharpAligned = checkerboard(); // nitidez alta -> passa no filtro de borrao
	}

	// ---------- FLUXO 1: CADASTRO ----------

	@Test
	@DisplayName("cadastro com poses corretas salva os templates e reporta consistencia")
	void enrollWithProperPosesSucceeds() {
		EnrollResult result = enrollBaseline();

		assertEquals(10, result.framesUsed());
		assertEquals(1.0, result.consistency(), 1e-6); // mesmo rosto em todos os frames
		assertEquals(3, templateStore.size()); // STRAIGHT, LEFT, RIGHT
	}

	@Test
	@DisplayName("cadastro sem virar o rosto e recusado")
	void enrollWithoutHeadTurnIsRejected() {
		// STRAIGHT + LEFT + RIGHT, porem TODOS frontais (nariz centralizado): nao houve giro.
		enqueueFrames(0.5, 0.5, 4, FACE_A);
		enqueueFrames(0.5, 0.5, 3, FACE_A);
		enqueueFrames(0.5, 0.5, 3, FACE_A);
		List<MultipartFile> frames = frames(10);
		List<String> phases = phases("STRAIGHT", 4, "LEFT", 3, "RIGHT", 3);

		ApiException ex =
				assertThrows(ApiException.class, () -> faceService.enroll(user, frames, phases));
		assertTrue(ex.getMessage().contains("ESQUERDA"));
		assertTrue(templateStore.isEmpty());
	}

	@Test
	@DisplayName("cadastro com mais de uma pessoa no quadro e recusado")
	void enrollWithMultipleFacesIsRejected() {
		// Dois rostos grandes detectados em cada frame -> todos descartados por multiface.
		Detection big1 = detection(0.5, 0.5, 200);
		Detection big2 = detection(0.5, 0.5, 200);
		when(engine.detect(any())).thenReturn(List.of(big1, big2));
		enqueueFrames(0.5, 0.5, 4, FACE_A);
		enqueueFrames(0.66, 0.5, 3, FACE_A);
		enqueueFrames(0.34, 0.5, 3, FACE_A);
		List<MultipartFile> frames = frames(10);
		List<String> phases = phases("STRAIGHT", 4, "LEFT", 3, "RIGHT", 3);

		ApiException ex =
				assertThrows(ApiException.class, () -> faceService.enroll(user, frames, phases));
		assertTrue(ex.getMessage().contains("mais de uma pessoa"));
	}

	// ---------- FLUXO 2: VALIDACAO ----------

	@Test
	@DisplayName("validacao do MESMO rosto com prova de vida correta e aprovada")
	void verifySameFaceWithLivenessSucceeds() {
		enrollBaseline();
		Challenge challenge = challengeService.create(1L);

		VerifyResult result = runVerify(challenge, FACE_A, true);

		assertTrue(result.success());
		assertTrue(result.match());
		assertTrue(result.liveness());
		assertEquals(1.0, result.similarity(), 1e-3); // rosto identico ao cadastrado
	}

	@Test
	@DisplayName("validacao de rosto DIFERENTE e recusada na identidade")
	void verifyDifferentFaceIsRejected() {
		enrollBaseline();
		Challenge challenge = challengeService.create(1L);

		VerifyResult result = runVerify(challenge, FACE_B, true);

		assertFalse(result.match()); // outra pessoa
		assertFalse(result.success());
		assertTrue(result.similarity() < 0.45);
	}

	@Test
	@DisplayName("validacao sem executar o movimento reprova na prova de vida")
	void verifyWithoutHeadTurnFailsLiveness() {
		enrollBaseline();
		Challenge challenge = challengeService.create(1L);

		// Rosto certo, mas os frames de giro permanecem frontais (sem movimento).
		VerifyResult result = runVerify(challenge, FACE_A, false);

		assertTrue(result.match()); // identidade confere
		assertFalse(result.liveness()); // mas nao houve giro
		assertFalse(result.success());
	}

	@Test
	@DisplayName("desafio VERTICAL (cima/baixo) e aprovado quando o usuario inclina a cabeca")
	void verifyVerticalChallengeSucceeds() {
		enrollBaseline();
		// O sorteio e aleatorio entre 4 direcoes; insiste ate a sequencia conter um movimento vertical
		Challenge challenge = challengeService.create(1L);
		for (int i = 0; i < 200 && challenge.sequence().stream()
				.noneMatch(d -> d == Direction.UP || d == Direction.DOWN); i++) {
			challenge = challengeService.create(1L);
		}
		assertTrue(challenge.sequence().stream()
				.anyMatch(d -> d == Direction.UP || d == Direction.DOWN));

		VerifyResult result = runVerify(challenge, FACE_A, true);

		assertTrue(result.liveness());
		assertTrue(result.success());
	}

	@Test
	@DisplayName("desafio vertical NAO e aprovado por um giro horizontal (eixo errado)")
	void verticalChallengeRejectsHorizontalMovement() {
		enrollBaseline();
		Challenge challenge = challengeService.create(1L);
		for (int i = 0; i < 200 && challenge.sequence().get(0) != Direction.UP; i++) {
			challenge = challengeService.create(1L);
		}
		assertEquals(Direction.UP, challenge.sequence().get(0));

		// Usuario gira para a esquerda (yaw) quando o desafio pede CIMA (pitch)
		List<String> phaseLabels = new ArrayList<>();
		enqueueFrames(0.5, 0.5, 4, FACE_A);
		for (int i = 0; i < 4; i++) phaseLabels.add("STRAIGHT");
		for (int i = 0; i < challenge.sequence().size(); i++) {
			enqueueFrames(0.5, 0.5, 2, FACE_A); // neutro
			enqueueFrames(0.68, 0.5, 3, FACE_A); // movimento HORIZONTAL, pitch neutro (eixo errado)
			for (int j = 0; j < 5; j++) phaseLabels.add("TURN" + (i + 1));
		}
		VerifyResult result =
				faceService.verify(user, challenge.id(), frames(phaseLabels.size()), phaseLabels);

		assertFalse(result.liveness()); // eixo errado: movimento 1 (para cima) nao confirmado
		assertFalse(result.success());
	}

	@Test
	@DisplayName("ATAQUE: fotos ja viradas (sem frame neutro) sao recusadas pelo movimento observado")
	void staticTurnedPhotosAreRejected() {
		enrollBaseline();
		Challenge challenge = challengeService.create(1L);

		// Atacante rotula fotos JA no desvio como TURN, sem nenhum frame neutro (sem transicao).
		List<String> phaseLabels = new ArrayList<>();
		enqueueFrames(0.5, 0.5, 4, FACE_A);
		for (int i = 0; i < 4; i++) phaseLabels.add("STRAIGHT");
		List<Direction> seq = challenge.sequence();
		for (int i = 0; i < seq.size(); i++) {
			double h = 0.5;
			double v = 0.5;
			switch (seq.get(i)) {
				case LEFT -> h = 0.68;
				case RIGHT -> h = 0.32;
				case UP -> v = 0.32;
				case DOWN -> v = 0.68;
			}
			enqueueFrames(h, v, 5, FACE_A); // TODOS ja no desvio, nenhum neutro
			for (int j = 0; j < 5; j++) phaseLabels.add("TURN" + (i + 1));
		}
		VerifyResult result =
				faceService.verify(user, challenge.id(), frames(phaseLabels.size()), phaseLabels);

		assertFalse(result.liveness()); // reprovado: movimento nao observado ao vivo
		assertFalse(result.success());
	}

	@Test
	@DisplayName("validacao com liveness passiva BAIXA (spoof) e recusada mesmo com rosto correto")
	void verifyRejectedByPassiveLiveness() {
		enrollBaseline();
		when(engine.isPassiveLivenessEnabled()).thenReturn(true);
		when(engine.passiveLivenessScore(any(), any())).thenReturn(0.20); // abaixo do limiar 0.55
		Challenge challenge = challengeService.create(1L);

		VerifyResult result = runVerify(challenge, FACE_A, true);

		assertTrue(result.match()); // identidade confere
		assertTrue(result.liveness()); // movimento confere
		assertTrue(result.passiveEvaluated());
		assertFalse(result.passivePassed()); // textura reprovou (foto/tela)
		assertFalse(result.success());
	}

	@Test
	@DisplayName("validacao com liveness passiva ALTA (rosto vivo) e aprovada")
	void verifyAcceptedWithHighPassiveLiveness() {
		enrollBaseline();
		when(engine.isPassiveLivenessEnabled()).thenReturn(true);
		when(engine.passiveLivenessScore(any(), any())).thenReturn(0.95); // acima do limiar
		Challenge challenge = challengeService.create(1L);

		VerifyResult result = runVerify(challenge, FACE_A, true);

		assertTrue(result.passiveEvaluated());
		assertTrue(result.passivePassed());
		assertTrue(result.success());
	}

	@Test
	@DisplayName("rate limit bloqueia apos 6 tentativas por minuto")
	void verifyRateLimitBlocksAfterSix() {
		// As 6 primeiras chamadas passam pelo rate limit (e falham no desafio invalido);
		// a 7a e bloqueada por excesso de tentativas.
		for (int i = 0; i < 6; i++) {
			assertThrows(
					ApiException.class,
					() -> faceService.verify(user, "desafio-invalido", frames(0), phases()));
		}
		ApiException ex = assertThrows(
				ApiException.class,
				() -> faceService.verify(user, "desafio-invalido", frames(0), phases()));
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
		assertTrue(ex.getMessage().contains("Muitas requisicoes"));
	}

	// ---------- helpers ----------

	/** Cadastro valido padrao: 4 frontais + 3 giros esquerda + 3 giros direita, todos rosto A. */
	private EnrollResult enrollBaseline() {
		enqueueFrames(0.5, 0.5, 4, FACE_A); // STRAIGHT
		enqueueFrames(0.66, 0.5, 3, FACE_A); // LEFT: nariz desloca para a direita da imagem
		enqueueFrames(0.34, 0.5, 3, FACE_A); // RIGHT: nariz desloca para a esquerda da imagem
		return faceService.enroll(
				user, frames(10), phases("STRAIGHT", 4, "LEFT", 3, "RIGHT", 3));
	}

	/**
	 * Executa uma validacao seguindo a sequencia sorteada no desafio (qualquer uma das 4 direcoes).
	 * Quando {@code doTurn} e falso, os frames dos movimentos permanecem frontais (para testar
	 * falha de prova de vida).
	 */
	private VerifyResult runVerify(Challenge challenge, float[] face, boolean doTurn) {
		List<String> phaseLabels = new ArrayList<>();
		enqueueFrames(0.5, 0.5, 4, face); // STRAIGHT (pose neutra: yaw 0.5, pitch 0.5)
		for (int i = 0; i < 4; i++) {
			phaseLabels.add("STRAIGHT");
		}
		List<Direction> seq = challenge.sequence();
		for (int i = 0; i < seq.size(); i++) {
			double h = 0.5;
			double v = 0.5;
			if (doTurn) {
				switch (seq.get(i)) {
					case LEFT -> h = 0.68;
					case RIGHT -> h = 0.32;
					case UP -> v = 0.32;
					case DOWN -> v = 0.68;
				}
				// Rajada continua: 2 frames NEUTROS (partida do centro) + 3 no desvio (movimento
				// observado), como o frontend passa a enviar.
				enqueueFrames(0.5, 0.5, 2, face);
				enqueueFrames(h, v, 3, face);
				for (int j = 0; j < 5; j++) phaseLabels.add("TURN" + (i + 1));
			} else {
				enqueueFrames(0.5, 0.5, 3, face); // sem movimento
				for (int j = 0; j < 3; j++) phaseLabels.add("TURN" + (i + 1));
			}
		}
		return faceService.verify(user, challenge.id(), frames(phaseLabels.size()), phaseLabels);
	}

	private void enqueueFrames(double noseRatio, double noseVertical, int count, float[] embedding) {
		for (int i = 0; i < count; i++) {
			detQueue.add(detection(noseRatio, noseVertical, 100));
			embQueue.add(embedding);
		}
	}

	/**
	 * Geometria sintetica: olhos em x=0/x=100 y=100 (interocular 100), boca em y=160.
	 * noseRatio posiciona o nariz no eixo horizontal; noseVertical entre olhos (0) e boca (1).
	 */
	private static Detection detection(double noseRatio, double noseVertical, float interocular) {
		float noseY = (float) (100 + noseVertical * 60);
		float[] kps = {
			0, 100, interocular, 100, (float) (noseRatio * interocular), noseY, 30, 160, 70, 160
		};
		return new Detection(0.9f, 0, 50, 200, 250, kps);
	}

	private List<MultipartFile> frames(int count) {
		List<MultipartFile> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(new MockMultipartFile("frames", "f" + i + ".jpg", "image/jpeg", jpegBytes));
		}
		return list;
	}

	/** Constroi a lista de fases: phases("STRAIGHT",4,"LEFT",3) -> 4x STRAIGHT + 3x LEFT. */
	private static List<String> phases(Object... spec) {
		List<String> list = new ArrayList<>();
		for (int i = 0; i < spec.length; i += 2) {
			String phase = (String) spec[i];
			int count = (Integer) spec[i + 1];
			for (int j = 0; j < count; j++) {
				list.add(phase);
			}
		}
		return list;
	}

	private static byte[] jpeg() throws Exception {
		BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	/** Padrao xadrez 112x112: alta variancia do Laplaciano -> nitido (passa no filtro de borrao). */
	private static BufferedImage checkerboard() {
		int size = 112;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				img.setRGB(x, y, ((x + y) % 2 == 0) ? 0xFFFFFF : 0x000000);
			}
		}
		return img;
	}

	private static float[] constant(int size, float value) {
		float[] v = new float[size];
		java.util.Arrays.fill(v, value);
		return v;
	}

	private static float[] alternating(int size) {
		float[] v = new float[size];
		for (int i = 0; i < size; i++) {
			v[i] = (i % 2 == 0) ? 1f : -1f;
		}
		return v;
	}
}
