package com.ulisses.face.engine;

import java.awt.image.BufferedImage;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Alinhamento facial padrao ArcFace: estima a transformacao de similaridade (Umeyama) que leva os
 * 5 landmarks detectados ate as posicoes canonicas de um rosto frontal 112x112 e amostra a imagem
 * com interpolacao bilinear. O alinhamento e essencial para a precisao do embedding.
 */
public final class FaceAligner {

	public static final int SIZE = 112;

	/** Posicoes canonicas dos 5 landmarks no template ArcFace 112x112. */
	private static final double[][] TEMPLATE = {
		{38.2946, 51.6963},
		{73.5318, 51.5014},
		{56.0252, 71.7366},
		{41.5493, 92.3655},
		{70.7299, 92.2041}
	};

	private FaceAligner() {}

	public static BufferedImage align(BufferedImage image, float[] kps) {
		double[][] forward = umeyama(kps);
		double[][] inverse = invertAffine(forward);

		int width = image.getWidth();
		int height = image.getHeight();
		int[] sourcePixels = image.getRGB(0, 0, width, height, null, 0, width);
		int[] alignedPixels = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				double sx = inverse[0][0] * x + inverse[0][1] * y + inverse[0][2];
				double sy = inverse[1][0] * x + inverse[1][1] * y + inverse[1][2];
				alignedPixels[y * SIZE + x] = bilinearSample(sourcePixels, width, height, sx, sy);
			}
		}
		BufferedImage aligned = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		aligned.setRGB(0, 0, SIZE, SIZE, alignedPixels, 0, SIZE);
		return aligned;
	}

	/** Transformacao de similaridade (rotacao + escala + translacao) src->template via SVD. */
	private static double[][] umeyama(float[] kps) {
		int n = TEMPLATE.length;
		double meanSrcX = 0, meanSrcY = 0, meanDstX = 0, meanDstY = 0;
		for (int i = 0; i < n; i++) {
			meanSrcX += kps[2 * i];
			meanSrcY += kps[2 * i + 1];
			meanDstX += TEMPLATE[i][0];
			meanDstY += TEMPLATE[i][1];
		}
		meanSrcX /= n;
		meanSrcY /= n;
		meanDstX /= n;
		meanDstY /= n;

		double[][] cov = new double[2][2];
		double varSrc = 0;
		for (int i = 0; i < n; i++) {
			double sx = kps[2 * i] - meanSrcX;
			double sy = kps[2 * i + 1] - meanSrcY;
			double dx = TEMPLATE[i][0] - meanDstX;
			double dy = TEMPLATE[i][1] - meanDstY;
			cov[0][0] += dx * sx;
			cov[0][1] += dx * sy;
			cov[1][0] += dy * sx;
			cov[1][1] += dy * sy;
			varSrc += sx * sx + sy * sy;
		}
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				cov[i][j] /= n;
			}
		}
		varSrc /= n;

		SingularValueDecomposition svd =
				new SingularValueDecomposition(MatrixUtils.createRealMatrix(cov));
		RealMatrix u = svd.getU();
		RealMatrix v = svd.getV();
		double[] singularValues = svd.getSingularValues();
		double sign = det2(u) * det2(v) < 0 ? -1 : 1;
		RealMatrix correction = MatrixUtils.createRealDiagonalMatrix(new double[] {1, sign});
		RealMatrix rotation = u.multiply(correction).multiply(v.transpose());
		double scale = (singularValues[0] + singularValues[1] * sign) / varSrc;

		double tx = meanDstX
				- scale * (rotation.getEntry(0, 0) * meanSrcX + rotation.getEntry(0, 1) * meanSrcY);
		double ty = meanDstY
				- scale * (rotation.getEntry(1, 0) * meanSrcX + rotation.getEntry(1, 1) * meanSrcY);
		return new double[][] {
			{scale * rotation.getEntry(0, 0), scale * rotation.getEntry(0, 1), tx},
			{scale * rotation.getEntry(1, 0), scale * rotation.getEntry(1, 1), ty}
		};
	}

	private static double det2(RealMatrix m) {
		return m.getEntry(0, 0) * m.getEntry(1, 1) - m.getEntry(0, 1) * m.getEntry(1, 0);
	}

	private static double[][] invertAffine(double[][] m) {
		double a = m[0][0], b = m[0][1], c = m[0][2];
		double d = m[1][0], e = m[1][1], f = m[1][2];
		double det = a * e - b * d;
		if (Math.abs(det) < 1e-12) {
			throw new IllegalStateException("Transformacao de alinhamento degenerada");
		}
		return new double[][] {
			{e / det, -b / det, (b * f - c * e) / det},
			{-d / det, a / det, (c * d - a * f) / det}
		};
	}

	private static int bilinearSample(int[] pixels, int width, int height, double x, double y) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		double fx = x - x0;
		double fy = y - y0;

		int p00 = pixelAt(pixels, width, height, x0, y0);
		int p10 = pixelAt(pixels, width, height, x0 + 1, y0);
		int p01 = pixelAt(pixels, width, height, x0, y0 + 1);
		int p11 = pixelAt(pixels, width, height, x0 + 1, y0 + 1);

		int r = blend((p00 >> 16) & 0xFF, (p10 >> 16) & 0xFF, (p01 >> 16) & 0xFF, (p11 >> 16) & 0xFF, fx, fy);
		int g = blend((p00 >> 8) & 0xFF, (p10 >> 8) & 0xFF, (p01 >> 8) & 0xFF, (p11 >> 8) & 0xFF, fx, fy);
		int b = blend(p00 & 0xFF, p10 & 0xFF, p01 & 0xFF, p11 & 0xFF, fx, fy);
		return (r << 16) | (g << 8) | b;
	}

	private static int pixelAt(int[] pixels, int width, int height, int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return 0;
		}
		return pixels[y * width + x];
	}

	private static int blend(int c00, int c10, int c01, int c11, double fx, double fy) {
		double top = c00 * (1 - fx) + c10 * fx;
		double bottom = c01 * (1 - fx) + c11 * fx;
		int value = (int) Math.round(top * (1 - fy) + bottom * fy);
		return Math.max(0, Math.min(255, value));
	}
}
