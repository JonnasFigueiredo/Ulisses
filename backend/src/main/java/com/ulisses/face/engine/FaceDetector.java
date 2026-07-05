package com.ulisses.face.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detector facial SCRFD (InsightFace det_10g). Recebe uma imagem qualquer, faz letterbox para
 * 640x640, roda a rede e decodifica caixas + 5 landmarks por rosto com NMS.
 */
public final class FaceDetector implements AutoCloseable {

	private static final int INPUT_SIZE = 640;
	private static final int[] STRIDES = {8, 16, 32};
	private static final int NUM_ANCHORS = 2;
	private static final float NMS_IOU = 0.4f;

	private final OrtEnvironment env;
	private final OrtSession session;
	private final String inputName;
	private final float minScore;

	public FaceDetector(OrtEnvironment env, Path modelPath, float minScore) throws OrtException {
		this.env = env;
		this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
		this.inputName = session.getInputNames().iterator().next();
		this.minScore = minScore;
	}

	public synchronized List<Detection> detect(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		float scale = Math.min((float) INPUT_SIZE / width, (float) INPUT_SIZE / height);
		int scaledW = Math.round(width * scale);
		int scaledH = Math.round(height * scale);

		BufferedImage letterbox = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = letterbox.createGraphics();
		g.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, scaledW, scaledH, null);
		g.dispose();

		float[] blob = toBlob(letterbox);
		try (OnnxTensor input = OnnxTensor.createTensor(
						env, FloatBuffer.wrap(blob), new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
				OrtSession.Result result = session.run(Map.of(inputName, input))) {
			List<Detection> candidates = new ArrayList<>();
			for (int s = 0; s < STRIDES.length; s++) {
				float[] scores = toFloatArray(result.get(s));
				float[] boxes = toFloatArray(result.get(s + 3));
				float[] kpss = toFloatArray(result.get(s + 6));
				decodeStride(STRIDES[s], scores, boxes, kpss, candidates);
			}
			List<Detection> kept = nms(candidates);
			List<Detection> output = new ArrayList<>(kept.size());
			for (Detection d : kept) {
				output.add(d.scaled(1f / scale, width, height));
			}
			return output;
		} catch (OrtException e) {
			throw new IllegalStateException("Falha na inferencia do detector facial", e);
		}
	}

	/** Normalizacao do SCRFD: (pixel - 127.5) / 128, canais RGB em layout CHW. */
	private static float[] toBlob(BufferedImage image) {
		int area = INPUT_SIZE * INPUT_SIZE;
		int[] pixels = image.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
		float[] blob = new float[3 * area];
		for (int i = 0; i < area; i++) {
			int p = pixels[i];
			blob[i] = (((p >> 16) & 0xFF) - 127.5f) / 128f;
			blob[area + i] = (((p >> 8) & 0xFF) - 127.5f) / 128f;
			blob[2 * area + i] = ((p & 0xFF) - 127.5f) / 128f;
		}
		return blob;
	}

	private void decodeStride(
			int stride, float[] scores, float[] boxes, float[] kpss, List<Detection> out) {
		int featureMap = INPUT_SIZE / stride;
		int total = featureMap * featureMap * NUM_ANCHORS;
		for (int idx = 0; idx < total && idx < scores.length; idx++) {
			float score = scores[idx];
			if (score < minScore) {
				continue;
			}
			int cell = idx / NUM_ANCHORS;
			float cx = (cell % featureMap) * stride;
			float cy = (cell / featureMap) * stride;
			float x1 = cx - boxes[idx * 4] * stride;
			float y1 = cy - boxes[idx * 4 + 1] * stride;
			float x2 = cx + boxes[idx * 4 + 2] * stride;
			float y2 = cy + boxes[idx * 4 + 3] * stride;
			float[] kps = new float[10];
			for (int k = 0; k < 5; k++) {
				kps[2 * k] = cx + kpss[idx * 10 + 2 * k] * stride;
				kps[2 * k + 1] = cy + kpss[idx * 10 + 2 * k + 1] * stride;
			}
			out.add(new Detection(score, x1, y1, x2, y2, kps));
		}
	}

	private static List<Detection> nms(List<Detection> candidates) {
		candidates.sort((a, b) -> Float.compare(b.score(), a.score()));
		List<Detection> kept = new ArrayList<>();
		for (Detection candidate : candidates) {
			boolean overlaps = false;
			for (Detection existing : kept) {
				if (iou(candidate, existing) > NMS_IOU) {
					overlaps = true;
					break;
				}
			}
			if (!overlaps) {
				kept.add(candidate);
			}
		}
		return kept;
	}

	private static float iou(Detection a, Detection b) {
		float ix1 = Math.max(a.x1(), b.x1());
		float iy1 = Math.max(a.y1(), b.y1());
		float ix2 = Math.min(a.x2(), b.x2());
		float iy2 = Math.min(a.y2(), b.y2());
		float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
		float union = a.area() + b.area() - inter;
		return union <= 0 ? 0 : inter / union;
	}

	private static float[] toFloatArray(OnnxValue value) throws OrtException {
		FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
		float[] array = new float[buffer.remaining()];
		buffer.get(array);
		return array;
	}

	@Override
	public void close() throws OrtException {
		session.close();
	}
}
