package com.ulisses.face.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes dos primitivos de comparacao de embeddings usados na decisao de identidade. */
class FaceEmbedderTest {

	@Test
	@DisplayName("cosseno de vetores identicos e 1")
	void cosineOfIdenticalVectorsIsOne() {
		float[] v = FaceEmbedder.normalize(new float[] {1, 2, 3, 4, 5});
		assertEquals(1.0f, FaceEmbedder.cosine(v, v), 1e-5);
	}

	@Test
	@DisplayName("cosseno de vetores ortogonais e 0")
	void cosineOfOrthogonalVectorsIsZero() {
		float[] a = FaceEmbedder.normalize(new float[] {1, 0, 1, 0});
		float[] b = FaceEmbedder.normalize(new float[] {0, 1, 0, 1});
		assertEquals(0.0f, FaceEmbedder.cosine(a, b), 1e-5);
	}

	@Test
	@DisplayName("cosseno de vetores opostos e -1")
	void cosineOfOppositeVectorsIsMinusOne() {
		float[] a = FaceEmbedder.normalize(new float[] {1, 2, 3});
		float[] b = FaceEmbedder.normalize(new float[] {-1, -2, -3});
		assertEquals(-1.0f, FaceEmbedder.cosine(a, b), 1e-5);
	}

	@Test
	@DisplayName("normalize produz vetor de norma unitaria")
	void normalizeProducesUnitLength() {
		float[] v = FaceEmbedder.normalize(new float[] {3, 4, 12, 0});
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		assertEquals(1.0, Math.sqrt(norm), 1e-5);
	}

	@Test
	@DisplayName("cosseno e simetrico")
	void cosineIsSymmetric() {
		float[] a = FaceEmbedder.normalize(new float[] {0.2f, 0.9f, -0.3f, 0.5f});
		float[] b = FaceEmbedder.normalize(new float[] {0.7f, -0.1f, 0.4f, 0.6f});
		assertEquals(FaceEmbedder.cosine(a, b), FaceEmbedder.cosine(b, a), 1e-6);
	}

	@Test
	@DisplayName("normalize de vetor nulo nao quebra")
	void normalizeOfZeroVectorIsSafe() {
		float[] v = FaceEmbedder.normalize(new float[] {0, 0, 0});
		assertTrue(Float.isFinite(v[0]) && Float.isFinite(v[1]) && Float.isFinite(v[2]));
	}
}
