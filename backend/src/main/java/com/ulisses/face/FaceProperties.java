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
		long challengeTtlSeconds) {}
