package com.ulisses.face.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Liveness passiva (anti-spoofing por textura) usando o ensemble MiniFASNet do projeto
 * Silent-Face-Anti-Spoofing (minivision, Apache-2.0). Dois modelos com recortes de escala
 * diferentes (2.7 e 4.0) analisam a textura ao redor do rosto para distinguir uma pessoa real
 * de uma foto impressa ou de uma tela. A pontuacao final e a media do softmax da classe "vivo".
 *
 * <p>ATENCAO: o indice da classe "vivo" e o limiar de decisao dependem do arquivo de modelo e
 * PRECISAM ser calibrados/validados contra um dataset real de ataques (ver PadEvaluationTest).
 * Esta camada aproxima o comportamento esperado, mas NAO e uma solucao certificada ISO 30107-3.
 */
public final class PassiveLivenessDetector implements AutoCloseable {

	private static final int SIZE = 80;
	private static final float SCALE_V2 = 2.7f;
	private static final float SCALE_V1SE = 4.0f;

	private final OrtEnvironment env;
	private final OrtSession modelV2;
	private final OrtSession modelV1se;
	private final String inputV2;
	private final String inputV1se;
	private final int liveClassIndex;

	public PassiveLivenessDetector(
			OrtEnvironment env, Path modelV2Path, Path modelV1sePath, int liveClassIndex)
			throws OrtException {
		if (liveClassIndex < 0 || liveClassIndex > 2) {
			// Falha alto: um indice invalido leria a classe errada e aprovaria spoofs silenciosamente.
			throw new IllegalArgumentException(
					"live-class-index deve estar entre 0 e 2 (softmax de 3 classes): " + liveClassIndex);
		}
		this.env = env;
		this.modelV2 = env.createSession(modelV2Path.toString(), new OrtSession.SessionOptions());
		this.modelV1se = env.createSession(modelV1sePath.toString(), new OrtSession.SessionOptions());
		this.inputV2 = modelV2.getInputNames().iterator().next();
		this.inputV1se = modelV1se.getInputNames().iterator().next();
		this.liveClassIndex = liveClassIndex;
	}

	/** Pontuacao de liveness passiva em [0,1] para o rosto detectado (1 = provavelmente vivo). */
	public synchronized double score(BufferedImage image, Detection face) {
		float[] a = softmax(infer(modelV2, inputV2, image, face, SCALE_V2));
		float[] b = softmax(infer(modelV1se, inputV1se, image, face, SCALE_V1SE));
		return (a[liveClassIndex] + b[liveClassIndex]) / 2.0;
	}

	private float[] infer(
			OrtSession session, String inputName, BufferedImage image, Detection face, float scale) {
		int[] box = expandBox(image.getWidth(), image.getHeight(), face, scale);
		BufferedImage crop = cropResize(image, box);
		float[] blob = toBgrBlob(crop);
		try (OnnxTensor input = OnnxTensor.createTensor(
						env, FloatBuffer.wrap(blob), new long[] {1, 3, SIZE, SIZE});
				OrtSession.Result result = session.run(Map.of(inputName, input))) {
			float[][] out = (float[][]) result.get(0).getValue();
			return out[0];
		} catch (OrtException e) {
			throw new IllegalStateException("Falha na inferencia de liveness passiva", e);
		}
	}

	/**
	 * Expande a caixa do rosto pelo fator de escala em torno do centro, com recorte quadrado
	 * limitado a imagem (mesma logica do Silent-Face original: preserva contexto ao redor do rosto).
	 */
	private static int[] expandBox(int width, int height, Detection face, float scale) {
		float boxW = face.width();
		float boxH = face.height();
		float effScale = Math.min((height - 1) / boxH, Math.min((width - 1) / boxW, scale));
		float newW = boxW * effScale;
		float newH = boxH * effScale;
		float centerX = face.x1() + boxW / 2f;
		float centerY = face.y1() + boxH / 2f;
		float leftX = centerX - newW / 2f;
		float leftY = centerY - newH / 2f;
		float rightX = centerX + newW / 2f;
		float rightY = centerY + newH / 2f;
		if (leftX < 0) {
			rightX -= leftX;
			leftX = 0;
		}
		if (leftY < 0) {
			rightY -= leftY;
			leftY = 0;
		}
		if (rightX > width - 1) {
			leftX -= (rightX - width + 1);
			rightX = width - 1;
		}
		if (rightY > height - 1) {
			leftY -= (rightY - height + 1);
			rightY = height - 1;
		}
		return new int[] {(int) leftX, (int) leftY, (int) rightX, (int) rightY};
	}

	private static BufferedImage cropResize(BufferedImage image, int[] box) {
		int w = Math.max(1, box[2] - box[0]);
		int h = Math.max(1, box[3] - box[1]);
		BufferedImage sub = image.getSubimage(box[0], box[1], w, h);
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(sub, 0, 0, SIZE, SIZE, null);
		g.dispose();
		return out;
	}

	/** Normalizacao do Silent-Face: canais BGR divididos por 255, layout CHW. */
	private static float[] toBgrBlob(BufferedImage image) {
		int area = SIZE * SIZE;
		int[] pixels = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
		float[] blob = new float[3 * area];
		for (int i = 0; i < area; i++) {
			int p = pixels[i];
			blob[i] = (p & 0xFF) / 255f; // B
			blob[area + i] = ((p >> 8) & 0xFF) / 255f; // G
			blob[2 * area + i] = ((p >> 16) & 0xFF) / 255f; // R
		}
		return blob;
	}

	private static float[] softmax(float[] logits) {
		float max = Float.NEGATIVE_INFINITY;
		for (float v : logits) {
			max = Math.max(max, v);
		}
		double sum = 0;
		float[] out = new float[logits.length];
		for (int i = 0; i < logits.length; i++) {
			out[i] = (float) Math.exp(logits[i] - max);
			sum += out[i];
		}
		for (int i = 0; i < out.length; i++) {
			out[i] /= sum;
		}
		return out;
	}

	@Override
	public void close() throws OrtException {
		modelV2.close();
		modelV1se.close();
	}
}
