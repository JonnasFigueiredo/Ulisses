package com.ulisses.face.engine;

import ai.onnxruntime.OrtEnvironment;
import com.ulisses.common.ApiException;
import com.ulisses.face.FaceProperties;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fachada do pipeline de reconhecimento facial local: deteccao (SCRFD) -> alinhamento (Umeyama)
 * -> embedding (ArcFace). Os modelos sao carregados em background no start da aplicacao.
 */
@Component
public class FaceEngine {

	public enum Status {
		LOADING,
		READY,
		ERROR
	}

	private static final Logger log = LoggerFactory.getLogger(FaceEngine.class);

	private final FaceProperties properties;
	private final OrtEnvironment env = OrtEnvironment.getEnvironment();

	private volatile Status status = Status.LOADING;
	private volatile String errorMessage;
	private volatile FaceDetector detector;
	private volatile FaceEmbedder embedder;
	private volatile PassiveLivenessDetector passiveDetector;

	public FaceEngine(FaceProperties properties) {
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void initializeInBackground() {
		Thread loader = new Thread(this::loadModels, "face-engine-loader");
		loader.setDaemon(true);
		loader.start();
	}

	private void loadModels() {
		try {
			Path modelsDir = Path.of(properties.modelsDir());
			Path detectionModel = ModelStore.ensureModel(
					modelsDir, "scrfd_det_10g.onnx", properties.detectionModelUrl());
			Path recognitionModel = ModelStore.ensureModel(
					modelsDir, "arcface_w600k_r50.onnx", properties.recognitionModelUrl());
			this.detector = new FaceDetector(env, detectionModel, 0.5f);
			this.embedder = new FaceEmbedder(env, recognitionModel);

			FaceProperties.PassiveLiveness passive = properties.passiveLiveness();
			if (passive != null && passive.enabled()) {
				Path padV2 = ModelStore.ensureModel(modelsDir, "minifasnet_v2.onnx", passive.modelV2Url());
				Path padV1se =
						ModelStore.ensureModel(modelsDir, "minifasnet_v1se.onnx", passive.modelV1seUrl());
				this.passiveDetector =
						new PassiveLivenessDetector(env, padV2, padV1se, passive.liveClassIndex());
				log.info("Liveness passiva (anti-spoofing MiniFASNet) carregada");
			}

			this.status = Status.READY;
			log.info("Engine de reconhecimento facial pronta (execucao 100% local via ONNX Runtime)");
		} catch (Exception e) {
			this.status = Status.ERROR;
			this.errorMessage = e.getMessage();
			log.error("Falha ao inicializar a engine de reconhecimento facial", e);
		}
	}

	public Status getStatus() {
		return status;
	}

	public List<Detection> detect(BufferedImage image) {
		ensureReady();
		return detector.detect(image);
	}

	/** Retorna o maior rosto da imagem que atenda aos criterios minimos de qualidade. */
	public Optional<Detection> detectMainFace(BufferedImage image) {
		return detect(image).stream()
				.filter(d -> d.score() >= properties.minDetectionScore())
				.filter(d -> Math.min(d.width(), d.height()) >= properties.minFaceSize())
				.max(Comparator.comparingDouble(Detection::area));
	}

	public float[] embed(BufferedImage image, Detection detection) {
		return embedAligned(alignFace(image, detection));
	}

	public BufferedImage alignFace(BufferedImage image, Detection detection) {
		ensureReady();
		return FaceAligner.align(image, detection.kps());
	}

	public float[] embedAligned(BufferedImage alignedFace) {
		ensureReady();
		return embedder.embed(alignedFace);
	}

	public boolean isPassiveLivenessEnabled() {
		return passiveDetector != null;
	}

	/** Pontuacao de liveness passiva em [0,1] (1 = provavelmente vivo). */
	public double passiveLivenessScore(BufferedImage image, Detection detection) {
		ensureReady();
		if (passiveDetector == null) {
			throw new IllegalStateException("Liveness passiva desabilitada");
		}
		return passiveDetector.score(image, detection);
	}

	private void ensureReady() {
		switch (status) {
			case READY -> {}
			case LOADING -> throw ApiException.unavailable(
					"Os modelos de reconhecimento facial ainda estao sendo carregados. Tente novamente em instantes.");
			case ERROR -> throw ApiException.unavailable(
					"Engine de reconhecimento facial indisponivel: " + errorMessage);
		}
	}

	@PreDestroy
	public void shutdown() {
		try {
			if (detector != null) {
				detector.close();
			}
			if (embedder != null) {
				embedder.close();
			}
			if (passiveDetector != null) {
				passiveDetector.close();
			}
		} catch (Exception e) {
			log.warn("Erro ao liberar sessoes ONNX", e);
		}
	}
}
