package com.ulisses.face.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * Contrato deterministico do fornecedor dos modelos, independente de camera e downloads.
 * Estes testes nao medem a eficacia do PAD nem calibram o limiar de aceitacao.
 */
class PassiveLivenessDetectorTest {

	@Test
	void preservesRawBgrValuesAndChannelFirstLayout() {
		BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x117FE6);
		image.setRGB(79, 79, 0xFF8000);

		float[] input = PassiveLivenessDetector.toBgrBlob(image);

		int area = 80 * 80;
		assertEquals(3 * area, input.length);
		// Referencia ONNX: image.astype(float32).transpose(2, 0, 1), sobre imagem BGR.
		assertEquals(230f, input[0]);
		assertEquals(127f, input[area]);
		assertEquals(17f, input[2 * area]);
		assertEquals(0f, input[area - 1]);
		assertEquals(128f, input[2 * area - 1]);
		assertEquals(255f, input[3 * area - 1]);
	}

	@Test
	void bundledModelsUseTheSuppliersLiveClass() throws Exception {
		var properties = new YamlPropertySourceLoader()
				.load("application", new ClassPathResource("application.yml")).get(0);
		String prefix = "ulisses.face.passive-liveness.";
		assertEquals(
				"https://github.com/yakhyo/face-anti-spoofing/releases/download/weights/MiniFASNetV2.onnx",
				properties.getProperty(prefix + "model-v2-url"));
		assertEquals(
				"https://github.com/yakhyo/face-anti-spoofing/releases/download/weights/MiniFASNetV1SE.onnx",
				properties.getProperty(prefix + "model-v1se-url"));
		// predict() no fornecedor define Real apenas para label_idx == 1.
		assertEquals(1, properties.getProperty(prefix + "live-class-index"));
	}
}
