package com.ulisses.face;

import com.ulisses.common.ApiException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

/** Limita as dimensoes pelo cabecalho antes de alocar os pixels da imagem enviada. */
final class FrameDecoder {

	private static final int MAX_DIMENSION = 4096;
	private static final long MAX_PIXELS = 4_000_000;

	private FrameDecoder() {}

	static BufferedImage decode(MultipartFile file) {
		try (InputStream source = file.getInputStream();
				var input = new MemoryCacheImageInputStream(source)) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) return null;
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) {
					throw ApiException.badRequest("Frame excede o limite de 4096 pixels por lado ou 4 megapixels");
				}
				BufferedImage image = reader.read(0);
				if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
				BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = rgb.createGraphics();
				try {
					graphics.drawImage(image, 0, 0, null);
				} finally {
					graphics.dispose();
				}
				return rgb;
			} finally {
				reader.dispose();
			}
		} catch (IOException e) {
			return null;
		}
	}
}
