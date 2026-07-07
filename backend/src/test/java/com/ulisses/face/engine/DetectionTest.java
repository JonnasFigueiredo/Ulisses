package com.ulisses.face.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes da geometria extraida da deteccao (pose e qualidade), base da prova de vida. */
class DetectionTest {

	/** kps: olho-esquerdo, olho-direito, nariz, boca-esq, boca-dir (coordenadas de imagem). */
	private static Detection withNose(float noseX) {
		float[] kps = {0, 100, 100, 100, noseX, 130, 30, 160, 70, 160};
		return new Detection(0.9f, 0, 50, 200, 250, kps);
	}

	@Test
	@DisplayName("nariz centralizado entre os olhos da noseRatio ~0.5")
	void noseCenteredGivesHalf() {
		assertEquals(0.5f, withNose(50).noseRatio(), 1e-4);
	}

	@Test
	@DisplayName("nariz deslocado para a direita da imagem aumenta a noseRatio")
	void noseTowardRightIncreasesRatio() {
		assertTrue(withNose(70).noseRatio() > 0.5f);
	}

	@Test
	@DisplayName("nariz deslocado para a esquerda da imagem diminui a noseRatio")
	void noseTowardLeftDecreasesRatio() {
		assertTrue(withNose(30).noseRatio() < 0.5f);
	}

	@Test
	@DisplayName("interocular e a distancia entre os olhos em pixels")
	void interocularIsEyeDistance() {
		assertEquals(100f, withNose(50).interocular(), 1e-4);
	}

	/** kps com nariz em altura configuravel (olhos y=100, boca y=160). */
	private static Detection withNoseY(float noseY) {
		float[] kps = {0, 100, 100, 100, 50, noseY, 30, 160, 70, 160};
		return new Detection(0.9f, 0, 50, 200, 250, kps);
	}

	@Test
	@DisplayName("nariz no meio entre olhos e boca da noseVerticalRatio ~0.5")
	void noseVerticalCenteredGivesHalf() {
		assertEquals(0.5f, withNoseY(130).noseVerticalRatio(), 1e-4);
	}

	@Test
	@DisplayName("inclinar a cabeca para CIMA aproxima o nariz dos olhos (ratio diminui)")
	void pitchUpDecreasesVerticalRatio() {
		assertTrue(withNoseY(112).noseVerticalRatio() < 0.5f);
	}

	@Test
	@DisplayName("inclinar a cabeca para BAIXO aproxima o nariz da boca (ratio aumenta)")
	void pitchDownIncreasesVerticalRatio() {
		assertTrue(withNoseY(148).noseVerticalRatio() > 0.5f);
	}

	@Test
	@DisplayName("width, height e area vem da caixa delimitadora")
	void boxGeometry() {
		Detection d = withNose(50);
		assertEquals(200f, d.width(), 1e-4);
		assertEquals(200f, d.height(), 1e-4);
		assertEquals(40000f, d.area(), 1e-1);
	}
}
