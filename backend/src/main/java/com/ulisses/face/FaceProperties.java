package com.ulisses.face;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ulisses.face")
public record FaceProperties(
		String modelsDir,
		String detectionModelUrl,
		String recognitionModelUrl,
		double matchThreshold,
		double minDetectionScore,
		int minFaceSize,
		int minEnrollInterocular,
		double minSharpness,
		long challengeTtlSeconds,
		boolean includeVerticalChallenges,
		PassiveLiveness passiveLiveness) {

	/**
	 * Liveness passiva (anti-spoofing por textura) via ensemble MiniFASNet (Silent-Face, Apache-2.0).
	 * AVISO: os limiares/indice de classe precisam ser calibrados e a eficacia validada contra um
	 * dataset real de ataques (ver PadEvaluationTest). NAO e uma solucao certificada ISO 30107-3.
	 */
	public record PassiveLiveness(
			boolean enabled,
			String modelV2Url,
			String modelV1seUrl,
			int liveClassIndex,
			double threshold) {}
}
