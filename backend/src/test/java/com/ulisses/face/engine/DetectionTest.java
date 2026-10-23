package com.ulisses.face.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes da pose extraida das linhas do rosto (yaw/pitch roll-corrigidos), base da prova de vida. */
class DetectionTest {

	/** kps: olho-esq, olho-dir, nariz, boca-esq, boca-dir (olhos y=100, boca y=160, interocular 100). */
	private static Detection withNose(float noseX) {
		float[] kps = {0, 100, 100, 100, noseX, 130, 30, 160, 70, 160};
		return new Detection(0.9f, 0, 50, 200, 250, kps);
	}

	private static Detection withNoseY(float noseY) {
		float[] kps = {0, 100, 100, 100, 50, noseY, 30, 160, 70, 160};
		return new Detection(0.9f, 0, 50, 200, 250, kps);
	}

	@Test
	@DisplayName("rosto frontal (nariz centralizado) da yaw ~0")
	void frontalGivesZeroYaw() {
		assertEquals(0f, withNose(50).noseRatio(), 1e-4);
	}

	@Test
	@DisplayName("nariz para a direita da linha dos olhos aumenta o yaw")
	void noseTowardRightIncreasesYaw() {
		assertTrue(withNose(70).noseRatio() > 0.05f);
	}

	@Test
	@DisplayName("nariz para a esquerda da linha dos olhos diminui o yaw")
	void noseTowardLeftDecreasesYaw() {
		assertTrue(withNose(30).noseRatio() < -0.05f);
	}

	@Test
	@DisplayName("interocular e a distancia entre os olhos em pixels")
	void interocularIsEyeDistance() {
		assertEquals(100f, withNose(50).interocular(), 1e-4);
	}

	@Test
	@DisplayName("nariz no meio entre olhos e boca da pitch ~0.5")
	void noseVerticalCenteredGivesHalf() {
		assertEquals(0.5f, withNoseY(130).noseVerticalRatio(), 1e-4);
	}

	@Test
	@DisplayName("inclinar para CIMA aproxima o nariz dos olhos (pitch diminui)")
	void pitchUpDecreasesRatio() {
		assertTrue(withNoseY(112).noseVerticalRatio() < 0.5f);
	}

	@Test
	@DisplayName("inclinar para BAIXO aproxima o nariz da boca (pitch aumenta)")
	void pitchDownIncreasesRatio() {
		assertTrue(withNoseY(148).noseVerticalRatio() > 0.5f);
	}

	/** Rotaciona todos os landmarks por 'deg' em torno de (cx,cy) — simula inclinacao da cabeca (roll). */
	private static Detection rotated(float[] kps, double deg, double cx, double cy) {
		double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
		float[] out = new float[kps.length];
		for (int i = 0; i < kps.length; i += 2) {
			double x = kps[i] - cx, y = kps[i + 1] - cy;
			out[i] = (float) (cx + x * c - y * s);
			out[i + 1] = (float) (cy + x * s + y * c);
		}
		return new Detection(0.9f, 0, 50, 200, 250, out);
	}

	@Test
	@DisplayName("yaw e pitch sao INVARIANTES a inclinacao da cabeca (roll) — a correcao real")
	void poseIsRollInvariant() {
		// Rosto com yaw e pitch fora do neutro.
		float[] base = {0, 100, 100, 100, 70, 120, 30, 160, 70, 160};
		Detection d0 = new Detection(0.9f, 0, 50, 200, 250, base);
		Detection rolled = rotated(base, 25, 50, 100); // cabeca inclinada 25 graus

		// A medida crua no eixo da imagem mudaria muito; a roll-corrigida quase nao muda:
		assertEquals(d0.noseRatio(), rolled.noseRatio(), 2e-3);
		assertEquals(d0.noseVerticalRatio(), rolled.noseVerticalRatio(), 2e-3);
		// E o roll e detectado corretamente:
		assertEquals(25f, rolled.roll(), 1.0f);
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
