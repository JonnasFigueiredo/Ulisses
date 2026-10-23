package com.ulisses.face;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ulisses.common.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class FrameDecoderTest {

	@Test
	void decodesNormalCaptureAsRgb() throws Exception {
		BufferedImage result = FrameDecoder.decode(file(png()));
		assertEquals(2, result.getWidth());
		assertEquals(2, result.getHeight());
		assertEquals(BufferedImage.TYPE_INT_RGB, result.getType());
		assertEquals(0xFF123456, result.getRGB(0, 0));
	}

	@Test
	void rejectsLargeDimensionsBeforeDecodingThePixelData() throws Exception {
		assertLargeHeaderRejected(100_000, 100_000);
		assertLargeHeaderRejected(3000, 3000);
		assertLargeHeaderRejected(4097, 2);
	}

	@Test
	void invalidAndEmptyFilesDoNotReachInference() {
		assertNull(FrameDecoder.decode(file(new byte[0])));
		assertNull(FrameDecoder.decode(file(new byte[] {1, 2, 3, 4})));
	}

	private void assertLargeHeaderRejected(int width, int height) throws Exception {
		// Apenas o cabecalho e modificado: o teste nunca aloca uma imagem grande.
		byte[] bytes = png();
		ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
		CRC32 crc = new CRC32();
		crc.update(bytes, 12, 17);
		ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
		ApiException error = assertThrows(ApiException.class, () -> FrameDecoder.decode(file(bytes)));
		assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
	}

	private byte[] png() throws Exception {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xFF123456);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private MockMultipartFile file(byte[] bytes) {
		return new MockMultipartFile("frame", "frame.png", "image/png", bytes);
	}
}
