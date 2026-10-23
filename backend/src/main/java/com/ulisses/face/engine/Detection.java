package com.ulisses.face.engine;

/**
 * Rosto detectado pelo SCRFD: caixa delimitadora, confianca e 5 pontos de referencia
 * (olho esquerdo-imagem, olho direito-imagem, nariz, canto esquerdo da boca, canto direito da boca),
 * em coordenadas da imagem original.
 */
public record Detection(float score, float x1, float y1, float x2, float y2, float[] kps) {

	public float width() {
		return x2 - x1;
	}

	public float height() {
		return y2 - y1;
	}

	public float area() {
		return Math.max(0, width()) * Math.max(0, height());
	}

	public Detection scaled(float factor, int maxWidth, int maxHeight) {
		float[] scaledKps = new float[kps.length];
		for (int i = 0; i < kps.length; i += 2) {
			scaledKps[i] = clamp(kps[i] * factor, maxWidth - 1);
			scaledKps[i + 1] = clamp(kps[i + 1] * factor, maxHeight - 1);
		}
		return new Detection(
				score,
				clamp(x1 * factor, maxWidth - 1),
				clamp(y1 * factor, maxHeight - 1),
				clamp(x2 * factor, maxWidth - 1),
				clamp(y2 * factor, maxHeight - 1),
				scaledKps);
	}

	/**
	 * Sinal de YAW (giro horizontal), ~0 com o rosto de frente. Medido projetando o nariz sobre a
	 * LINHA DOS OLHOS (eixo esquerda→direita) e normalizando pela distancia interocular. Como usa a
	 * direcao real da linha dos olhos (nao o eixo X da imagem), e invariante a inclinacao da cabeca
	 * (roll) e ao angulo da camera. Aumenta ao girar para um lado, diminui para o outro.
	 */
	public float noseRatio() {
		return (float) facePose()[0];
	}

	/**
	 * Sinal de PITCH (inclinacao vertical), medido projetando o nariz sobre o eixo OLHOS→BOCA e
	 * normalizando pela distancia olhos-boca (a "altura" propria do rosto). Tambem roll-corrigido.
	 * Inclinar para cima aproxima o nariz da linha dos olhos (diminui); para baixo, da boca (aumenta).
	 */
	public float noseVerticalRatio() {
		return (float) facePose()[1];
	}

	/**
	 * Pose do rosto a partir das linhas dos landmarks: {yaw, pitch} normalizados, roll-corrigidos.
	 * Constroi o referencial proprio do rosto (ex = linha dos olhos; ey = perpendicular apontando
	 * para a boca) e projeta o nariz nele. Rotacionar todos os pontos (roll) preserva os produtos
	 * escalares, entao yaw/pitch nao mudam — e isso que torna a medida robusta.
	 */
	private double[] facePose() {
		double eLx = kps[0], eLy = kps[1], eRx = kps[2], eRy = kps[3];
		double nx = kps[4], ny = kps[5];
		double emx = (eLx + eRx) / 2.0, emy = (eLy + eRy) / 2.0; // meio dos olhos
		double mmx = (kps[6] + kps[8]) / 2.0, mmy = (kps[7] + kps[9]) / 2.0; // meio da boca

		double exx = eRx - eLx, exy = eRy - eLy; // direcao da linha dos olhos
		double io = Math.hypot(exx, exy);
		if (io < 1e-3) {
			return new double[] {0.0, 0.5};
		}
		exx /= io;
		exy /= io;
		double eyx = -exy, eyy = exx; // perpendicular
		if ((mmx - emx) * eyx + (mmy - emy) * eyy < 0) { // garante que aponta para a boca
			eyx = -eyx;
			eyy = -eyy;
		}
		double vx = nx - emx, vy = ny - emy;
		double noseX = vx * exx + vy * exy; // nariz ao longo da linha dos olhos
		double noseY = vx * eyx + vy * eyy; // nariz no eixo olhos→boca
		double eyeMouth = (mmx - emx) * eyx + (mmy - emy) * eyy; // altura olhos-boca (roll-corrigida)

		double yaw = noseX / io;
		double pitch = Math.abs(eyeMouth) < 1e-3 ? 0.5 : noseY / eyeMouth;
		return new double[] {
			Math.max(-1.5, Math.min(1.5, yaw)), Math.max(-1.0, Math.min(2.0, pitch))
		};
	}

	/** Angulo da linha dos olhos em relacao a horizontal (roll), em graus. */
	public float roll() {
		return (float) Math.toDegrees(Math.atan2(kps[3] - kps[1], kps[2] - kps[0]));
	}

	/** Distancia interocular em pixels — metrica de qualidade/resolucao do rosto (ISO/IEC 19794-5). */
	public float interocular() {
		float dx = kps[2] - kps[0];
		float dy = kps[3] - kps[1];
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	private static float clamp(float value, float max) {
		return Math.max(0, Math.min(value, max));
	}
}
