package com.ulisses.face.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Extrator de embeddings ArcFace (InsightFace w600k_r50). Recebe um rosto ja alinhado em 112x112 e
 * produz um vetor de 512 dimensoes normalizado (L2). A similaridade entre dois rostos e o cosseno
 * entre seus vetores.
 */
public final class FaceEmbedder implements AutoCloseable {

	public static final int EMBEDDING_SIZE = 512;

	private final OrtEnvironment env;
	private final OrtSession session;
	private final String inputName;

	public FaceEmbedder(OrtEnvironment env, Path modelPath) throws OrtException {
		this.env = env;
		this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
		this.inputName = session.getInputNames().iterator().next();
	}

	public synchronized float[] embed(BufferedImage alignedFace) {
		if (alignedFace.getWidth() != FaceAligner.SIZE || alignedFace.getHeight() != FaceAligner.SIZE) {
			throw new IllegalArgumentException("O rosto deve estar alinhado em 112x112");
		}
		int area = FaceAligner.SIZE * FaceAligner.SIZE;
		int[] pixels = alignedFace.getRGB(0, 0, FaceAligner.SIZE, FaceAligner.SIZE, null, 0, FaceAligner.SIZE);
		float[] blob = new float[3 * area];
		for (int i = 0; i < area; i++) {
			int p = pixels[i];
			blob[i] = (((p >> 16) & 0xFF) - 127.5f) / 127.5f;
			blob[area + i] = (((p >> 8) & 0xFF) - 127.5f) / 127.5f;
			blob[2 * area + i] = ((p & 0xFF) - 127.5f) / 127.5f;
		}

		try (OnnxTensor input = OnnxTensor.createTensor(
						env,
						FloatBuffer.wrap(blob),
						new long[] {1, 3, FaceAligner.SIZE, FaceAligner.SIZE});
				OrtSession.Result result = session.run(Map.of(inputName, input))) {
			FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
			float[] embedding = new float[buffer.remaining()];
			buffer.get(embedding);
			return normalize(embedding);
		} catch (OrtException e) {
			throw new IllegalStateException("Falha na inferencia do extrator de embeddings", e);
		}
	}

	public static float[] normalize(float[] vector) {
		double norm = 0;
		for (float v : vector) {
			norm += v * v;
		}
		norm = Math.sqrt(norm);
		if (norm < 1e-10) {
			return vector;
		}
		float[] normalized = new float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			normalized[i] = (float) (vector[i] / norm);
		}
		return normalized;
	}

	/** Similaridade de cosseno entre dois vetores ja normalizados. */
	public static float cosine(float[] a, float[] b) {
		float dot = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
		}
		return dot;
	}

	@Override
	public void close() throws OrtException {
		session.close();
	}
}
