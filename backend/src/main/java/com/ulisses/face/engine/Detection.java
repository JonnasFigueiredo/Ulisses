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
	 * Posicao horizontal relativa do nariz entre os olhos (0 = colado no olho mais a esquerda da
	 * imagem, 1 = no olho mais a direita). ~0.5 com o rosto de frente; desvia conforme a cabeca gira.
	 * Usado na prova de vida por desafio de movimento.
	 */
	public float noseRatio() {
		float eyeLeftX = Math.min(kps[0], kps[2]);
		float eyeRightX = Math.max(kps[0], kps[2]);
		float span = eyeRightX - eyeLeftX;
		if (span < 1e-3f) {
			return 0.5f;
		}
		float ratio = (kps[4] - eyeLeftX) / span;
		return Math.max(-1f, Math.min(2f, ratio));
	}

	/**
	 * Posicao vertical relativa do nariz entre a linha dos olhos (0) e a linha da boca (1).
	 * ~0.5 com o rosto neutro; inclinar a cabeca para CIMA projeta o nariz em direcao aos olhos
	 * (ratio diminui) e para BAIXO em direcao a boca (ratio aumenta). Usado nos desafios verticais
	 * da prova de vida — sempre comparado a linha de base do proprio usuario, o que neutraliza o
	 * angulo da camera (webcam abaixo/acima do rosto).
	 */
	public float noseVerticalRatio() {
		float eyeMidY = (kps[1] + kps[3]) / 2f;
		float mouthMidY = (kps[7] + kps[9]) / 2f;
		float span = mouthMidY - eyeMidY;
		if (Math.abs(span) < 1e-3f) {
			return 0.5f;
		}
		float ratio = (kps[5] - eyeMidY) / span;
		return Math.max(-1f, Math.min(2f, ratio));
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
