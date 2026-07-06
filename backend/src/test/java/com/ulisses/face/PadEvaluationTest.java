package com.ulisses.face;

import ai.onnxruntime.OrtEnvironment;
import com.ulisses.face.engine.Detection;
import com.ulisses.face.engine.FaceDetector;
import com.ulisses.face.engine.PassiveLivenessDetector;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Harness de avaliacao da liveness passiva segundo as metricas da ISO/IEC 30107-3:
 *
 * <ul>
 *   <li><b>APCER</b> (Attack Presentation Classification Error Rate): fracao de ATAQUES aceitos
 *       como reais — quanto menor, mais seguro.
 *   <li><b>BPCER</b> (Bona Fide Presentation Classification Error Rate): fracao de rostos REAIS
 *       rejeitados — quanto menor, melhor a experiencia do usuario legitimo.
 *   <li><b>ACER</b> = (APCER + BPCER) / 2.
 * </ul>
 *
 * Este e o instrumento que CALIBRA (limiar e indice de classe "vivo") e VALIDA a eficacia do
 * anti-spoofing. Ele NAO roda no build normal: e ativado apontando um dataset rotulado via
 * propriedade do sistema. Estrutura esperada do dataset:
 *
 * <pre>
 *   &lt;dataset&gt;/bonafide/*.jpg   (rostos reais)
 *   &lt;dataset&gt;/attack/*.jpg     (fotos, telas, mascaras, deepfakes impressos, etc.)
 * </pre>
 *
 * Execucao:
 *
 * <pre>
 *   mvn -Dtest=PadEvaluationTest \
 *       -Dpad.dataset=/caminho/do/dataset \
 *       -Dpad.models=./models test
 * </pre>
 *
 * Nota honesta: passar bem aqui indica que o modelo discrimina no SEU dataset — nao substitui a
 * certificacao em laboratorio acreditado (iBeta / ISO 30107-3), que usa instrumentos de ataque
 * padronizados.
 */
class PadEvaluationTest {

	@Test
	@DisplayName("APCER/BPCER/ACER da liveness passiva sobre um dataset rotulado")
	void evaluate() throws Exception {
		String datasetPath = System.getProperty("pad.dataset");
		String modelsPath = System.getProperty("pad.models", "./models");
		Assumptions.assumeTrue(
				datasetPath != null,
				"Defina -Dpad.dataset=<pasta com bonafide/ e attack/> para rodar a avaliacao");

		Path bonafideDir = Path.of(datasetPath, "bonafide");
		Path attackDir = Path.of(datasetPath, "attack");
		Assumptions.assumeTrue(
				Files.isDirectory(bonafideDir) && Files.isDirectory(attackDir),
				"O dataset precisa das subpastas bonafide/ e attack/");

		OrtEnvironment env = OrtEnvironment.getEnvironment();
		try (FaceDetector detector = new FaceDetector(
						env, Path.of(modelsPath, "scrfd_det_10g.onnx"), 0.4f);
				PassiveLivenessDetector pad = new PassiveLivenessDetector(
						env,
						Path.of(modelsPath, "minifasnet_v2.onnx"),
						Path.of(modelsPath, "minifasnet_v1se.onnx"),
						Integer.getInteger("pad.liveIndex", 2))) {

			double[] bonafide = scoreAll(detector, pad, bonafideDir);
			double[] attack = scoreAll(detector, pad, attackDir);

			System.out.println("\n=== Avaliacao de liveness passiva (ISO/IEC 30107-3) ===");
			System.out.printf(
					Locale.ROOT,
					"Amostras: bonafide=%d, attack=%d%n",
					bonafide.length,
					attack.length);
			System.out.printf(
					Locale.ROOT,
					"Score medio: bonafide=%.3f, attack=%.3f%n",
					mean(bonafide),
					mean(attack));
			System.out.printf("%-10s %-10s %-10s %-10s%n", "threshold", "APCER", "BPCER", "ACER");

			double bestThreshold = 0;
			double bestAcer = Double.MAX_VALUE;
			for (double t = 0.05; t <= 0.95; t += 0.05) {
				double apcer = rate(attack, t, true); // ataque com score >= t (aceito) = erro
				double bpcer = rate(bonafide, t, false); // real com score < t (rejeitado) = erro
				double acer = (apcer + bpcer) / 2;
				System.out.printf(Locale.ROOT, "%-10.2f %-10.4f %-10.4f %-10.4f%n", t, apcer, bpcer, acer);
				if (acer < bestAcer) {
					bestAcer = acer;
					bestThreshold = t;
				}
			}
			System.out.printf(
					Locale.ROOT,
					"%nMelhor limiar (menor ACER): threshold=%.2f, ACER=%.4f%n",
					bestThreshold,
					bestAcer);
			System.out.println(
					"Ajuste ulisses.face.passive-liveness.threshold com este valor apos analisar o trade-off.");
		}
	}

	private static double[] scoreAll(
			FaceDetector detector, PassiveLivenessDetector pad, Path dir) throws Exception {
		List<Double> scores = new ArrayList<>();
		File[] files = dir.toFile().listFiles();
		if (files == null) {
			return new double[0];
		}
		for (File file : files) {
			BufferedImage image = readRgb(file);
			if (image == null) {
				continue;
			}
			Optional<Detection> face = detector.detect(image).stream()
					.max((a, b) -> Float.compare(a.area(), b.area()));
			if (face.isEmpty()) {
				continue;
			}
			scores.add(pad.score(image, face.get()));
		}
		return scores.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/** Fracao de amostras aceitas (score>=t) quando attack=true, ou rejeitadas (score<t) quando false. */
	private static double rate(double[] scores, double threshold, boolean attack) {
		if (scores.length == 0) {
			return 0;
		}
		long errors = 0;
		for (double s : scores) {
			boolean accepted = s >= threshold;
			if (attack ? accepted : !accepted) {
				errors++;
			}
		}
		return (double) errors / scores.length;
	}

	private static double mean(double[] values) {
		if (values.length == 0) {
			return 0;
		}
		double sum = 0;
		for (double v : values) {
			sum += v;
		}
		return sum / values.length;
	}

	private static BufferedImage readRgb(File file) {
		try {
			BufferedImage img = ImageIO.read(file);
			if (img == null) {
				return null;
			}
			if (img.getType() == BufferedImage.TYPE_INT_RGB) {
				return img;
			}
			BufferedImage rgb =
					new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(img, 0, 0, null);
			return rgb;
		} catch (Exception e) {
			return null;
		}
	}
}
